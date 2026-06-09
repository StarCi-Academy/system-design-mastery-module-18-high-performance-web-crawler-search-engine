// Redis keys shared by the frontier service.
// QUEUE_KEY is a ZSET: priority is the score, the URL is the member.
export const QUEUE_KEY = "frontier:queue"
// BLOOM_KEY is a RedisBloom filter holding every URL the crawler has ever seen.
export const BLOOM_KEY = "seen:urls"
