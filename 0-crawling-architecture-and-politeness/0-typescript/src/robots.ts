// A minimal RFC 9309 robots.txt parser: collects Allow/Disallow directives for the
// wildcard user-agent plus the Crawl-delay, and decides allow/deny by longest-match.

/**
 * Parsed representation of a host's robots.txt.
 * Exposes the crawl-delay and an allow/deny predicate.
 */
export interface RobotsRules {
    /** Crawl-delay in milliseconds (0 = no delay declared). */
    crawlDelayMs: number
    /**
     * Decide whether a URL path is allowed by this robots.txt.
     * RFC 9309: longest matching prefix wins; a tie resolves to Allow.
     */
    isAllowed(path: string): boolean
}

/** Internal storage type for a single Allow or Disallow directive. */
interface Rule {
    allow: boolean
    /** Path prefix to match against the requested URL path. */
    pattern: string
}

/**
 * Parse a raw robots.txt body into a {@link RobotsRules} object.
 * An empty body is a valid "allow everything" answer — call sites must pass
 * empty string rather than null to represent "no robots.txt".
 *
 * @param body raw robots.txt text content
 * @returns parsed rules with a crawlDelayMs value and an isAllowed predicate
 */
export function parseRobotsTxt(body: string): RobotsRules {
    const rules: Rule[] = []
    let crawlDelayMs = 0
    for (const rawLine of body.split("\n")) {
        // Strip inline comments; blank lines are skipped.
        const line = rawLine.split("#")[0].trim()
        if (line.length === 0) continue
        const sep = line.indexOf(":")
        if (sep === -1) continue
        const key = line.slice(0, sep).trim().toLowerCase()
        const value = line.slice(sep + 1).trim()
        if (key === "disallow") {
            // An empty Disallow value means "allow all" in RFC 9309 — skip it.
            if (value.length > 0) rules.push({ allow: false, pattern: value })
        } else if (key === "allow") {
            if (value.length > 0) rules.push({ allow: true, pattern: value })
        } else if (key === "crawl-delay") {
            const seconds = Number(value)
            // NaN check guards against malformed values like "crawl-delay: fast".
            if (!Number.isNaN(seconds)) crawlDelayMs = Math.round(seconds * 1000)
        }
    }
    return {
        crawlDelayMs,
        isAllowed(path: string): boolean {
            // RFC 9309: the most specific (longest prefix) matching rule wins; a tie
            // resolves to Allow.
            let best: Rule | null = null
            for (const rule of rules) {
                if (path.startsWith(rule.pattern)) {
                    if (best === null || rule.pattern.length > best.pattern.length) best = rule
                    // Equal-length prefix: Allow beats Disallow (RFC 9309 tie-break).
                    else if (rule.pattern.length === best.pattern.length && rule.allow) best = rule
                }
            }
            // No matching rule → unrestricted (allow by default).
            return best === null ? true : best.allow
        },
    }
}
