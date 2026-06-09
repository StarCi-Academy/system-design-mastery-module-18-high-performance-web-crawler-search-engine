namespace Indexer;

public record Edge(string FromUrl, string ToUrl);

public record PageRankOutput(
    Dictionary<string, double> Ranks, int Nodes, int Edges, int Iterations, bool Converged);

// Iterative power-method PageRank with explicit dangling-node correction.
public static class PageRankEngine
{
    public const double Damping = 0.85;
    public const int MaxIterations = 10;
    public const double Tolerance = 1e-6;

    public static PageRankOutput Compute(IReadOnlyList<Edge> edges)
    {
        var nodes = new HashSet<string>();
        foreach (var e in edges)
        {
            nodes.Add(e.FromUrl);
            nodes.Add(e.ToUrl);
        }
        int n = nodes.Count;
        if (n == 0)
        {
            return new PageRankOutput(new(), 0, 0, 0, true);
        }

        var outDegree = nodes.ToDictionary(u => u, _ => 0);
        var inLinks = nodes.ToDictionary(u => u, _ => new List<string>());
        foreach (var e in edges)
        {
            outDegree[e.FromUrl]++;
            inLinks[e.ToUrl].Add(e.FromUrl);
        }

        var rank = nodes.ToDictionary(u => u, _ => 1.0 / n);

        bool converged = false;
        int it = 0;
        for (; it < MaxIterations; it++)
        {
            var next = new Dictionary<string, double>(n);
            double danglingSum = nodes.Where(u => outDegree[u] == 0).Sum(u => rank[u]);
            double danglingShare = (Damping * danglingSum) / n;
            double teleport = (1.0 - Damping) / n;
            double delta = 0.0;
            foreach (var u in nodes)
            {
                double sum = 0.0;
                foreach (var v in inLinks[u])
                {
                    if (outDegree[v] > 0) sum += rank[v] / outDegree[v];
                }
                double newRank = teleport + danglingShare + Damping * sum;
                next[u] = newRank;
                delta += Math.Abs(newRank - rank[u]);
            }
            rank = next;
            if (delta < Tolerance)
            {
                converged = true;
                it++;
                break;
            }
        }

        return new PageRankOutput(rank, n, edges.Count, it, converged);
    }
}
