import * as cheerio from "cheerio"

/**
 * Extract the unique, absolute outlinks from an HTML document.
 * - Relative hrefs are resolved against the page URL via the WHATWG URL parser.
 * - Self-links (abs === url) are filtered so a page cannot boost its own rank.
 * - A Set dedups repeated links within the same page (one edge, not many).
 */
export function extractOutlinks(url: string, html: string): { title: string; outlinks: string[] } {
    const $ = cheerio.load(html)
    const title = $("title").text().trim() || url
    const outlinks = new Set<string>()
    $("a[href]").each((_, el) => {
        const href = $(el).attr("href")
        if (!href) return
        try {
            const abs = new URL(href, url).toString()
            if (abs !== url) outlinks.add(abs)
        } catch {
            // ignore malformed hrefs
        }
    })
    return { title, outlinks: [...outlinks] }
}
