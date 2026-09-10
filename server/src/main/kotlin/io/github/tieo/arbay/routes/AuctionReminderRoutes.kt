package io.github.tieo.arbay.routes

import io.github.tieo.arbay.model.AuctionReminder
import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.repo.AuctionReminderStore
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Auctions someone asked to be told about before they end. Held here rather than on the device
 *  because the deadline passes whether or not the app is running. */
fun Route.auctionReminderRoutes() {
    route("/api/auctions/reminders") {
        get { call.respond(AuctionReminderStore.all()) }
        post { call.respond(AuctionReminderStore.set(call.receive<AuctionReminder>())) }
        delete("/{listingId}") {
            val id = call.parameters["listingId"] ?: throw BadRequestException("Missing listingId")
            AuctionReminderStore.remove(id)
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }
}
