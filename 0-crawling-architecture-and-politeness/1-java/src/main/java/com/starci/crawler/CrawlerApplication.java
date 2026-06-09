package com.starci.crawler;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Spring Boot entry point — starts the embedded Tomcat and registers all Spring beans. */
@SpringBootApplication
public class CrawlerApplication {
    /** Launch the application; Spring wires the controller, JPA, and Redis automatically. */
    public static void main(String[] args) {
        SpringApplication.run(CrawlerApplication.class, args);
    }
}

/**
 * A hermetic in-process "fake internet" so the lesson runs without real network access.
 * Each host publishes its own robots.txt and a small set of pages, keeping politeness
 * and robots mechanics observable and deterministic.
 */
class FakeInternet {
    /**
     * Returns a canned response for a robots.txt URL or a page URL.
     *
     * @param rawUrl absolute URL to fetch
     * @param kind   "robots" for robots.txt, "page" for an HTML page
     * @return Object[]{statusCode (int), body (String), contentType (String)}
     */
    static Object[] fetch(String rawUrl, String kind) {
        URI u = URI.create(rawUrl);
        // Only books.starci.test is registered in the fake internet fixture.
        if (!"books.starci.test".equals(u.getHost())) return new Object[]{404, "", "text/plain"};
        if ("robots".equals(kind)) {
            // Declare Crawl-delay: 1 so Flow 3 demonstrates the shared-clock wait.
            return new Object[]{200, "User-agent: *\nAllow: /\nDisallow: /admin\nCrawl-delay: 1", "text/plain"};
        }
        Map<String, String> pages = Map.of(
            "/", "<html><head><title>Books</title></head><body>Welcome to the books index page.</body></html>",
            "/book/1", "<html><head><title>Book 1</title></head><body>The first book in the catalog.</body></html>",
            "/book/2", "<html><head><title>Book 2</title></head><body>The second book in the catalog.</body></html>"
        );
        String body = pages.get(u.getPath());
        return body != null ? new Object[]{200, body, "text/html"} : new Object[]{404, "", "text/html"};
    }
}

/**
 * Minimal RFC 9309 robots.txt parser.
 * Collects Allow/Disallow directives and the Crawl-delay for the wildcard user-agent,
 * then decides allow/deny using the longest-match rule.
 */
class RobotsRules {
    /** Crawl-delay in milliseconds, parsed from the "Crawl-delay: N" directive. */
    int crawlDelayMs = 0;

    // Internal storage: parallel lists of patterns and their allow/deny values.
    final List<String> patterns = new ArrayList<>();
    final List<Boolean> allows = new ArrayList<>();

    /**
     * Parse a robots.txt body into a RobotsRules instance.
     *
     * @param body raw robots.txt text (may be empty — empty = allow all)
     * @return parsed rules object
     */
    static RobotsRules parse(String body) {
        RobotsRules r = new RobotsRules();
        for (String raw : body.split("\n")) {
            // Strip inline comments before parsing key:value pairs.
            String line = raw.split("#", 2)[0].trim();
            int idx = line.indexOf(':');
            if (idx == -1) continue;
            String key = line.substring(0, idx).trim().toLowerCase();
            String val = line.substring(idx + 1).trim();
            if (key.equals("disallow") && !val.isEmpty()) { r.patterns.add(val); r.allows.add(false); }
            else if (key.equals("allow") && !val.isEmpty()) { r.patterns.add(val); r.allows.add(true); }
            else if (key.equals("crawl-delay")) {
                // Parse as double to handle fractional seconds (e.g. "0.5").
                try { r.crawlDelayMs = (int) (Double.parseDouble(val) * 1000); } catch (NumberFormatException ignored) {}
            }
        }
        return r;
    }

    /**
     * Decide whether the given path is allowed by this robots.txt.
     * RFC 9309: the longest matching prefix wins; a tie resolves to Allow.
     *
     * @param path URL path component (e.g. "/admin")
     * @return true if the path is allowed to be crawled
     */
    boolean isAllowed(String path) {
        int bestLen = -1;
        Boolean best = null;
        for (int i = 0; i < patterns.size(); i++) {
            String p = patterns.get(i);
            if (path.startsWith(p)) {
                // Longer prefix = more specific rule; ties resolve to Allow.
                if (p.length() > bestLen || (p.length() == bestLen && allows.get(i))) {
                    bestLen = p.length();
                    best = allows.get(i);
                }
            }
        }
        // No matching rule means unrestricted — allow by default.
        return best == null || best;
    }
}

