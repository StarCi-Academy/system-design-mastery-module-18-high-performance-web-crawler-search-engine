using System.Globalization;
using Npgsql;
using StackExchange.Redis;

// Polite crawler service (ASP.NET Core minimal API + StackExchange.Redis + Npgsql).
// Same /api/crawler contract as the TypeScript, Java, and Go tracks.

var builder = WebApplication.CreateBuilder(args);
var app = builder.Build();

var redisUrl = Environment.GetEnvironmentVariable("REDIS_URL") ?? "redis://localhost:6379";
var pgUrl = Environment.GetEnvironmentVariable("POSTGRES_URL") ?? "postgres://crawler:crawler@localhost:5432/crawler";
var redis = (await ConnectionMultiplexer.ConnectAsync(RedisConfig(redisUrl))).GetDatabase();
var pgConnString = PgConnString(pgUrl);
const int RobotsTtlSeconds = 60;

await using (var conn = new NpgsqlConnection(pgConnString))
{
    await conn.OpenAsync();
    await using var cmd = new NpgsqlCommand(@"CREATE TABLE IF NOT EXISTS page (
        id BIGSERIAL PRIMARY KEY,
        url VARCHAR UNIQUE NOT NULL,
        host VARCHAR NOT NULL,
        html_body TEXT NOT NULL,
        content_type VARCHAR NOT NULL,
        status_code INT NOT NULL,
        fetched_at TIMESTAMPTZ NOT NULL DEFAULT now())", conn);
    await cmd.ExecuteNonQueryAsync();
}

