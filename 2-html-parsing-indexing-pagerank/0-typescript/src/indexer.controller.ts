import { Body, Controller, Get, HttpCode, Param, Post } from "@nestjs/common"
import { ComputeResult, ParseDto, ParseResult, RankResult, TopRow } from "./dto"
import { IndexerService } from "./indexer.service"

@Controller("api/indexer")
export class IndexerController {
    constructor(private readonly indexerService: IndexerService) {}

    @Post("parse")
    @HttpCode(200)
    parse(@Body() dto: ParseDto): Promise<ParseResult> {
        return this.indexerService.parse(dto.url, dto.html)
    }

    @Post("compute-pagerank")
    @HttpCode(200)
    compute(): Promise<ComputeResult> {
        return this.indexerService.computePageRank()
    }

    @Get("top")
    top(): Promise<TopRow[]> {
        return this.indexerService.top()
    }

    @Get("rank/:url")
    rankOf(@Param("url") url: string): Promise<RankResult> {
        return this.indexerService.rankOf(decodeURIComponent(url))
    }
}