/**
 * JPA entity representing a successfully crawled page stored in Postgres.
 * Schema authority: spring.jpa.hibernate.ddl-auto=update in application.properties.
 */
@Entity
@Table(name = "page")
class Page {
    /** Auto-generated surrogate primary key. */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;

    /** Absolute URL; unique constraint prevents duplicate rows on re-seed. */
    @Column(unique = true, nullable = false) String url;

    /** Hostname of the crawled page, used as the politeness key in Redis. */
    @Column(nullable = false) String host;

    /** Raw HTML body of the crawled page. */
    @Column(name = "html_body", columnDefinition = "text", nullable = false) String htmlBody;

    /** MIME content type returned by the fake internet fixture (e.g. text/html). */
    @Column(name = "content_type", nullable = false) String contentType;

    /** HTTP status code returned by the fake internet fixture. */
    @Column(name = "status_code", nullable = false) int statusCode;

    /** Timestamp set at insert/update time; used for ORDER BY fetchedAt DESC. */
    @Column(name = "fetched_at", nullable = false) Instant fetchedAt = Instant.now();
}

/**
 * Spring Data JPA repository for Page entities.
 * findByUrl and findAllByOrderByFetchedAtDesc are derived query methods.
 */
interface PageRepository extends JpaRepository<Page, Long> {
    /** Find a page by exact URL match (used for UPSERT logic). */
    Optional<Page> findByUrl(String url);

    /** Return all pages ordered newest-first (used by GET /api/crawler/pages). */
    List<Page> findAllByOrderByFetchedAtDesc();
}

/**
 * REST controller exposing the three crawler endpoints:
 *   POST /api/crawler/seed           — validate robots, wait politeness, fetch, persist
 *   GET  /api/crawler/pages          — list all stored pages newest-first
 *   GET  /api/crawler/politeness/:host — inspect Redis politeness state for a host
 *
 * Keeping politeness state in Redis (not in process memory) means every JVM instance
 * reads the same lastFetched timestamp — the core insight of the lesson.
 */
@RestController
@RequestMapping("/api/crawler")
class CrawlerController {
    /** How long robots.txt bodies and parsed crawl-delay values are cached in Redis. */
    private static final int ROBOTS_TTL_SECONDS = 60;

    /** ISO 8601 formatter for the politeness snapshot response timestamps. */
    private static final DateTimeFormatter ISO =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final StringRedisTemplate redis;
    private final PageRepository repo;

    /** Constructor injection — Spring wires both beans automatically. */
    CrawlerController(StringRedisTemplate redis, PageRepository repo) {
        this.redis = redis;
        this.repo = repo;
    }

    /**
     * Fetch a host's robots.txt at most once per TTL window and cache body + delay.
     * Testing for null (not empty string) is deliberate: an empty robots body is a
     * valid "allow everything" answer and must not trigger a refetch.
     *
     * @param host hostname extracted from the seed URL
     * @param ref  parsed seed URI used to construct the robots.txt URL
     * @return parsed RobotsRules for the host
     */
    private RobotsRules loadRobots(String host, URI ref) {
        String cacheKey = "crawler:robots:" + host + ":body";
        String cached = redis.opsForValue().get(cacheKey);
        String body;
        if (cached != null) {
            // An empty cached body is a valid allow-all answer; only null is a cache miss.
            body = cached;
        } else {
            Object[] fetched = FakeInternet.fetch(ref.getScheme() + "://" + host + "/robots.txt", "robots");
            // Treat any non-200 response as "no rules" — empty body = allow all.
            body = (int) fetched[0] == 200 ? (String) fetched[1] : "";
            redis.opsForValue().set(cacheKey, body, Duration.ofSeconds(ROBOTS_TTL_SECONDS));
        }
        RobotsRules rules = RobotsRules.parse(body);
        // Cache the parsed delay under the same TTL so body + delay refresh together.
        redis.opsForValue().set("crawler:robots:" + host + ":delay",
                String.valueOf(rules.crawlDelayMs), Duration.ofSeconds(ROBOTS_TTL_SECONDS));
        return rules;
    }

