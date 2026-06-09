package indexer

import "math"

const (
	Damping       = 0.85
	MaxIterations = 10
	Tolerance     = 1e-6
)

// Edge is a directed link fromUrl -> toUrl.
type Edge struct {
	FromURL string
	ToURL   string
}

// PageRankOutput holds the computed ranks and run metadata.
type PageRankOutput struct {
	Ranks      map[string]float64
	Nodes      int
	Edges      int
	Iterations int
	Converged  bool
}

// ComputePageRank runs the iterative power-method PageRank with explicit
// dangling-node correction so the score vector keeps summing to 1.
func ComputePageRank(edges []Edge) PageRankOutput {
	nodeSet := make(map[string]struct{})
	for _, e := range edges {
		nodeSet[e.FromURL] = struct{}{}
		nodeSet[e.ToURL] = struct{}{}
	}
	n := len(nodeSet)
	if n == 0 {
		return PageRankOutput{Ranks: map[string]float64{}, Converged: true}
	}

	nodes := make([]string, 0, n)
	for u := range nodeSet {
		nodes = append(nodes, u)
	}

	outDegree := make(map[string]int, n)
	inLinks := make(map[string][]string, n)
	for _, u := range nodes {
		outDegree[u] = 0
		inLinks[u] = nil
	}
	for _, e := range edges {
		outDegree[e.FromURL]++
		inLinks[e.ToURL] = append(inLinks[e.ToURL], e.FromURL)
	}

	rank := make(map[string]float64, n)
	for _, u := range nodes {
		rank[u] = 1.0 / float64(n)
	}

	converged := false
	it := 0
	for ; it < MaxIterations; it++ {
		next := make(map[string]float64, n)
		// Rank mass of nodes with NO outlinks is redistributed uniformly,
		// so the score vector keeps summing to 1 (the production-grade detail).
		danglingSum := 0.0
		for _, u := range nodes {
			if outDegree[u] == 0 {
				danglingSum += rank[u]
			}
		}
		danglingShare := (Damping * danglingSum) / float64(n)
		teleport := (1.0 - Damping) / float64(n)
		delta := 0.0
		for _, u := range nodes {
			sum := 0.0
			for _, v := range inLinks[u] {
				if outDegree[v] > 0 {
					sum += rank[v] / float64(outDegree[v])
				}
			}
			newRank := teleport + danglingShare + Damping*sum
			next[u] = newRank
			delta += math.Abs(newRank - rank[u])
		}
		rank = next
		if delta < Tolerance {
			converged = true
			it++
			break
		}
	}

	return PageRankOutput{
		Ranks:      rank,
		Nodes:      n,
		Edges:      len(edges),
		Iterations: it,
		Converged:  converged,
	}
}
