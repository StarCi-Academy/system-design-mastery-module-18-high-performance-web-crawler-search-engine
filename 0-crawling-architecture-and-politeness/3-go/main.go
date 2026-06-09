package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
)

const robotsTTL = 60 * time.Second

// fakeFetch is a hermetic in-process "fake internet" so the lesson runs without network.
func fakeFetch(rawURL, kind string) (int, string, string) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return 404, "", "text/plain"
	}
	if u.Hostname() != "books.starci.test" {
		return 404, "", "text/plain"
	}
	if kind == "robots" {
		return 200, "User-agent: *\nAllow: /\nDisallow: /admin\nCrawl-delay: 1", "text/plain"
	}
	pages := map[string]string{
		"/":       "<html><head><title>Books</title></head><body>Welcome to the books index page.</body></html>",
		"/book/1": "<html><head><title>Book 1</title></head><body>The first book in the catalog.</body></html>",
		"/book/2": "<html><head><title>Book 2</title></head><body>The second book in the catalog.</body></html>",
	}
	if body, ok := pages[u.Path]; ok {
		return 200, body, "text/html"
	}
	return 404, "", "text/html"
}

type rule struct {
	allow   bool
	pattern string
}

type robotsRules struct {
	crawlDelayMs int
	rules        []rule
}

// parseRobots parses a robots.txt body into crawl-delay and allow/deny rules.
// An empty body is valid (no directives = allow all); the caller must pass "" on
// non-200 status codes to avoid treating an error as a permissive robots file.
func parseRobots(body string) robotsRules {
	r := robotsRules{}
	for _, raw := range strings.Split(body, "\n") {
		line := strings.TrimSpace(strings.SplitN(raw, "#", 2)[0])
		if line == "" {
			continue
		}
		idx := strings.Index(line, ":")
		if idx == -1 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(line[:idx]))
		val := strings.TrimSpace(line[idx+1:])
		switch key {
		case "disallow":
			if val != "" {
				r.rules = append(r.rules, rule{false, val})
			}
		case "allow":
			if val != "" {
				r.rules = append(r.rules, rule{true, val})
			}
		case "crawl-delay":
			if sec, err := strconv.ParseFloat(val, 64); err == nil {
				r.crawlDelayMs = int(sec * 1000)
			}
		}
	}
	return r
}

// isAllowed applies the RFC 9309 longest-match rule; a tie resolves to Allow.
func (r robotsRules) isAllowed(path string) bool {
	var best *rule
	for i := range r.rules {
		ru := r.rules[i]
		if strings.HasPrefix(path, ru.pattern) {
			if best == nil || len(ru.pattern) > len(best.pattern) {
				best = &r.rules[i]
			} else if len(ru.pattern) == len(best.pattern) && ru.allow {
				best = &r.rules[i]
			}
		}
	}
	if best == nil {
		return true
	}
	return best.allow
}

// server holds the two stateful clients shared across all HTTP handlers.
// Using a struct receiver (rather than package globals) makes the dependency
// graph explicit and testable.
type server struct {
	// rdb is the Redis client used for the robots TTL cache and politeness clock.
	rdb *redis.Client
	// db is the Postgres connection pool used for UPSERT and SELECT on the page table.
	db *pgxpool.Pool
}

// loadRobots fetches a host's robots.txt at most once per TTL window, then caches
// both the raw body and the parsed crawl-delay under the same expiry.
func (s *server) loadRobots(ctx context.Context, host string, ref *url.URL) robotsRules {
	cacheKey := "crawler:robots:" + host + ":body"
	var body string
	cached, err := s.rdb.Get(ctx, cacheKey).Result()
	if err == nil {
		// A cache hit (including an empty body = valid allow-all) is returned as-is.
		body = cached
	} else {
		status, fetched, _ := fakeFetch(ref.Scheme+"://"+host+"/robots.txt", "robots")
		// Treat any non-200 response as "no rules" — empty body = allow all.
		if status == 200 {
			body = fetched
		}
		s.rdb.Set(ctx, cacheKey, body, robotsTTL)
	}
	rules := parseRobots(body)
	// Cache the parsed delay under the same TTL so body + delay refresh together.
	s.rdb.Set(ctx, "crawler:robots:"+host+":delay", strconv.Itoa(rules.crawlDelayMs), robotsTTL)
	return rules
}

// applyPoliteness derives the wait from the SHARED Redis clock, not local memory.
func (s *server) applyPoliteness(ctx context.Context, host string, delayMs int) int {
	if delayMs <= 0 {
		return 0
	}
	last, err := s.rdb.Get(ctx, "crawler:host:"+host+":lastFetched").Result()
	if err != nil {
		return 0
	}
	lastMs, _ := strconv.ParseInt(last, 10, 64)
	elapsed := time.Now().UnixMilli() - lastMs
	wait := int64(delayMs) - elapsed
	if wait < 0 {
		wait = 0
	}
	if wait > 0 {
		log.Printf("Politeness wait %dms for %s", wait, host)
		time.Sleep(time.Duration(wait) * time.Millisecond)
	}
	return int(wait)
}

