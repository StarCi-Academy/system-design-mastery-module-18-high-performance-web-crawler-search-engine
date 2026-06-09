import { Module } from "@nestjs/common"
import { ConfigModule, ConfigService } from "@nestjs/config"
import { TypeOrmModule } from "@nestjs/typeorm"
import { FrontierController } from "./frontier.controller"
import { FrontierService } from "./frontier.service"
import { FrontierEventEntity } from "./entities"

@Module({
    imports: [
        ConfigModule.forRoot({ isGlobal: true }),
        TypeOrmModule.forRootAsync({
            inject: [ConfigService],
            useFactory: (config: ConfigService) => ({
                type: "postgres",
                url: config.get<string>("POSTGRES_URL"),
                entities: [FrontierEventEntity],
                synchronize: false,
            }),
        }),
        TypeOrmModule.forFeature([FrontierEventEntity]),
    ],
    controllers: [FrontierController],
    providers: [FrontierService],
})
export class AppModule {}
