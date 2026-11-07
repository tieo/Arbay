package io.github.tieo.arbay.routes

import io.github.tieo.arbay.chat.AuctionBids
import io.github.tieo.arbay.model.AuctionBidRequest
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

/** Bids Arbay places in an auction's last seconds, held here because they are placed whether or
 *  not the app is open. `POST /try` reads eBay's review of a bid without placing it. */
fun Route.auctionBidRoutes() {
    route("/api/auctions/bids") {
        get { call.respond(AuctionBids.all()) }
        post {
            val asked = call.receive<AuctionBidRequest>()
            // A search card's time left drops the minutes ("Noch 1 T 4 Std"); the item page's timer
            // names the end to the second, so the bid goes out in the auction's real last seconds.
            val exact = runCatching { io.github.tieo.arbay.neuland.NeulandOffers.detail(asked.url, "eBay").endsAt }.getOrNull()
            val request = exact?.let { asked.copy(endsAt = it) } ?: asked
            val bid = runCatching { AuctionBids.ask(request) }.getOrElse { throw BadRequestException(it.message ?: "no bid") }
            call.respond(bid)
        }
        post("/try") {
            val request = call.receive<AuctionBidRequest>()
            val max = AuctionBids.maxCents(request.allInEur, request.shippingEur) ?: throw BadRequestException("nothing is left to bid after shipping")
            val review = try {
                AuctionBids.review(request.url, max / 100.0, request.allInEur)
            } catch (e: io.github.tieo.arbay.neuland.Neuland.Refusal) {
                throw BadRequestException("eBay's bid form: ${e.message}")
            } catch (e: IllegalArgumentException) {
                throw BadRequestException(e.message ?: "no look now")
            }
            call.respond(review)
        }
        delete("/{listingId}") {
            val id = call.parameters["listingId"] ?: throw BadRequestException("Missing listingId")
            call.respond(mapOf("cancelled" to AuctionBids.cancel(id)))
        }
    }
}
