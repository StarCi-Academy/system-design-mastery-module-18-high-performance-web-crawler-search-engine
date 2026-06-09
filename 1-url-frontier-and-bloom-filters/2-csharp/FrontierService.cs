using Npgsql;
using StackExchange.Redis;

namespace FrontierService;

/// <summary>
/// URL frontier: a Redis ZSET priority queue fronted by a RedisBloom dedup filter,
/// with an append-only Postgres audit trail.
/// </summary>
public sealed class FrontierService
{
    private const string QueueKey = "frontier:queue";
    private const string BloomKey = "seen:urls";

    private readonly IDatabase _redis;
    private readonly string _postgresUrl;
    private readonly int _capacity;
    private readonly double _errorRate;

    public FrontierService(IConnectionMultiplexer mux, IConfiguration config)
    {
        _redis = mux.GetDatabase();
        _postgresUrl = config["POSTGRES_URL"]
                       ?? "Host=localhost;Username=frontier;Password=frontier;Database=frontier";
        _capacity = int.Parse(config["BLOOM_CAPACITY"] ?? "100000");
        _errorRate = double.Parse(config["BLOOM_ERROR_RATE"] ?? "0.01");
    }

    public async Task InitAsync()
    {
        await using var conn = new NpgsqlConnection(_postgresUrl);
        await conn.OpenAsync();
        await using (var cmd = new NpgsqlCommand(
            "CREATE TABLE IF NOT EXISTS frontier_event (" +
            "id uuid PRIMARY KEY, action text NOT NULL, url text NOT NULL, " +
            "priority int NOT NULL, created_at timestamptz NOT NULL DEFAULT now());" +
            "CREATE INDEX IF NOT EXISTS idx_frontier_event_action ON frontier_event (action);", conn))
        {
            await cmd.ExecuteNonQueryAsync();
        }
        await EnsureBloomAsync();
    }

    // BF.RESERVE creates the bloom with the chosen error rate and capacity; RedisBloom does not
    // auto-create a filter with sensible defaults. StackExchange.Redis dispatches raw module
    // commands through ExecuteAsync, so BF.* works without a typed wrapper.
    private async Task EnsureBloomAsync()
    {
        try
        {
            await _redis.ExecuteAsync("BF.RESERVE", BloomKey, _errorRate, _capacity);
        }
        catch (RedisServerException ex) when (ex.Message.Contains("ERR item exists"))
        {
            // Filter already exists from a previous boot; leave it untouched.
        }
    }

    private async Task AuditAsync(string action, string url, int priority)
    {
        await using var conn = new NpgsqlConnection(_postgresUrl);
        await conn.OpenAsync();
        await using var cmd = new NpgsqlCommand(
            "INSERT INTO frontier_event (id, action, url, priority, created_at) " +
            "VALUES (gen_random_uuid(), @a, @u, @p, now())", conn);
        cmd.Parameters.AddWithValue("a", action);
        cmd.Parameters.AddWithValue("u", url);
        cmd.Parameters.AddWithValue("p", priority);
        await cmd.ExecuteNonQueryAsync();
    }

    // BF.EXISTS before BF.ADD + ZADD is the canonical pattern: the bloom is a read-mostly pre-filter.
    public async Task<object> EnqueueAsync(string url, int priority)
    {
        var exists = (long)await _redis.ExecuteAsync("BF.EXISTS", BloomKey, url);
        if (exists == 1)
        {
            // A duplicate is short-circuited before it can reach the queue; still audit it.
            await AuditAsync("enqueue-dup", url, priority);
            return new { url, priority, duplicate = true, queueSize = await QueueSizeAsync() };
        }
        await _redis.ExecuteAsync("BF.ADD", BloomKey, url);
        await _redis.SortedSetAddAsync(QueueKey, url, priority);
        await AuditAsync("enqueue", url, priority);
        return new { url, priority, duplicate = false, queueSize = await QueueSizeAsync() };
    }

    // SortedSetPopAsync(Order.Descending) maps to ZPOPMAX — one round trip and one server-side op.
    public async Task<object?> DequeueAsync()
    {
        var entry = await _redis.SortedSetPopAsync(QueueKey, Order.Descending);
        if (entry is null) return null;
        var url = entry.Value.Element.ToString();
        var priority = (int)entry.Value.Score;
        await AuditAsync("dequeue", url, priority);
        return new { url, priority, queueSize = await QueueSizeAsync() };
    }

    public async Task<object> SeenAsync(string url)
    {
        var exists = (long)await _redis.ExecuteAsync("BF.EXISTS", BloomKey, url);
        return new { url, seen = exists == 1 };
    }

    public async Task<object> StatsAsync()
    {
        var info = (RedisResult[])(await _redis.ExecuteAsync("BF.INFO", BloomKey))!;
        var capacity = _capacity;
        var items = 0;
        for (var i = 0; i + 1 < info.Length; i += 2)
        {
            var key = info[i].ToString();
            if (key == "Capacity") capacity = (int)info[i + 1];
            else if (key == "Number of items inserted") items = (int)info[i + 1];
        }
        return new
        {
            queueSize = await QueueSizeAsync(),
            bloomCapacity = capacity,
            bloomItems = items,
            bloomErrorRate = _errorRate
        };
    }

    private async Task<long> QueueSizeAsync() => await _redis.SortedSetLengthAsync(QueueKey);
}
