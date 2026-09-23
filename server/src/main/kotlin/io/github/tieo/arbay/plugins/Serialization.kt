package io.github.tieo.arbay.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

val appJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(appJson)
    }
    // Everything the app sends is a small JSON document (a search, a setting, a verdict on one
    // listing); a body far past that is refused before it is read into memory.
    install(RequestBodyLimit) {
        bodyLimit { 1024L * 1024L }
    }
}
