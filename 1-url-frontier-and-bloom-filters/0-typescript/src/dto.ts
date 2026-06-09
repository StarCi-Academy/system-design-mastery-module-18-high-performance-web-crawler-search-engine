export interface EnqueueDto {
    url: string
    priority: number
}

export interface EnqueueResult {
    url: string
    priority: number
    duplicate: boolean
    queueSize: number
}

export interface DequeueResult {
    url: string
    priority: number
    queueSize: number
}

export interface SeenResult {
    url: string
    seen: boolean
}

export interface StatsResult {
    queueSize: number
    bloomCapacity: number
    bloomItems: number
    bloomErrorRate: number
}
