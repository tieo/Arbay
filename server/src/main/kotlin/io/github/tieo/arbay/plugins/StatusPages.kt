package io.github.tieo.arbay.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory

private val statusPagesLog = LoggerFactory.getLogger("StatusPages")

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<NotFoundException> { call, cause ->
            call.respondText(cause.message ?: "Not found", status = HttpStatusCode.NotFound)
        }
        exception<BadRequestException> { call, cause ->
            call.respondText(cause.message ?: "Bad request", status = HttpStatusCode.BadRequest)
        }
        exception<SerializationException> { call, cause ->
            call.respondText(cause.message ?: "Invalid request body", status = HttpStatusCode.BadRequest)
        }
        exception<Throwable> { call, cause ->
            // The stack trace goes to the log, where it can be read; the caller learns only
            // that the server failed, not the paths and internals a message can carry.
            statusPagesLog.error("Unhandled error on {} {}", call.request.httpMethod.value, call.request.path(), cause)
            call.respondText("Internal error", status = HttpStatusCode.InternalServerError)
        }
    }
}

class NotFoundException(message: String) : RuntimeException(message)
class BadRequestException(message: String) : RuntimeException(message)
