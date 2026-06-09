import { Module } from "@nestjs/common"
import { ConfigModule } from "@nestjs/config"
import { TypeOrmModule } from "@nestjs/typeorm"
import { CrawlerController } from "./crawler.controller"
import { CrawlerService } from "./crawler.service"
import { Page } from "./page.entity"

/**
 * Root application module.
 *
 * Wires ConfigModule (global env), TypeORM connected to Postgres via POSTGRES_URL,
 * and the single CrawlerModule feature (controller + service + Page entity).
 */
@Module({
    imports: [
        // ConfigModule makes process.env values available across the whole app
        // without per-module imports.
        ConfigModule.forRoot({ isGlobal: true }),
        TypeOrmModule.forRoot({
            type: "postgres",
            url: process.env.POSTGRES_URL || "postgres://crawler:crawler@localhost:5432/crawler",
            entities: [Page],
            // synchronize:false keeps TypeORM from silently altering the schema at
            // runtime; the table is created by the init.sql in compose (or auto-create
            // on first connect via the Page entity's schema when running fresh).
            synchronize: false,
        }),
        TypeOrmModule.forFeature([Page]),
    ],
    controllers: [CrawlerController],
    providers: [CrawlerService],
})
export class AppModule {}
