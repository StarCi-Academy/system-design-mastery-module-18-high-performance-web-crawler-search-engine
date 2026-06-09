import {
    Body,
    Controller,
    Get,
    HttpCode,
    Param,
    Post,
} from "@nestjs/common"
import {
    DequeueResult,
    EnqueueDto,
    EnqueueResult,
    SeenResult,
    StatsResult,
} from "./dto"
import { FrontierService } from "./frontier.service"

@Controller("api/frontier")
export class FrontierController {
    constructor(private readonly frontierService: FrontierService) {}

    @Post("enqueue")
    @HttpCode(201)
    enqueue(@Body() dto: EnqueueDto): Promise<EnqueueResult> {
        return this.frontierService.enqueue(dto.url, dto.priority)
    }

    @Post("dequeue")
    @HttpCode(200)
    dequeue(): Promise<DequeueResult | null> {
        return this.frontierService.dequeue()
    }

    @Get("seen/:url")
    seen(@Param("url") url: string): Promise<SeenResult> {
        return this.frontierService.seen(decodeURIComponent(url))
    }

    @Get("stats")
    stats(): Promise<StatsResult> {
        return this.frontierService.stats()
    }
}
