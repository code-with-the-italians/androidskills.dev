plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

group = "dev.androidskills"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.rateLimit)
    implementation(libs.logback.classic)

    // Persistence: SQLite via Exposed
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)

    // HTTP client: GitHub OAuth (token exchange + user lookup) in prod.
    // Tests use an in-process fake OAuthClient, so no network at test time.
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.contentNegotiation)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
    testImplementation(ktorLibs.client.mock)
    testImplementation("org.yaml:snakeyaml:2.2")
}

spotless {
    kotlin {
        ktfmt("0.52").googleStyle()
        target("src/**/*.kt")
    }
}

detekt {
    buildUponDefaultConfig = true
    config.from(file("config/detekt/detekt.yml"))
}
