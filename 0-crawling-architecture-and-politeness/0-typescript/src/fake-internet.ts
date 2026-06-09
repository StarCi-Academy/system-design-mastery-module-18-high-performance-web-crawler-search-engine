// A hermetic "fake internet" fixture so the lesson runs without real network access.
// Each host publishes its own robots.txt and a small set of pages. This keeps the
// politeness and robots mechanics observable and deterministic in CI.

/** Shape returned by fakeFetch for both robots.txt and page requests. */
export interface FetchResult {
    /** HTTP status code (200 or 404). */
    statusCode: number
    /** Response body text (may be empty). */
    body: string
    /** MIME content type (text/plain for robots, text/html for pages). */
    contentType: string
}

/** Fixture definition for a single fake host. */
interface FakeHost {
    /** Raw robots.txt body to return for GET /robots.txt. */
    robots: string
    /** Mapping from URL path to HTML page body. */
    pages: Record<string, string>
}

// Single fixture host: books.starci.test.
// Declares Crawl-delay: 1 so Flow 3 exercises the shared-clock wait.
// Disallow: /admin so Flow 2 exercises the allow/deny check.
const FAKE_INTERNET: Record<string, FakeHost> = {
    "books.starci.test": {
        robots: ["User-agent: *", "Allow: /", "Disallow: /admin", "Crawl-delay: 1"].join("\n"),
        pages: {
            "/": "<html><head><title>Books</title></head><body>Welcome to the books index page.</body></html>",
            "/book/1": "<html><head><title>Book 1</title></head><body>The first book in the catalog.</body></html>",
            "/book/2": "<html><head><title>Book 2</title></head><body>The second book in the catalog.</body></html>",
        },
    },
}

/**
 * Simulate an HTTP fetch without leaving the process.
 * Returns a canned response for a robots.txt URL or a page URL.
 *
 * @param rawUrl absolute URL to "fetch"
 * @param kind   "robots" to retrieve robots.txt, "page" to retrieve an HTML page
 * @returns {@link FetchResult} with statusCode, body, and contentType
 */
export function fakeFetch(rawUrl: string, kind: "robots" | "page"): FetchResult {
    const url = new URL(rawUrl)
    const host = url.hostname
    const site = FAKE_INTERNET[host]
    if (kind === "robots") {
        // Unknown host = no robots.txt → 404, empty body (crawl allowed by default).
        if (!site) return { statusCode: 404, body: "", contentType: "text/plain" }
        return { statusCode: 200, body: site.robots, contentType: "text/plain" }
    }
    // Unknown host or unknown path → 404, empty body.
    if (!site) return { statusCode: 404, body: "", contentType: "text/html" }
    const page = site.pages[url.pathname]
    if (page === undefined) return { statusCode: 404, body: "", contentType: "text/html" }
    return { statusCode: 200, body: page, contentType: "text/html" }
}
