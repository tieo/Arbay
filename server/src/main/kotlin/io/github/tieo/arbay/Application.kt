package io.github.tieo.arbay

import io.github.tieo.arbay.classifier.FreeItemMonitor
import io.github.tieo.arbay.classifier.FreeItemProfileStore
import io.github.tieo.arbay.classifier.ModelRegistry
import io.github.tieo.arbay.classifier.models.*
import io.github.tieo.arbay.crawler.CarTaxonomyProvider
import io.github.tieo.arbay.crawler.ExchangeRates
import io.github.tieo.arbay.plugins.configureRouting
import io.github.tieo.arbay.plugins.configureSerialization
import io.github.tieo.arbay.plugins.configureStatusPages
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.sse.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.event.Level

fun main() {
    // CLIP image preprocessing uses java.awt (BufferedImage/Graphics2D); headless avoids
    // needing an X11 display/libs on the server.
    System.setProperty("java.awt.headless", "true")
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(SSE)
    // Request log: one line per API call with method, path, status and duration — the audit
    // trail for what the app asked of the server (and how long crawls took).
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
    }
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
    // Rebuild the car taxonomy from site catalogs daily; refresh() is a no-op until
    // per-site probing lands, so the bundled seed stays authoritative meanwhile.
    launch {
        while (true) {
            try { CarTaxonomyProvider.refresh() } catch (_: Exception) {}
            delay(24 * 60 * 60 * 1000L)
        }
    }
    // Start background monitor if tracking was previously enabled
    if (FreeItemProfileStore.get()?.trackingEnabled == true) {
        FreeItemMonitor.start()
    }
}
