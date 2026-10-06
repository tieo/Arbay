package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.ImportRules
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.format
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.importVat
import io.github.tieo.arbay.model.tidyTitle
import io.github.tieo.arbay.results.detailSpecs
import io.github.tieo.arbay.results.label
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Article
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** The listing chosen in the middle pane: its photos, price, every fact the market published, its
 *  own description, and a way to each market that carries it. */
@Composable
fun DetailPane(app: WebApp, route: Route) {
    val id = route.listing ?: return
    val listings by app.listings.listings.collectAsState()
    val elsewhere by app.listings.elsewhere.collectAsState()
    val fetched by app.listings.fetched.collectAsState()
    val listing = listings.firstOrNull { it.id == id } ?: fetched.firstOrNull { it.id == id }
    if (listing == null) {
        Div({ classes("empty") }) { Text("Waiting for this listing to come back from its market…") }
        return
    }
    ListingDetailView(app, listing, elsewhere[listing.id].orEmpty())
}

@Composable
private fun ListingDetailView(app: WebApp, listing: Listing, copies: List<Listing>) {
    // A card is the market's summary of the ad; this pane is the ad. Its page is read once, for the
    // listing being looked at, for what the card leaves out.
    var fromItsPage by remember(listing.id) { mutableStateOf<ListingDetail?>(null) }
    var reading by remember(listing.id) { mutableStateOf(false) }
    LaunchedEffect(listing.id) {
        if (listing.url.isBlank()) return@LaunchedEffect
        reading = true
        fromItsPage = app.client.listingDetail(listing)
        reading = false
    }
    val vehicle = fromItsPage?.vehicle?.let { page ->
        listing.vehicle?.let { card ->
            card.copy(wheelbaseMm = card.wheelbaseMm ?: page.wheelbaseMm, verified = card.verified + page.verified)
        } ?: page
    } ?: listing.vehicle
    val description = fromItsPage?.description?.takeIf { it.length > (listing.description?.length ?: 0) }
        ?: listing.description

    Article({ classes("listing") }) {
        H2 { Text(listing.title.tidyTitle()) }

        Gallery(listing)

        Div({ classes("price-block") }) {
            Span({ classes("price", "big") }) { Text(listing.comparablePrice.format()) }
            listing.oldPrice?.let { Span({ classes("old-price") }) { Text(it.format()) } }
            listing.importVat(ImportRules.current)?.let { vat ->
                Span({ classes("muted") }) {
                    Text("includes ${ImportRules.current.importVatPercent}% import VAT of ${vat.format()} · ${listing.price.format()} on ${listing.platformId.displayName}")
                }
            }
            listing.shipping?.cost?.takeIf { it.amount > 0 }?.let { Span({ classes("muted") }) { Text("delivery ${it.format()}") } }
        }

        Div({ classes("actions") }) {
            MarketLink(listing, primary = true, priceDiffers = false)
            copies.forEach { copy -> MarketLink(copy, primary = false, priceDiffers = copy.comparablePrice != listing.comparablePrice) }
        }

        Div({ classes("facts") }) {
            val place = listing.location ?: fromItsPage?.location
            buildList {
                listing.condition?.let { add(it.label) }
                place?.let { loc -> listOfNotNull(loc.zip, loc.city ?: loc.raw ?: loc.country).joinToString(" ").takeIf { it.isNotBlank() }?.let(::add) }
                listing.distanceKm?.let { add("${it.toInt()} km away") }
                listing.listingDate?.let { add("posted ${it.toLocalDateTime(TimeZone.currentSystemDefault()).date}") }
                if (listing.saleType == SaleType.AUCTION) add(listing.bidCount?.let { "$it bids" } ?: "auction")
                if (listing.shipping?.free == true) add("free shipping")
                if (listing.negotiable) add("negotiable")
            }.forEach { fact -> Span({ classes("fact") }) { Text(fact) } }
        }

        vehicle?.let { v ->
            val specs = detailSpecs(v)
            if (specs.isNotEmpty()) {
                H3 { Text("What the market says it is") }
                Div({ classes("specs") }) {
                    specs.forEach { spec ->
                        val stated = spec.stated(v)
                        Span({ classes("spec-label") }) { Text(spec.label) }
                        Span({
                            classes(*listOfNotNull("spec-value", "read".takeIf { !stated }).toTypedArray())
                            if (!stated) attr("title", "read out of the words, not stated")
                        }) { Text(if (stated) spec.value else "~${spec.value}") }
                    }
                }
            }
        }

        listing.seller?.let { seller ->
            P({ classes("seller") }) { Text("Sold by ${seller.name}" + (seller.rating?.let { " · $it★" } ?: "")) }
        }

        description?.takeIf { it.isNotBlank() }?.let {
            H3 { Text("Description") }
            P({ classes("description") }) { Text(it) }
        }
        if (reading) P({ classes("muted") }) { Text("Reading the rest off the ad…") }
    }
}

/** The photos large, one at a time, with every one of them beside it to pick from. */
@Composable
private fun Gallery(listing: Listing) {
    val images = listing.imageUrls.filter { it.isNotBlank() }
    if (images.isEmpty()) return
    var shown by remember(listing.id) { mutableStateOf(0) }
    Div({ classes("gallery") }) {
        Img(src = images[shown.coerceIn(0, images.lastIndex)], alt = listing.title) {
            classes("hero")
            attr("referrerpolicy", "no-referrer")
        }
        if (images.size > 1) {
            Div({ classes("strip") }) {
                images.forEachIndexed { index, url ->
                    Button(attrs = {
                        classes(*listOfNotNull("strip-item", "on".takeIf { index == shown }).toTypedArray())
                        attr("aria-label", "Photo ${index + 1}")
                        onClick { shown = index }
                    }) {
                        Img(src = url, alt = "") {
                            attr("loading", "lazy")
                            attr("referrerpolicy", "no-referrer")
                        }
                    }
                }
            }
        }
    }
}

/** A real link to the ad on its market, opening beside the app. */
@Composable
private fun MarketLink(listing: Listing, primary: Boolean, priceDiffers: Boolean) {
    A(href = listing.url, attrs = {
        classes(if (primary) "button" else "button-secondary")
        attr("target", "_blank")
        attr("rel", "noopener noreferrer")
    }) {
        Text(
            (if (primary) "Open on " else "Also on ") + listing.platformId.displayName +
                if (priceDiffers) " · ${listing.comparablePrice.format()}" else "",
        )
    }
}
