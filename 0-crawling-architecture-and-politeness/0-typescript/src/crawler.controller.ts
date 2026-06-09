import { Body, Controller, Get, Param, Post } from "@nestjs/common"
import { CrawlerService } from "./crawler.service"

/** Request body for POST /api/crawler/seed. */
interface SeedDto {
    /** Absolute URL to crawl (e.g. "http://books.starci.test/"). */
    url: string
}

/**
 * CrawlerController exposes the three REST endpoints that the lesson flows exercise:
 * seed a URL, list stored pages, and inspect a host's politeness state.
 */
@Controller("api/crawler")
export class CrawlerController {
    constructor(private readonly crawler: CrawlerService) {}

    /**
     * POST /api/crawler/seed
     * Validates robots.txt for the URL's host, enforces the crawl-delay, fetches
     * the page from the fake-internet fixture, and persists it to Postgres.
     */
    @Post("seed")
    async seed(@Body() body: SeedDto): Promise<unknown> {
        // Delegate all business logic to the service so the controller stays thin.
        return this.crawler.seed(body.url)
    }

    /**
     * GET /api/crawler/pages
     * Returns all successfully crawled pages ordered by fetchedAt descending.
     * Disallowed paths never appear here — they were rejected before persistence.
     */
    @Get("pages")
    async pages(): Promise<unknown> {
        return this.crawler.listPages()
    }

    /**
     * GET /api/crawler/politeness/:host
     * Returns the politeness snapshot for a host: crawlDelayMs, lastFetchedAt,
     * and nextAllowedAt.  Returns 404 if the host has not been crawled yet.
     */
    @Get("politeness/:host")
    async politeness(@Param("host") host: string): Promise<unknown> {
        return this.crawler.politeness(host)
    }
}