    /**
     * Derive the politeness wait from the SHARED Redis clock, not local memory.
     * Formula: wait = crawlDelay - (now - lastFetched); never sleep a negative amount.
     * A shared clock ensures every JVM instance spaces itself out correctly.
     *
     * @param host    hostname to check politeness for
     * @param delayMs crawl-delay in milliseconds from the host's robots.txt
     * @return actual milliseconds waited (0 if no prior fetch or delay expired)
     */
    private int applyPoliteness(String host, int delayMs) {
        if (delayMs <= 0) return 0;
        String last = redis.opsForValue().get("crawler:host:" + host + ":lastFetched");
        if (last == null) return 0;
        long elapsed = System.currentTimeMillis() - Long.parseLong(last);
        // wait = delay - (now - lastFetched); never sleep a negative amount.
        int wait = (int) Math.max(0, delayMs - elapsed);
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
        }
        return wait;
    }

    /**
     * POST /api/crawler/seed
     * Ties robots loading, allow check, politeness wait, fetch, and persistence into
     * one observable request. A Disallowed path never touches the network.
     *
     * @param body JSON request body containing the "url" field
     * @return seed result map with url, host, statusCode, bytesStored, waitedMs, allowed
     */
    @PostMapping("/seed")
    Map<String, Object> seed(@RequestBody Map<String, String> body) {
        URI ref = URI.create(body.get("url"));
        String host = ref.getHost();
        RobotsRules rules = loadRobots(host, ref);
        if (!rules.isAllowed(ref.getPath())) {
            // Disallowed paths never reach the network: 0 bytes stored, allowed=false.
            return Map.of("url", body.get("url"), "host", host, "statusCode", 0,
                "bytesStored", 0, "waitedMs", 0, "allowed", false);
        }
        int waited = applyPoliteness(host, rules.crawlDelayMs);
        Object[] fetched = FakeInternet.fetch(body.get("url"), "page");
        // Record lastFetched immediately after the fetch so the clock reflects this request.
        redis.opsForValue().set("crawler:host:" + host + ":lastFetched",
                String.valueOf(System.currentTimeMillis()));
        String html = (String) fetched[1];
        // UPSERT: find existing row by url to avoid duplicates on re-seed.
        Page page = repo.findByUrl(body.get("url")).orElseGet(Page::new);
        page.url = body.get("url"); page.host = host; page.htmlBody = html;
        page.contentType = (String) fetched[2]; page.statusCode = (int) fetched[0];
        page.fetchedAt = Instant.now();
        repo.save(page);
        return Map.of("url", body.get("url"), "host", host, "statusCode", fetched[0],
            "bytesStored", html.length(), "waitedMs", waited, "allowed", true);
    }

    /**
     * GET /api/crawler/pages
     * Returns all stored pages ordered by fetchedAt descending.
     * Only allowed pages are stored, so Disallowed paths never appear here.
     *
     * @return list of maps each containing url, host, statusCode
     */
    @GetMapping("/pages")
    List<Map<String, Object>> pages() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Page p : repo.findAllByOrderByFetchedAtDesc()) {
            // Expose only the three contract fields; keep htmlBody server-side.
            out.add(Map.of("url", p.url, "host", p.host, "statusCode", p.statusCode));
        }
        return out;
    }

    /**
     * GET /api/crawler/politeness/{host}
     * Returns the current politeness snapshot for a host from Redis.
     * Returns a 404-like map if no state exists (host not yet crawled).
     *
     * @param host hostname URL path variable (e.g. "books.starci.test")
     * @return map with host, crawlDelayMs, lastFetchedAt, nextAllowedAt; or error map
     */
    @GetMapping("/politeness/{host}")
    Object politeness(@PathVariable String host) {
        String delay = redis.opsForValue().get("crawler:robots:" + host + ":delay");
        String last = redis.opsForValue().get("crawler:host:" + host + ":lastFetched");
        if (delay == null || last == null) {
            // Return a 404-style map; Spring returns HTTP 200 for Map responses by default.
            return Map.of("statusCode", 404, "message", "No politeness state for host " + host);
        }
        int delayMs = Integer.parseInt(delay);
        long lastMs = Long.parseLong(last);
        // ISO 8601 strings let the client parse independently of timezone.
        return Map.of(
            "host", host,
            "crawlDelayMs", delayMs,
            "lastFetchedAt", ISO.format(Instant.ofEpochMilli(lastMs)),
            "nextAllowedAt", ISO.format(Instant.ofEpochMilli(lastMs + delayMs))
        );
    }
}
