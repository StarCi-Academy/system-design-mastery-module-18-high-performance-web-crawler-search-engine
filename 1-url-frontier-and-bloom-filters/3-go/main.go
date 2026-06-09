package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
)

// decodeURL percent-decodes a path segment so %3A%2F%2F becomes :// before Redis lookups.
func decodeURL(s string) (string, error) {
	return url.QueryUnescape(s)
}

const (
	queueKey = "frontier:queue"
	bloomKey = "seen:urls"
)

// FrontierService owns the Redis client (ZSET queue + RedisBloom filter) and the
// Postgres pool used for the append-only audit trail.
type FrontierService struct {
	rdb       *redis.Client
	pool      *pgxpool.Pool
	capacity  int
	errorRate float64
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

// BF.RESERVE creates the bloom with the chosen error rate and capacity; RedisBloom does not
// auto-create a filter with sensible defaults. go-redis dispatches raw module commands with
// rdb.Do, so BF.* works without a typed wrapper.
func (s *FrontierService) ensureBloom(ctx context.Context) error {
	err := s.rdb.Do(ctx, "BF.RESERVE", bloomKey, s.errorRate, s.capacity).Err()
	// "ERR item exists" means a previous boot already created the filter — leave it untouched.
	if err != nil && err.Error() != "ERR item exists" {
		return err
	}
	return nil
}

func (s *FrontierService) audit(ctx context.Context, action, url string, priority int) {
	_, _ = s.pool.Exec(ctx,
		`INSERT INTO frontier_event (id, action, url, priority, created_at)
		 VALUES (gen_random_uuid(), $1, $2, $3, $4)`,
		action, url, priority, time.Now())
}

// bfExists wraps BF.EXISTS to handle both integer(1/0) and bool RESP3 responses from RedisBloom.
func bfExists(ctx context.Context, rdb *redis.Client, key, member string) (bool, error) {
	res := rdb.Do(ctx, "BF.EXISTS", key, member)
	if err := res.Err(); err != nil {
		return false, err
	}
	// go-redis v9 with RESP3 may return bool; fall back to Int64 for RESP2.
	if b, err := res.Bool(); err == nil {
		return b, nil
	}
	n, err := res.Int64()
	return n == 1, err
}

// BF.EXISTS before BF.ADD + ZADD is the canonical pattern: the bloom is a read-mostly pre-filter.
func (s *FrontierService) enqueue(ctx context.Context, url string, priority int) (gin.H, error) {
	exists, err := bfExists(ctx, s.rdb, bloomKey, url)
	if err != nil {
		return nil, err
	}
	if exists {
		// A duplicate is short-circuited before it can reach the queue; still audit it.
		s.audit(ctx, "enqueue-dup", url, priority)
		size, _ := s.rdb.ZCard(ctx, queueKey).Result()
		return gin.H{"url": url, "priority": priority, "duplicate": true, "queueSize": size}, nil
	}
	if err := s.rdb.Do(ctx, "BF.ADD", bloomKey, url).Err(); err != nil {
		return nil, err
	}
	if err := s.rdb.ZAdd(ctx, queueKey, redis.Z{Score: float64(priority), Member: url}).Err(); err != nil {
		return nil, err
	}
	s.audit(ctx, "enqueue", url, priority)
	size, _ := s.rdb.ZCard(ctx, queueKey).Result()
	return gin.H{"url": url, "priority": priority, "duplicate": false, "queueSize": size}, nil
}

// ZPopMax maps to ZPOPMAX — one round trip and one server-side op, so N workers each get a distinct URL.
func (s *FrontierService) dequeue(ctx context.Context) (gin.H, error) {
	res, err := s.rdb.ZPopMax(ctx, queueKey, 1).Result()
	if err != nil {
		return nil, err
	}
	if len(res) == 0 {
		return nil, nil
	}
	url := res[0].Member.(string)
	priority := int(res[0].Score)
	s.audit(ctx, "dequeue", url, priority)
	size, _ := s.rdb.ZCard(ctx, queueKey).Result()
	return gin.H{"url": url, "priority": priority, "queueSize": size}, nil
}

func (s *FrontierService) stats(ctx context.Context) (gin.H, error) {
	size, _ := s.rdb.ZCard(ctx, queueKey).Result()
	cap := s.capacity
	items := 0

	// BF.INFO returns a flat key-value list in RESP2 (Slice) or a map in RESP3.
	// Handle both by trying Slice first, then map fallback.
	raw := s.rdb.Do(ctx, "BF.INFO", bloomKey)
	if err := raw.Err(); err != nil {
		return nil, err
	}
	if info, err := raw.Slice(); err == nil {
		for i := 0; i+1 < len(info); i += 2 {
			key, _ := info[i].(string)
			switch key {
			case "Capacity":
				cap = toInt(info[i+1])
			case "Number of items inserted":
				items = toInt(info[i+1])
			}
		}
	} else {
		// RESP3 map: the result is map[interface{}]interface{} under .Val()
		if m, ok := raw.Val().(map[interface{}]interface{}); ok {
			for k, v := range m {
				switch fmt.Sprintf("%v", k) {
				case "Capacity":
					cap = toInt(v)
				case "Number of items inserted":
					items = toInt(v)
				}
			}
		}
	}
	return gin.H{
		"queueSize":      size,
		"bloomCapacity":  cap,
		"bloomItems":     items,
		"bloomErrorRate": s.errorRate,
	}, nil
}

func toInt(v interface{}) int {
	switch n := v.(type) {
	case int64:
		return int(n)
	case int:
		return n
	case string:
		i, _ := strconv.Atoi(n)
		return i
	}
	return 0
}

func main() {
	ctx := context.Background()
	rdb := redis.NewClient(&redis.Options{Addr: env("REDIS_ADDR", "localhost:6379")})
	pool, err := pgxpool.New(ctx, env("POSTGRES_URL", "postgres://frontier:frontier@localhost:5432/frontier"))
	if err != nil {
		log.Fatalf("postgres connect: %v", err)
	}
	if _, err := pool.Exec(ctx,
		`CREATE TABLE IF NOT EXISTS frontier_event (
			id uuid PRIMARY KEY,
			action text NOT NULL,
			url text NOT NULL,
			priority int NOT NULL,
			created_at timestamptz NOT NULL DEFAULT now()
		);
		CREATE INDEX IF NOT EXISTS idx_frontier_event_action ON frontier_event (action);`); err != nil {
		log.Fatalf("schema: %v", err)
	}

	cap, _ := strconv.Atoi(env("BLOOM_CAPACITY", "100000"))
	rate, _ := strconv.ParseFloat(env("BLOOM_ERROR_RATE", "0.01"), 64)
	svc := &FrontierService{rdb: rdb, pool: pool, capacity: cap, errorRate: rate}
	if err := svc.ensureBloom(ctx); err != nil {
		log.Fatalf("ensureBloom: %v", err)
	}

	r := gin.Default()
	// UseRawPath + UnescapePathValues lets httprouter pass %2F as part of a path param
	// without splitting the URL on the decoded slash.
	r.UseRawPath = true
	r.UnescapePathValues = false
	r.POST("/api/frontier/enqueue", func(c *gin.Context) {
		var dto struct {
			URL      string `json:"url"`
			Priority int    `json:"priority"`
		}
		if err := c.ShouldBindJSON(&dto); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"message": err.Error()})
			return
		}
		out, err := svc.enqueue(c, dto.URL, dto.Priority)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"message": err.Error()})
			return
		}
		c.JSON(http.StatusCreated, out)
	})
	r.POST("/api/frontier/dequeue", func(c *gin.Context) {
		out, err := svc.dequeue(c)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"message": err.Error()})
			return
		}
		if out == nil {
			c.JSON(http.StatusOK, nil)
			return
		}
		c.JSON(http.StatusOK, out)
	})
	r.GET("/api/frontier/seen/:url", func(c *gin.Context) {
		url := c.Param("url")
		// URL-decode the path variable so %3A%2F%2F becomes :// before the bloom lookup.
		decoded, err := decodeURL(url)
		if err != nil {
			decoded = url
		}
		exists, _ := bfExists(c, svc.rdb, bloomKey, decoded)
		c.JSON(http.StatusOK, gin.H{"url": decoded, "seen": exists})
	})
	r.GET("/api/frontier/stats", func(c *gin.Context) {
		out, err := svc.stats(c)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"message": err.Error()})
			return
		}
		c.JSON(http.StatusOK, out)
	})

	port := env("PORT", "3000")
	log.Printf("frontier-service listening on %s", port)
	if err := r.Run(":" + port); err != nil {
		log.Fatal(err)
	}
}
