package io.github.tieo.arbay.ui.screen

import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.math.roundToInt
import io.github.tieo.arbay.model.importVat
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.ImportRules
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.imageModel
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Location
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.ui.AdaptiveSheet

/**
 * A listing's full content, in the app — the whole reason it needs to be here at all rather than
 * a card that always jumps straight to the platform's own page. Backing an ad up (see
 * [io.github.tieo.arbay.api.ArbayClient.getArchivedListing]) only means something if there is
 * somewhere to see the backup once the live page is gone; this is that somewhere, and it renders
 * a live listing exactly the same way, so nothing about it looks like a fallback path.
 */
@Composable
fun ListingDetailSheet(
    listing: Listing,
    // True when this copy came from the archive rather than the live listing already in memory —
    // shown as a banner, since "removed from the platform" is worth knowing before "open on site"
    // is tapped and does nothing.
    isArchived: Boolean = false,
    onDismiss: () -> Unit,
) {
    // eBay says where a thing is on the item page and nowhere on the card it was found through, so
    // a listing that arrived without a location is asked about once, here, where someone is looking
    // at that one listing. Markets that publish a location on the card never reach this.
    val client = remember { ArbayClient() }
    var fetchedLocation by remember(listing.id) { mutableStateOf<Location?>(null) }
    var lookingUpLocation by remember(listing.id) { mutableStateOf(false) }
    LaunchedEffect(listing.id) {
        // An off-screen render must fire no network call, and an archived copy is being read for
        // what it said when it was crawled, not for where the seller stands today.
        if (listing.location != null || isArchived || listing.url.isBlank()) return@LaunchedEffect
        lookingUpLocation = true
        fetchedLocation = client.listingLocation(listing)
        lookingUpLocation = false
    }

    AdaptiveSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                listing.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (isArchived) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Archive, null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            "No longer on ${listing.platformId.displayName} — showing your saved copy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            if (listing.imageUrls.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listing.imageUrls) { url ->
                        AsyncImage(
                            model = imageModel(url),
                            contentDescription = listing.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 260.dp, height = 200.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    listing.comparablePrice.format(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                listing.oldPrice?.let {
                    Text(
                        it.format(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough,
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailChip(listing.platformId.displayName)
                listing.condition?.let { DetailChip(it.name.lowercase().replace('_', ' ')) }
                val place = listing.location ?: fetchedLocation
                place?.let { loc ->
                    listOfNotNull(loc.zip, loc.city ?: loc.raw ?: loc.country)
                        .joinToString(" ").takeIf { it.isNotBlank() }?.let { DetailChip(it) }
                }
                if (place == null && lookingUpLocation) DetailChip("looking up where it is\u2026")
                listing.distanceKm?.let { DetailChip("${it.roundToInt()} km away") }
                listing.listingDate?.let {
                    DetailChip("posted ${it.toLocalDateTime(TimeZone.currentSystemDefault()).date}")
                }
                listing.soldDate?.let {
                    DetailChip("sold ${it.toLocalDateTime(TimeZone.currentSystemDefault()).date}")
                }
                if (listing.saleType == SaleType.AUCTION) {
                    DetailChip(listing.bidCount?.let { "$it bids" } ?: "auction")
                }
                if (listing.shipping?.free == true) DetailChip("Free shipping")
                if (listing.negotiable) DetailChip("Negotiable")
            }

            // What the price really is where it is being read: a market outside the buyer's VAT
            // area quotes without the import VAT charged on the way in, and the card's figure is
            // the landed one, so the difference is spelled out rather than left as a discrepancy
            // between this screen and the market's own page.
            listing.importVat(ImportRules.current)?.let { vat ->
                Text(
                    "Includes ${ImportRules.current.importVatPercent}% import VAT of ${vat.format()} — " +
                        "${listing.price.format()} on ${listing.platformId.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            listing.shipping?.cost?.takeIf { it.amount > 0 }?.let {
                Text(
                    "Delivery ${it.format()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Everything the market published about the thing itself. It was all being carried and
            // none of it shown: a van's own page said only its registration month and mileage,
            // pulled out of a description string, while its year, power, gearbox, fuel, body,
            // doors, seats, emission class, colour and inspection date sat in the record unread.
            listing.vehicle?.let { v ->
                val specs = buildList {
                    v.firstRegYear?.let {
                        add("First registered" to (v.firstRegMonth?.let { m -> "%02d/%d".format(m, it) } ?: "$it"))
                    }
                    v.mileageKm?.let { add("Mileage" to "${"%,d".format(it).replace(',', '.')} km") }
                    v.powerKw?.let { add("Power" to "$it kW · ${(it * 1.35962).toInt()} hp") }
                    v.displacementCc?.let { add("Engine" to "$it cc") }
                    v.fuel?.let { add("Fuel" to it.name.lowercase().replace('_', ' ')) }
                    v.gearbox?.let { add("Gearbox" to it.name.lowercase()) }
                    v.drivetrain?.let { add("Drive" to it.name.lowercase().replace('_', ' ')) }
                    v.bodyType?.let { add("Body" to it.name.lowercase().replace('_', ' ')) }
                    v.doors?.let { add("Doors" to "$it") }
                    v.seats?.let { add("Seats" to "$it") }
                    v.condition?.let { add("Condition" to it.name.lowercase().replace('_', ' ')) }
                    v.previousOwners?.let { add("Previous owners" to "$it") }
                    v.color?.let { add("Colour" to it) }
                    v.emissionClassEuro?.let { add("Emission class" to "Euro $it") }
                    v.emissionSticker?.let { add("Sticker" to "$it") }
                    v.inspectionUntil?.let { add("Inspection until" to it) }
                    v.upholstery?.let { add("Upholstery" to it) }
                    v.vanLength?.let { add("Length" to "L$it") }
                    v.vanHeight?.let { add("Roof" to "H$it") }
                    v.wheelbaseMm?.let { add("Wheelbase" to "$it mm") }
                }
                if (specs.isNotEmpty()) {
                    Text("What the market says it is", style = MaterialTheme.typography.labelLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        specs.forEach { (label, value) ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(value, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            listing.seller?.let { seller ->
                Text(
                    "Sold by ${seller.name}" + (seller.rating?.let { " · ${"%.1f".format(it)}★" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            listing.description?.takeIf { it.isNotBlank() }?.let {
                Text("Description", style = MaterialTheme.typography.labelLarge)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { openBrowser(listing.url) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isArchived) "Try the original page" else "Open on ${listing.platformId.displayName}")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DetailChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
