using System.Net;
using FrontierService;
using StackExchange.Redis;

var builder = WebApplication.CreateBuilder(args);

var redisConn = builder.Configuration["REDIS_URL"] ?? "localhost:6379";
builder.Services.AddSingleton<IConnectionMultiplexer>(
    ConnectionMultiplexer.Connect(redisConn));
builder.Services.AddSingleton<FrontierService.FrontierService>();

var app = builder.Build();

var service = app.Services.GetRequiredService<FrontierService.FrontierService>();
await service.InitAsync();

app.MapPost("/api/frontier/enqueue", async (EnqueueDto dto) =>
    Results.Json(await service.EnqueueAsync(dto.Url, dto.Priority), statusCode: (int)HttpStatusCode.Created));

app.MapPost("/api/frontier/dequeue", async () =>
    Results.Json(await service.DequeueAsync()));

app.MapGet("/api/frontier/seen/{url}", async (string url) =>
    Results.Json(await service.SeenAsync(Uri.UnescapeDataString(url))));

app.MapGet("/api/frontier/stats", async () =>
    Results.Json(await service.StatsAsync()));

var port = Environment.GetEnvironmentVariable("PORT") ?? "3000";
app.Run($"http://0.0.0.0:{port}");

namespace FrontierService
{
    public record EnqueueDto(string Url, int Priority);
}
