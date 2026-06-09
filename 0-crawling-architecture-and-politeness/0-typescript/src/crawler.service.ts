import { Injectable, Logger, NotFoundException } from "@nestjs/common"
import { InjectRepository } from "@nestjs/typeorm"
import Redis from "ioredis"
import { Repository } from "typeorm"
import { fakeFetch } from "./fake-internet"
import { Page } from "./page.entity"
import { parseRobotsTxt, RobotsRules } from "./robots"

/** Shape returned by POST /api/crawler/seed — identical across all 4 language tracks. */
export interface SeedResult {
    /** The URL that was seeded. */
    url: string
    /** Hostname extracted from the URL. */
    host: string
    /** HTTP status code of the fetched page; 0 when the path is Disallowed. */
    statusCode: number
    /** Number of bytes stored in Postgres; 0 when the path is Disallowed. */
    bytesStored: number
    /** Milliseconds the service waited to respect the crawl-delay; 0 on first hit. */
    waitedMs: number
    /** false when the path was rejected by robots.txt; no page stored in that case. */
    allowed: boolean
}

/**
 * CrawlerService implements the core politeness-aware fetch pipeline:
 *   1. Load (and TTL-cache) the host's robots.txt from Redis.
 *   2. Check RFC 9309 allow/deny with longest-match.
 *   3. Apply the shared-clock crawl-delay enforced via Redis.
 *   4. Fetch the page from the in-process fake-internet fixture.
 *   5. UPSERT the page into Postgres.
 *
 * Keeping politeness state in Redis (not in process memory) means every worker
 * pod reads the same lastFetched timestamp, which is the key insight of the lesson.
 */
@Injectable()
export class CrawlerService {
    private readonly logger = new Logger(CrawlerService.name)
    private readonly redis: Redis
    /** How long robots.txt bodies and parsed crawl-delay values are cached. */
    private readonly ROBOTS_TTL_SECONDS = 60

    constructor(@InjectRepository(Page) private readonly pageRepo: Repository<Page>) {
        // A single shared Redis connection is sufficient; ioredis handles reconnection.
        this.redis = new Redis(process.env.REDIS_URL || "redis://localhost:6379")
    }

    /**
     * loadRobots fetches a host's robots.txt at most once per TTL window, then caches
     * both the raw body and the parsed crawl-delay under the same expiry.
     *
     * Testing `cached !== null` (not falsy) is deliberate: an empty robots body is a
     * valid "allow everything" answer and must not trigger a refetch.
     */
    private async loadRobots(host: string, ref: URL): Promise<RobotsRules> {
        const cacheKey = `crawler:robots:${host}:body`
        const cached = await this.redis.get(cacheKey)
        let body: string
        if (cached !== null) {
            // An EMPTY robots body is a valid "allow everything" answer, so we test
            // for null (cache miss) rather than a falsy empty string.
            body = cached
        } else {
            const robotsUrl = `${ref.protocol}//${host}/robots.txt`
            const fetched = fakeFetch(robotsUrl, "robots")
            // Treat any non-200 response as "no rules" — empty body = allow all.
            body = fetched.statusCode === 200 ? fetched.body : ""
            await this.redis.set(cacheKey, body, "EX", this.ROBOTS_TTL_SECONDS)
        }
        const rules = parseRobotsTxt(body)
        // Cache the parsed delay under the same TTL so body + delay refresh together.
        await this.redis.set(`crawler:robots:${host}:delay`, String(rules.crawlDelayMs), "EX", this.ROBOTS_TTL_SECONDS)
        return rules
    }

    /**
     * applyPoliteness derives the wait from a SHARED Redis clock, not local memory,
     * so every worker sees the same last-fetched timestamp for a host.
     *
     * Formula: wait = crawlDelay - (now - lastFetched).
     * Math.max(0, …) ensures we never sleep a negative amount.
     */
    private async applyPoliteness(host: string, delayMs: number): Promise<number> {
        if (delayMs <= 0) return 0
        const key = `crawler:host:${host}:lastFetched`
        const last = await this.redis.get(key)
        if (!last) return 0
        const elapsed = Date.now() - Number(last)
        // wait = delay - (now - lastFetched); never sleep a negative amount.
        const wait = Math.max(0, delayMs - elapsed)
        if (wait > 0) {
            this.logger.log(`Politeness wait ${wait}ms for ${host}`)
            await new Promise((resolve) => setTimeout(resolve, wait))
        }
        return wait
    }

    /**
     * seed ties robots loading, allow check, politeness wait, fetch, and persistence
     * into one observable request that returns the manifest the flows assert on.
     *
     * A Disallowed path returns immediately with `allowed:false`; it never touches
     * the network and nothing is stored in Postgres.
     */
    async seed(rawUrl: string): Promise<SeedResult> {
        const ref = new URL(rawUrl)
        const host = ref.hostname
        const rules = await this.loadRobots(host, ref)
        if (!rules.isAllowed(ref.pathname)) {
            // Disallowed paths never reach the network: 0 bytes stored, allowed=false.
            return { url: rawUrl, host, statusCode: 0, bytesStored: 0, waitedMs: 0, allowed: false }
        }
        const waitedMs = await this.applyPoliteness(host, rules.crawlDelayMs)
        const fetched = fakeFetch(rawUrl, "page")
        // Record lastFetched immediately after the fetch so the clock reflects this
        // request; subsequent requests to the same host compute their wait from here.
        await this.redis.set(`crawler:host:${host}:lastFetched`, String(Date.now()))
        const page = this.pageRepo.create({
            url: rawUrl,
            host,
            htmlBody: fetched.body,
            contentType: fetched.contentType,
            statusCode: fetched.statusCode,
        })
        // UPSERT by url so re-seeding the same URL updates instead of duplicating.
        await this.pageRepo.upsert(page, ["url"])
        return { url: rawUrl, host, statusCode: fetched.statusCode, bytesStored: fetched.body.length, waitedMs, allowed: true }
    }

    /**
     * listPages returns all crawled pages ordered by fetchedAt descending.
     * Only allowed pages are stored, so Disallow-ed paths never appear here.
     */
    async listPages(): Promise<Array<{ url: string; host: string; statusCode: number }>> {
        const pages = await this.pageRepo.find({ order: { fetchedAt: "DESC" } })
        // Expose only the fields the contract requires; keep htmlBody server-side.
        return pages.map((p) => ({ url: p.url, host: p.host, statusCode: p.statusCode }))
    }

    /**
     * politeness returns the current politeness state for a host from Redis.
     * Returns 404 if the host has never been crawled (no politeness state exists).
     */
    async politeness(host: string): Promise<{ host: string; crawlDelayMs: number; lastFetchedAt: string; nextAllowedAt: string }> {
        const delay = await this.redis.get(`crawler:robots:${host}:delay`)
        const last = await this.redis.get(`crawler:host:${host}:lastFetched`)
        if (delay === null || last === null) {
            throw new NotFoundException(`No politeness state for host ${host}`)
        }
        const crawlDelayMs = Number(delay)
        const lastFetchedMs = Number(last)
        return {
            host,
            crawlDelayMs,
            // ISO 8601 strings let the client parse independently of timezone.
            lastFetchedAt: new Date(lastFetchedMs).toISOString(),
            nextAllowedAt: new Date(lastFetchedMs + crawlDelayMs).toISOString(),
        }
    }
}
