export const DAMPING = 0.85
export const MAX_ITERATIONS = 10
export const TOLERANCE = 1e-6

export interface Edge {
    fromUrl: string
    toUrl: string
}

export interface PageRankOutput {
    ranks: Map<string, number>
    nodes: number
    edges: number
    iterations: number
    converged: boolean
}

/**
 * Iterative power-method PageRank with explicit dangling-node correction.
 *
 * Recurrence: PR(u) = (1 - d)/N + danglingShare + d * Σ_{v in in(u)} PR(v)/out(v)
 *
 * The danglingShare term redistributes the rank mass of nodes with no outlinks
 * uniformly across the whole graph, so the score vector keeps summing to 1.
 */
export function computePageRank(edges: Edge[]): PageRankOutput {
    const nodes = new Set<string>()
    for (const e of edges) {
        nodes.add(e.fromUrl)
        nodes.add(e.toUrl)
    }
    const N = nodes.size
    if (N === 0) {
        return { ranks: new Map(), nodes: 0, edges: 0, iterations: 0, converged: true }
    }

    const outDegree = new Map<string, number>()
    const inLinks = new Map<string, string[]>()
    for (const u of nodes) {
        outDegree.set(u, 0)
        inLinks.set(u, [])
    }
    for (const e of edges) {
        outDegree.set(e.fromUrl, (outDegree.get(e.fromUrl) ?? 0) + 1)
        inLinks.get(e.toUrl)!.push(e.fromUrl)
    }

    let rank = new Map<string, number>()
    for (const u of nodes) rank.set(u, 1 / N)

    let converged = false
    let it = 0
    for (; it < MAX_ITERATIONS; it++) {
        const next = new Map<string, number>()
        const danglingSum = [...nodes]
            .filter((u) => (outDegree.get(u) ?? 0) === 0)
            .reduce((s, u) => s + (rank.get(u) ?? 0), 0)
        const danglingShare = (DAMPING * danglingSum) / N
        const teleport = (1 - DAMPING) / N
        let delta = 0
        for (const u of nodes) {
            let sum = 0
            for (const v of inLinks.get(u) ?? []) {
                const dv = outDegree.get(v) ?? 0
                if (dv > 0) sum += (rank.get(v) ?? 0) / dv
            }
            const newRank = teleport + danglingShare + DAMPING * sum
            next.set(u, newRank)
            delta += Math.abs(newRank - (rank.get(u) ?? 0))
        }
        rank = next
        if (delta < TOLERANCE) {
            converged = true
            it++
            break
        }
    }

    return { ranks: rank, nodes: N, edges: edges.length, iterations: it, converged }
}
