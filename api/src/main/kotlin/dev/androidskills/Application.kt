package dev.androidskills

import dev.androidskills.llm.LlmClient
import dev.androidskills.llm.StubLlmClient
import dev.androidskills.storage.FileStore
import dev.androidskills.storage.LocalFsStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/**
 * Ktor application module (referenced from application.conf). The entrypoint is
 * io.ktor.server.netty.EngineMain (see main.kt).
 */
fun Application.module(config: AppConfig = AppConfig.fromEnv()) {
    Database.init(config)

    install(ContentNegotiation) { json() }
    install(CallLogging)

    val fileStore: FileStore = LocalFsStore(config.fileStoreDir)
    val llm: LlmClient = StubLlmClient()

    routing {
        get("/api/health") {
            call.respond(
                HealthResponse(
                    ok = true,
                    version = config.version,
                    db = Database.journalMode(),
                    fileStore = fileStore.kind,
                    llm = llm.kind,
                ),
            )
        }
    }
}

@Serializable
data class HealthResponse(
    val ok: Boolean,
    val version: String,
    val db: String,
    val fileStore: String,
    val llm: String,
)
