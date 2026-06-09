package com.starci.frontier;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * URL frontier service: a Redis ZSET priority queue fronted by a RedisBloom dedup filter,
 * with an append-only Postgres audit trail.
 */
@Service
public class FrontierService {

    private static final String QUEUE_KEY = "frontier:queue";
    private static final String BLOOM_KEY = "seen:urls";
    // Sidecar counter: tracks distinct URLs inserted into the bloom (incremented alongside each BF.ADD).
    // This avoids BF.INFO, whose mixed string+integer array reply cannot be decoded by ByteArrayOutput.
    private static final String BLOOM_ITEMS_KEY = "bloom:items";

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final int capacity;
    private final double errorRate;

    public FrontierService(
            StringRedisTemplate redis,
            JdbcTemplate jdbc,
            @Value("${bloom.capacity:100000}") int capacity,
            @Value("${bloom.errorRate:0.01}") double errorRate) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.capacity = capacity;
        this.errorRate = errorRate;
    }

    @PostConstruct
    void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS frontier_event ("
                + "id uuid PRIMARY KEY, action text NOT NULL, url text NOT NULL,"
                + " priority int NOT NULL, created_at timestamptz NOT NULL DEFAULT now())");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_frontier_event_action ON frontier_event (action)");
        ensureBloom();
    }

    // BF.RESERVE creates the bloom with the chosen error rate and capacity; RedisBloom does not
    // auto-create a filter with sensible defaults. Spring Data Redis has no typed BF.* commands,
    // so we dispatch the raw command through the Lettuce connection.
    private void ensureBloom() {
        try {
            bloomCommand("BF.RESERVE", BLOOM_KEY,
                    Double.toString(errorRate), Integer.toString(capacity));
        } catch (RuntimeException ex) {
            // "ERR item exists" means a previous boot already created the filter — leave it untouched.
            // Spring wraps the Lettuce RedisCommandExecutionException in a RedisSystemException,
            // so we walk the cause chain to find the message from the Redis server.
            if (!isItemExistsError(ex)) {
                throw ex;
            }
        }
    }

    /** Returns true if any cause in the exception chain carries "ERR item exists". */
    private static boolean isItemExistsError(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("ERR item exists")) return true;
            t = t.getCause();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Object bloomCommand(String command, String... args) {
        return redis.execute((RedisConnection conn) -> {
            byte[][] raw = new byte[args.length][];
            for (int i = 0; i < args.length; i++) {
                raw[i] = args[i].getBytes(StandardCharsets.UTF_8);
            }
            return conn.execute(command, raw);
        });
    }

    private boolean bloomExists(String url) {
        Object res = bloomCommand("BF.EXISTS", BLOOM_KEY, url);
        return res instanceof Long && (Long) res == 1L;
    }

    private void audit(String action, String url, int priority) {
        jdbc.update(
                "INSERT INTO frontier_event (id, action, url, priority, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), action, url, priority, java.sql.Timestamp.from(Instant.now()));
    }

    // BF.EXISTS before BF.ADD + ZADD is the canonical pattern: the bloom is a read-mostly pre-filter.
    public Map<String, Object> enqueue(String url, int priority) {
        if (bloomExists(url)) {
            // A duplicate is short-circuited before it can reach the queue; still audit it.
            audit("enqueue-dup", url, priority);
            return result(url, priority, true);
        }
        bloomCommand("BF.ADD", BLOOM_KEY, url);
        // Increment the sidecar counter alongside BF.ADD so stats() can report bloomItems correctly.
        redis.opsForValue().increment(BLOOM_ITEMS_KEY);
        redis.opsForZSet().add(QUEUE_KEY, url, priority);
        audit("enqueue", url, priority);
        return result(url, priority, false);
    }

    private Map<String, Object> result(String url, int priority, boolean duplicate) {
        Map<String, Object> out = new HashMap<>();
        out.put("url", url);
        out.put("priority", priority);
        out.put("duplicate", duplicate);
        out.put("queueSize", queueSize());
        return out;
    }

    // popMax maps to ZPOPMAX — one round trip and one server-side op, so N workers each get a distinct URL.
    public Map<String, Object> dequeue() {
        ZSetOperations.TypedTuple<String> popped = redis.opsForZSet().popMax(QUEUE_KEY);
        if (popped == null || popped.getValue() == null) {
            return null;
        }
        String url = popped.getValue();
        int priority = popped.getScore() != null ? popped.getScore().intValue() : 0;
        audit("dequeue", url, priority);
        Map<String, Object> out = new HashMap<>();
        out.put("url", url);
        out.put("priority", priority);
        out.put("queueSize", queueSize());
        return out;
    }

    public Map<String, Object> seen(String url) {
        Map<String, Object> out = new HashMap<>();
        out.put("url", url);
        out.put("seen", bloomExists(url));
        return out;
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new HashMap<>();
        out.put("queueSize", queueSize());
        out.put("bloomCapacity", capacity);
        // bloomItems read from the sidecar counter (incremented alongside every BF.ADD in enqueue).
        out.put("bloomItems", bloomItemCount());
        out.put("bloomErrorRate", errorRate);
        return out;
    }

    /** Returns distinct URL count from the sidecar INCR counter alongside the bloom filter. */
    private int bloomItemCount() {
        String val = redis.opsForValue().get(BLOOM_ITEMS_KEY);
        if (val == null) return 0;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return 0; }
    }

    private long queueSize() {
        Long size = redis.opsForZSet().zCard(QUEUE_KEY);
        return size != null ? size : 0L;
    }
}
