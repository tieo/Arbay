package io.github.tieo.arbay

import io.github.tieo.arbay.classifier.FreeItemMonitor
import io.github.tieo.arbay.classifier.FreeItemProfileStore
import io.github.tieo.arbay.classifier.ModelRegistry
import io.github.tieo.arbay.classifier.models.*
import io.github.tieo.arbay.crawler.ExchangeRates
import io.github.tieo.arbay.plugins.configureRouting
import io.github.tieo.arbay.plugins.configureSerialization
import io.github.tieo.arbay.plugins.configureStatusPages
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.sse.*
import kotlinx.coroutines.launch

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(SSE)
    configureSerialization()
    configureStatusPages()
    configureRouting()
    // Register scoring models for the arena
    ModelRegistry.register(LogisticRegressionModel())
    ModelRegistry.register(KnnModel())
    ModelRegistry.register(CentroidModel())
    // Initial training from existing feedback
    ModelRegistry.retrainAll()

    launch { ExchangeRates.refresh() }
    // Start background monitor if tracking was previously enabled
    if (FreeItemProfileStore.get()?.trackingEnabled == true) {
        FreeItemMonitor.start()
    }
}
