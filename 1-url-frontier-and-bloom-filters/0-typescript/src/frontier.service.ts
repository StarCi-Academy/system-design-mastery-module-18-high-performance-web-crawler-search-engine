import { Injectable, Logger, OnModuleInit } from "@nestjs/common"
import { ConfigService } from "@nestjs/config"
import { InjectRepository } from "@nestjs/typeorm"
import Redis from "ioredis"
import { Repository } from "typeorm"
import { BLOOM_KEY, QUEUE_KEY } from "./constants"
import {
    DequeueResult,
    EnqueueResult,
    SeenResult,
    StatsResult,
} from "./dto"
import { FrontierEventEntity } from "./entities"

@Injectable()
export class FrontierService implements OnModuleInit {
    private readonly logger = new Logger(FrontierService.name)
    private readonly redis: Redis
    private readonly capacity: number
    private readonly errorRate: number
    private bloomReady = false

    constructor(
        private readonly config: ConfigService,
        @InjectRepository(FrontierEventEntity)
        private readonly eventRepo: Repository<FrontierEventEntity>,
    ) {
        this.redis = new Redis(
            this.config.get<string>("REDIS_URL") ?? "redis://localhost:6379",
        )
        this.capacity = Number(this.config.get<string>("BLOOM_CAPACITY") ?? 100000)
        this.errorRate = Number(this.config.get<string>("BLOOM_ERROR_RATE") ?? 0.01)
    }

    async onModuleInit(): Promise<void> {
        await this.ensureBloom()
    }

    // BF.RESERVE creates the bloom WITH the chosen error rate and capacity. RedisBloom does not
    // auto-create a filter with sensible defaults — a bare BF.ADD picks 0.01 / 100, destroying accuracy.
    private async ensureBloom(): Promise<void> {
        try {
            await this.redis.call(
                "BF.RESERVE",
                BLOOM_KEY,
                String(this.errorRate),
                String(this.capacity),
            )
            this.logger.log(`Created bloom filter ${BLOOM_KEY} cap=${this.capacity} err=${this.errorRate}`)
        } catch (err) {
            const msg = (err as Error).message || ""
            // "ERR item exists" means a previous pod already created the filter — leave it untouched.
            if (msg.includes("ERR item exists")) {
                this.logger.log(`Bloom filter ${BLOOM_KEY} already exists`)
            } else {
                throw err
            }
        }
        this.bloomReady = true
    }

    // BF.EXISTS BEFORE BF.ADD + ZADD is the canonical pattern: the bloom is a read-mostly pre-filter.
    async enqueue(url: string, priority: number): Promise<EnqueueResult> {
        if (!this.bloomReady) await this.ensureBloom()
        const exists = (await this.redis.call("BF.EXISTS", BLOOM_KEY, url)) as number
        if (exists === 1) {
            // A duplicate is short-circuited before it can reach the queue; still audit it.
            await this.eventRepo.save({ action: "enqueue-dup", url, priority })
            const size = await this.redis.zcard(QUEUE_KEY)
            return { url, priority, duplicate: true, queueSize: size }
        }
        await this.redis.call("BF.ADD", BLOOM_KEY, url)
        await this.redis.zadd(QUEUE_KEY, priority, url)
        await this.eventRepo.save({ action: "enqueue", url, priority })
        const size = await this.redis.zcard(QUEUE_KEY)
        return { url, priority, duplicate: false, queueSize: size }
    }

    // ZPOPMAX is one round trip and one server-side op — N workers each receive a distinct URL.
    async dequeue(): Promise<DequeueResult | null> {
        const popped = await this.redis.zpopmax(QUEUE_KEY)
        if (popped.length === 0) return null
        const url = popped[0]
        const priority = Number(popped[1])
        await this.eventRepo.save({ action: "dequeue", url, priority })
        const size = await this.redis.zcard(QUEUE_KEY)
        return { url, priority, queueSize: size }
    }

    // BF.EXISTS never returns a false negative: a 0 means the URL was provably never added.
    async seen(url: string): Promise<SeenResult> {
        const exists = (await this.redis.call("BF.EXISTS", BLOOM_KEY, url)) as number
        return { url, seen: exists === 1 }
    }

    async stats(): Promise<StatsResult> {
        const queueSize = await this.redis.zcard(QUEUE_KEY)
        const info = (await this.redis.call("BF.INFO", BLOOM_KEY)) as unknown[]
        // BF.INFO returns an interleaved [name, value, name, value, ...] array.
        const map = new Map<string, number>()
        for (let i = 0; i + 1 < info.length; i += 2) {
            map.set(String(info[i]), Number(info[i + 1]))
        }
        return {
            queueSize,
            bloomCapacity: map.get("Capacity") ?? this.capacity,
            bloomItems: map.get("Number of items inserted") ?? 0,
            bloomErrorRate: this.errorRate,
        }
    }
}
