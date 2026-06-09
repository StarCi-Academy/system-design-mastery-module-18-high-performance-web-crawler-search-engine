import { Module } from "@nestjs/common"
import { ConfigModule, ConfigService } from "@nestjs/config"
import { TypeOrmModule } from "@nestjs/typeorm"
import { IndexerController } from "./indexer.controller"
import { IndexerService } from "./indexer.service"
import { PageLink, PageRank } from "./entities"

@Module({
    imports: [
        ConfigModule.forRoot({ isGlobal: true }),
        TypeOrmModule.forRootAsync({
            inject: [ConfigService],
            useFactory: (config: ConfigService) => ({
                type: "postgres",
                url: config.get<string>("POSTGRES_URL"),
                entities: [PageLink, PageRank],
                synchronize: true,
            }),
        }),
        TypeOrmModule.forFeature([PageLink, PageRank]),
    ],
    controllers: [IndexerController],
    providers: [IndexerService],
})
export class AppModule {}