// seed ties robots loading, allow check, politeness wait, fetch, and persistence
// into one observable request that returns the manifest the flows assert on.
func (s *server) seed(w http.ResponseWriter, r *http.Request) {
	var req struct {
		URL string `json:"url"`
	}
	json.NewDecoder(r.Body).Decode(&req)
	ctx := r.Context()
	ref, err := url.Parse(req.URL)
	if err != nil {
		http.Error(w, "bad url", 400)
		return
	}
	host := ref.Hostname()
	rules := s.loadRobots(ctx, host, ref)
	if !rules.isAllowed(ref.Path) {
		// Disallowed paths never reach the network: 0 bytes stored, allowed=false.
		writeJSON(w, 200, map[string]any{"url": req.URL, "host": host, "statusCode": 0, "bytesStored": 0, "waitedMs": 0, "allowed": false})
		return
	}
	waited := s.applyPoliteness(ctx, host, rules.crawlDelayMs)
	status, body, contentType := fakeFetch(req.URL, "page")
	// Record lastFetched immediately after the fetch so the clock reflects this request.
	s.rdb.Set(ctx, "crawler:host:"+host+":lastFetched", strconv.FormatInt(time.Now().UnixMilli(), 10), 0)
	// UPSERT by url so re-seeding the same URL updates instead of duplicating.
	s.db.Exec(ctx,
		`INSERT INTO page (url, host, html_body, content_type, status_code, fetched_at)
		 VALUES ($1,$2,$3,$4,$5, now())
		 ON CONFLICT (url) DO UPDATE SET html_body=EXCLUDED.html_body, status_code=EXCLUDED.status_code, fetched_at=now()`,
		req.URL, host, body, contentType, status)
	writeJSON(w, 200, map[string]any{"url": req.URL, "host": host, "statusCode": status, "bytesStored": len(body), "waitedMs": waited, "allowed": true})
}

// pages returns all crawled pages ordered by fetched_at descending.
// Only allowed pages are stored, so Disallow-ed paths never appear here.
func (s *server) pages(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `SELECT url, host, status_code FROM page ORDER BY fetched_at DESC`)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	defer rows.Close()
	out := []map[string]any{}
	for rows.Next() {
		var u, h string
		var sc int
		rows.Scan(&u, &h, &sc)
		out = append(out, map[string]any{"url": u, "host": h, "statusCode": sc})
	}
	writeJSON(w, 200, out)
}

// politeness returns the current politeness state for a host from Redis.
// Returns 404 if the host has never been crawled (no politeness state exists).
func (s *server) politeness(w http.ResponseWriter, r *http.Request) {
	host := chi.URLParam(r, "host")
	ctx := r.Context()
	delay, err1 := s.rdb.Get(ctx, "crawler:robots:"+host+":delay").Result()
	last, err2 := s.rdb.Get(ctx, "crawler:host:"+host+":lastFetched").Result()
	if err1 != nil || err2 != nil {
		writeJSON(w, 404, map[string]any{"statusCode": 404, "message": "No politeness state for host " + host})
		return
	}
	delayMs, _ := strconv.Atoi(delay)
	lastMs, _ := strconv.ParseInt(last, 10, 64)
	writeJSON(w, 200, map[string]any{
		"host":          host,
		"crawlDelayMs":  delayMs,
		"lastFetchedAt": time.UnixMilli(lastMs).UTC().Format("2006-01-02T15:04:05.000Z"),
		"nextAllowedAt": time.UnixMilli(lastMs + int64(delayMs)).UTC().Format("2006-01-02T15:04:05.000Z"),
	})
}

// writeJSON serialises v to JSON and writes it with the given HTTP status code.
// A single helper keeps all handlers consistent and avoids forgetting Content-Type.
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// main wires the Redis client, Postgres pool, HTTP router, and starts the server.
func main() {
	ctx := context.Background()
	redisURL := getenv("REDIS_URL", "redis://localhost:6379")
	opt, _ := redis.ParseURL(redisURL)
	rdb := redis.NewClient(opt)
	db, err := pgxpool.New(ctx, getenv("POSTGRES_URL", "postgres://crawler:crawler@localhost:5432/crawler"))
	if err != nil {
		log.Fatal(err)
	}
	db.Exec(ctx, `CREATE TABLE IF NOT EXISTS page (
		id BIGSERIAL PRIMARY KEY,
		url VARCHAR UNIQUE NOT NULL,
		host VARCHAR NOT NULL,
		html_body TEXT NOT NULL,
		content_type VARCHAR NOT NULL,
		status_code INT NOT NULL,
		fetched_at TIMESTAMPTZ NOT NULL DEFAULT now())`)
	s := &server{rdb: rdb, db: db}
	r := chi.NewRouter()
	r.Post("/api/crawler/seed", s.seed)
	r.Get("/api/crawler/pages", s.pages)
	r.Get("/api/crawler/politeness/{host}", s.politeness)
	port := getenv("PORT", "3000")
	log.Printf("crawler-service listening on %s", port)
	http.ListenAndServe(":"+port, r)
}

// getenv returns the environment variable k or the default d if unset or empty.
func getenv(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}
