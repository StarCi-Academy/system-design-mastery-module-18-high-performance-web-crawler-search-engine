export interface ParseDto {
    url: string
    html: string
}

export interface ParseResult {
    url: string
    outlinks: string[]
    storedLinks: number
    title: string
}

export interface ComputeResult {
    iterations: number
    damping: number
    nodes: number
    edges: number
    converged: boolean
}

export interface TopRow {
    url: string
    rank: number
}

export interface RankResult {
    url: string
    rank: number
    iterations: number
    updatedAt: string
}