app.MapPost("/api/crawler/seed", async (SeedRequest req) =>
{
    var ref_ = new Uri(req.Url);
    var host = ref_.Host;
    var rules = await LoadRobots(redis, host, ref_, RobotsTtlSeconds);
    if (!rules.IsAllowed(ref_.AbsolutePath))
    {
        // Disallowed paths never reach the network: 0 bytes stored, allowed=false.
        return Results.Ok(new { url = req.Url, host, statusCode = 0, bytesStored = 0, waitedMs = 0, allowed = false });
    }
    var waited = await ApplyPoliteness(redis, host, rules.CrawlDelayMs);
    var (status, body, contentType) = FakeInternet.Fetch(req.Url, "page");
    // Record lastFetched immediately after the fetch so the clock reflects this request.
    await redis.StringSetAsync($"crawler:host:{host}:lastFetched",
        DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString());
    await using (var conn = new NpgsqlConnection(pgConnString))
    {
        await conn.OpenAsync();
        // UPSERT by url so re-seeding the same URL updates instead of duplicating.
        await using var cmd = new NpgsqlCommand(@"INSERT INTO page (url, host, html_body, content_type, status_code, fetched_at)
            VALUES (@u,@h,@b,@c,@s, now())
            ON CONFLICT (url) DO UPDATE SET html_body=EXCLUDED.html_body, status_code=EXCLUDED.status_code, fetched_at=now()", conn);
        cmd.Parameters.AddWithValue("u", req.Url);
        cmd.Parameters.AddWithValue("h", host);
        cmd.Parameters.AddWithValue("b", body);
        cmd.Parameters.AddWithValue("c", contentType);
        cmd.Parameters.AddWithValue("s", status);
        await cmd.ExecuteNonQueryAsync();
    }
    return Results.Ok(new { url = req.Url, host, statusCode = status, bytesStored = body.Length, waitedMs = waited, allowed = true });
});

app.MapGet("/api/crawler/pages", async () =>
{
    var list = new List<object>();
    await using var conn = new NpgsqlConnection(pgConnString);
    await conn.OpenAsync();
    await using var cmd = new NpgsqlCommand("SELECT url, host, status_code FROM page ORDER BY fetched_at DESC", conn);
    await using var reader = await cmd.ExecuteReaderAsync();
    while (await reader.ReadAsync())
        list.Add(new { url = reader.GetString(0), host = reader.GetString(1), statusCode = reader.GetInt32(2) });
    return Results.Ok(list);
});

app.MapGet("/api/crawler/politeness/{host}", async (string host) =>
{
    var delay = await redis.StringGetAsync($"crawler:robots:{host}:delay");
    var last = await redis.StringGetAsync($"crawler:host:{host}:lastFetched");
    if (delay.IsNull || last.IsNull)
        return Results.NotFound(new { statusCode = 404, message = $"No politeness state for host {host}" });
    var delayMs = int.Parse(delay!);
    var lastMs = long.Parse(last!);
    return Results.Ok(new
    {
        host,
        crawlDelayMs = delayMs,
        lastFetchedAt = Iso(lastMs),
        nextAllowedAt = Iso(lastMs + delayMs),
    });
});

var port = Environment.GetEnvironmentVariable("PORT") ?? "3000";
Console.WriteLine($"crawler-service listening on {port}");
app.Run($"http://0.0.0.0:{port}");

// loadRobots fetches a host robots.txt at most once per TTL window and caches body + delay.
static async Task<RobotsRules> LoadRobots(IDatabase redis, string host, Uri ref_, int ttl)
{
    var cacheKey = $"crawler:robots:{host}:body";
    var cached = await redis.StringGetAsync(cacheKey);
    string body;
    if (!cached.IsNull)
    {
        // An empty cached body is a valid allow-all answer; only IsNull is a cache miss.
        body = cached!;
    }
    else
    {
        var (status, fetched, _) = FakeInternet.Fetch($"{ref_.Scheme}://{host}/robots.txt", "robots");
        // Treat any non-200 response as "no rules" — empty body = allow all.
        body = status == 200 ? fetched : "";
        await redis.StringSetAsync(cacheKey, body, TimeSpan.FromSeconds(ttl));
    }
    var rules = RobotsRules.Parse(body);
    // Cache the parsed delay under the same TTL so body + delay refresh together.
    await redis.StringSetAsync($"crawler:robots:{host}:delay",
        rules.CrawlDelayMs.ToString(), TimeSpan.FromSeconds(ttl));
    return rules;
}

// applyPoliteness derives the wait from the SHARED Redis clock, not local memory.
static async Task<int> ApplyPoliteness(IDatabase redis, string host, int delayMs)
{
    if (delayMs <= 0) return 0;
    var last = await redis.StringGetAsync($"crawler:host:{host}:lastFetched");
    if (last.IsNull) return 0;
    var elapsed = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - long.Parse(last!);
    // wait = delay - (now - lastFetched); never sleep a negative amount.
    var wait = (int)Math.Max(0, delayMs - elapsed);
    if (wait > 0) await Task.Delay(wait);
    return wait;
}

static string Iso(long ms) => DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime.ToString("yyyy-MM-ddTHH:mm:ss.fffZ", CultureInfo.InvariantCulture);

static ConfigurationOptions RedisConfig(string url)
{
    var u = new Uri(url);
    return new ConfigurationOptions { EndPoints = { { u.Host, u.Port == -1 ? 6379 : u.Port } } };
}

static string PgConnString(string url)
{
    var u = new Uri(url);
    var userInfo = u.UserInfo.Split(':');
    return $"Host={u.Host};Port={(u.Port == -1 ? 5432 : u.Port)};Username={userInfo[0]};Password={userInfo[1]};Database={u.AbsolutePath.TrimStart('/')}";
}

record SeedRequest(string Url);

static class FakeInternet
{
    public static (int, string, string) Fetch(string rawUrl, string kind)
    {
        var u = new Uri(rawUrl);
        if (u.Host != "books.starci.test") return (404, "", "text/plain");
        if (kind == "robots") return (200, "User-agent: *\nAllow: /\nDisallow: /admin\nCrawl-delay: 1", "text/plain");
        var pages = new Dictionary<string, string>
        {
            ["/"] = "<html><head><title>Books</title></head><body>Welcome to the books index page.</body></html>",
            ["/book/1"] = "<html><head><title>Book 1</title></head><body>The first book in the catalog.</body></html>",
            ["/book/2"] = "<html><head><title>Book 2</title></head><body>The second book in the catalog.</body></html>",
        };
        return pages.TryGetValue(u.AbsolutePath, out var body) ? (200, body, "text/html") : (404, "", "text/html");
    }
}

class RobotsRules
{
    public int CrawlDelayMs { get; private set; }
    private readonly List<(bool allow, string pattern)> _rules = new();

    public static RobotsRules Parse(string body)
    {
        var r = new RobotsRules();
        foreach (var raw in body.Split('\n'))
        {
            var line = raw.Split('#')[0].Trim();
            var idx = line.IndexOf(':');
            if (idx == -1) continue;
            var key = line[..idx].Trim().ToLowerInvariant();
            var val = line[(idx + 1)..].Trim();
            if (key == "disallow" && val.Length > 0) r._rules.Add((false, val));
            else if (key == "allow" && val.Length > 0) r._rules.Add((true, val));
            else if (key == "crawl-delay" && double.TryParse(val, NumberStyles.Any, CultureInfo.InvariantCulture, out var sec))
                r.CrawlDelayMs = (int)(sec * 1000);
        }
        return r;
    }

    // RFC 9309 longest-match: the longest matching prefix wins; a tie resolves to Allow.
    public bool IsAllowed(string path)
    {
        (bool allow, string pattern)? best = null;
        foreach (var rule in _rules)
        {
            if (path.StartsWith(rule.pattern))
            {
                if (best == null || rule.pattern.Length > best.Value.pattern.Length) best = rule;
                else if (rule.pattern.Length == best.Value.pattern.Length && rule.allow) best = rule;
            }
        }
        return best?.allow ?? true;
    }
}
