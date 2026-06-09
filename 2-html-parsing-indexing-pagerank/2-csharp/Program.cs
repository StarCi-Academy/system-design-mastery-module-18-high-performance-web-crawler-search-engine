using AngleSharp;
using AngleSharp.Dom;
using Indexer;
using Microsoft.EntityFrameworkCore;

var builder = WebApplication.CreateBuilder(args);

var connString = Environment.GetEnvironmentVariable("ConnectionStrings__Postgres")
                 ?? "Host=localhost;Port=5432;Database=indexer;Username=indexer;Password=indexer";
builder.Services.AddDbContext<IndexerDb>(o => o.UseNpgsql(connString));

var port = Environment.GetEnvironmentVariable("PORT") ?? "3000";
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");

var app = builder.Build();

const string Base = "https://search.starci.test/";
string[][] seed =
[
    ["a", "b"], ["a", "c"], ["b", "c"], ["c", "a"],
    ["d", "c"], ["d", "b"], ["e", "d"]
];

// Migrate + seed the demo graph on boot when page_link is empty.
using (var scope = app.Services.CreateScope())
{
    var db = scope.ServiceProvider.GetRequiredService<IndexerDb>();
    await db.Database.EnsureCreatedAsync();
    if (!await db.PageLinks.AnyAsync())
    {
        foreach (var e in seed)
        {
            db.PageLinks.Add(new PageLink { FromUrl = Base + e[0], ToUrl = Base + e[1] });
        }
        await db.SaveChangesAsync();
    }
}

app.MapPost("/api/indexer/parse", async (ParseDto dto, IndexerDb db) =>
{
    var context = BrowsingContext.New(Configuration.Default);
    var doc = await context.OpenAsync(req => req.Content(dto.Html));
    var title = string.IsNullOrWhiteSpace(doc.Title) ? dto.Url : doc.Title!;
    var outlinks = new List<string>();
    var seen = new HashSet<string>();
    foreach (var el in doc.QuerySelectorAll("a[href]"))
    {
        var href = el.GetAttribute("href");
        if (string.IsNullOrEmpty(href)) continue;
        if (!Uri.TryCreate(new Uri(dto.Url), href, out var abs)) continue;
        var s = abs.ToString();
        if (s == dto.Url || !seen.Add(s)) continue;
        outlinks.Add(s);
    }

    var existing = db.PageLinks.Where(l => l.FromUrl == dto.Url);
    db.PageLinks.RemoveRange(existing);
    foreach (var to in outlinks)
    {
        db.PageLinks.Add(new PageLink { FromUrl = dto.Url, ToUrl = to });
    }
    await db.SaveChangesAsync();

    return Results.Ok(new { url = dto.Url, outlinks, storedLinks = outlinks.Count, title });
});

app.MapPost("/api/indexer/compute-pagerank", async (IndexerDb db) =>
{
    var edges = await db.PageLinks
        .Select(l => new Edge(l.FromUrl, l.ToUrl)).ToListAsync();
    var output = PageRankEngine.Compute(edges);

    await db.Database.ExecuteSqlRawAsync("TRUNCATE TABLE page_rank");
    var now = DateTimeOffset.UtcNow;
    foreach (var (url, rank) in output.Ranks)
    {
        db.PageRanks.Add(new PageRankRow
        {
            Url = url, Rank = rank, Iterations = output.Iterations, UpdatedAt = now
        });
    }
    await db.SaveChangesAsync();

    return Results.Ok(new
    {
        iterations = output.Iterations, damping = PageRankEngine.Damping,
        nodes = output.Nodes, edges = output.Edges, converged = output.Converged
    });
});

app.MapGet("/api/indexer/top", async (IndexerDb db) =>
{
    var rows = await db.PageRanks.OrderByDescending(r => r.Rank).Take(10).ToListAsync();
    return Results.Ok(rows.Select(r => new { url = r.Url, rank = Math.Round(r.Rank, 3) }));
});

app.MapGet("/api/indexer/rank/{url}", async (string url, IndexerDb db) =>
{
    var decoded = Uri.UnescapeDataString(url);
    var row = await db.PageRanks.FindAsync(decoded);
    if (row is null)
    {
        return Results.NotFound(new { statusCode = 404, message = $"URL {decoded} not found" });
    }
    return Results.Ok(new
    {
        url = row.Url, rank = row.Rank, iterations = row.Iterations,
        updatedAt = row.UpdatedAt.UtcDateTime.ToString("o")
    });
});

app.Run();

record ParseDto(string Url, string Html);
