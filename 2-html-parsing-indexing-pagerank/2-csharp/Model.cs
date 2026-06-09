using Microsoft.EntityFrameworkCore;

namespace Indexer;

// page_link stores one directed edge (FromUrl -> ToUrl) of the web graph.
public class PageLink
{
    public long Id { get; set; }
    public string FromUrl { get; set; } = "";
    public string ToUrl { get; set; } = "";
}

// page_rank stores the computed authority score per URL after a batch run.
public class PageRankRow
{
    public string Url { get; set; } = "";
    public double Rank { get; set; }
    public int Iterations { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }
}

public class IndexerDb(DbContextOptions<IndexerDb> options) : DbContext(options)
{
    public DbSet<PageLink> PageLinks => Set<PageLink>();
    public DbSet<PageRankRow> PageRanks => Set<PageRankRow>();

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.Entity<PageLink>(e =>
        {
            e.ToTable("page_link");
            e.HasKey(x => x.Id);
            e.Property(x => x.FromUrl).HasColumnName("from_url");
            e.Property(x => x.ToUrl).HasColumnName("to_url");
            // An edge is a set member: the same (from, to) pair appears at most once.
            e.HasIndex(x => new { x.FromUrl, x.ToUrl }).IsUnique();
        });
        b.Entity<PageRankRow>(e =>
        {
            e.ToTable("page_rank");
            e.HasKey(x => x.Url);
            e.Property(x => x.Url).HasColumnName("url");
            e.Property(x => x.Rank).HasColumnName("rank");
            e.Property(x => x.Iterations).HasColumnName("iterations");
            e.Property(x => x.UpdatedAt).HasColumnName("updated_at");
        });
    }
}
