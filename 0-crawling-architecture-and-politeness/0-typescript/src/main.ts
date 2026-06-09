import "reflect-metadata"
import { NestFactory } from "@nestjs/core"
import { AppModule } from "./app.module"

/**
 * Bootstrap the NestJS application, bind to 0.0.0.0 so the container port is
 * reachable from the host when running under Docker Compose.
 */
async function bootstrap(): Promise<void> {
    const app = await NestFactory.create(AppModule)
    const port = Number(process.env.PORT) || 3000
    // "0.0.0.0" is required inside a container — the default 127.0.0.1 makes the
    // port invisible to the host even with a published port mapping.
    await app.listen(port, "0.0.0.0")
    // eslint-disable-next-line no-console
    console.log(`crawler-service listening on ${port}`)
}

void bootstrap()
