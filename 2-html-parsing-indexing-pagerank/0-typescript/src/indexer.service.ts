import { Injectable, NotFoundException, OnModuleInit } from "@nestjs/common"
import { InjectRepository } from "@nestjs/typeorm"
import { Repository } from "typeorm"
import {
    ComputeResult,
    ParseResult,
    RankResult,
    TopRow,
} from "./dto"
import { PageLink, PageRank } from "./entities"
import { extractOutlinks } from "./parsing"
import { computePageRank, DAMPING } from "./pagerank"

// Demo graph seeded on boot so the API returns useful data without manual setup.
const SEED_EDGES: Array<[string, string]> = [
    ["a", "b"],
    ["a", "c"],
    ["b", "c"],
    ["c", "a"],
    ["d", "c"],
    ["d", "b"],
    ["e", "d"],
]
const BASE = "https://search.starci.test/"

@Injectable()
export class IndexerService implements OnModuleInit {
    constructor(
        @InjectRepository(PageLink) private readonly linkRepo: Repository<PageLink>,
        @InjectRepository(PageRank) private readonly rankRepo: Repository<PageRank>,
    ) {}

    // Seed the demo link graph on boot only when page_link is empty.
    async onModuleInit(): Promise<void> {
        const count = await this.linkRepo.count()
        if (count > 0) return
        const records = SEED_EDGES.map(([from, to]) => ({
            fromUrl: `${BASE}${from}`,
            toUrl: `${BASE}${to}`,
        }))
        await this.linkRepo.save(records)
    }

    /**
     * Parse an HTML page: extract outlinks, then DELETE existing edges from this
     * URL before INSERT so a re-parse REPLACES (never appends) the outlink set.
     */
    async parse(url: string, html: string): Promise<ParseResult> {
        const { title, outlinks } = extractOutlinks(url, html)
        await this.linkRepo.delete({ fromUrl: url })
        const records = outlinks.map((toUrl) => ({ fromUrl: url, toUrl }))
        if (records.length > 0) await this.linkRepo.save(records)
        return { url, outlinks, storedLinks: records.length, title }
    }

    // Run the batch PageRank job over the whole link graph and upsert the scores.
    async computePageRank(): Promise<ComputeResult> {
        const links = await this.linkRepo.find()
        const edges = links.map((l) => ({ fromUrl: l.fromUrl, toUrl: l.toUrl }))
        const result = computePageRank(edges)
        const now = new Date()
        const rows = [...result.ranks.entries()].map(([url, rank]) => ({
            url,
            rank,
            iterations: result.iterations,
            updatedAt: now,
        }))
        await this.rankRepo.clear()
        if (rows.length > 0) await this.rankRepo.save(rows)
        return {
            iterations: result.iterations,
            damping: DAMPING,
            nodes: result.nodes,
            edges: result.edges,
            converged: result.converged,
        }
    }

    // Leaderboard: pages ordered by authority score, highest first.
    async top(): Promise<TopRow[]> {
        const rows = await this.rankRepo.find({
            order: { rank: "DESC" },
            take: 10,
        })
        return rows.map((r) => ({ url: r.url, rank: Number(r.rank.toFixed(3)) }))
    }

    // Look up the precomputed rank of a single URL; 404 when never ranked.
    async rankOf(url: string): Promise<RankResult> {
        const row = await this.rankRepo.findOne({ where: { url } })
        if (!row) throw new NotFoundException(`URL ${url} not found`)
        return {
            url: row.url,
            rank: Number(row.rank),
            iterations: row.iterations,
            updatedAt: row.updatedAt.toISOString(),
        }
    }
}
