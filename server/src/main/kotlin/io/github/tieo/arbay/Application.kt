package io.github.tieo.arbay

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
    launch { ExchangeRates.refresh() }
}
