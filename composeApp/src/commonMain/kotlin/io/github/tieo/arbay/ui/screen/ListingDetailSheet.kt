package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
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
import io.github.tieo.arbay.ImportRules
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.imageModel
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.model.Location
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.VehicleField
import io.github.tieo.arbay.model.importVat
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.ui.AdaptiveSheet
import kotlin.math.roundToInt
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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
    var fromItsOwnPage by remember(listing.id) { mutableStateOf<ListingDetail?>(null) }
    var readingItsPage by remember(listing.id) { mutableStateOf(false) }
    LaunchedEffect(listing.id) {
        // A card is the market's summary of the ad; this sheet is the ad. The page is read once,
        // here, for the listing being looked at. An archived copy is read for what it said when it
        // was crawled, and an off-screen render must fire no network call at all.
        if (isArchived || listing.url.isBlank()) return@LaunchedEffect
        readingItsPage = true
        fromItsOwnPage = client.listingDetail(listing)
        readingItsPage = false
    }
    val vehicle = fromItsOwnPage?.vehicle?.let { fromPage ->
        listing.vehicle?.let { card ->
            card.copy(
                wheelbaseMm = card.wheelbaseMm ?: fromPage.wheelbaseMm,
                verified = card.verified + fromPage.verified,
            )
        } ?: fromPage
    } ?: listing.vehicle
    val description = fromItsOwnPage?.description
        ?.takeIf { it.length > (listing.description?.length ?: 0) } ?: listing.description

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
                val place = listing.location ?: fromItsOwnPage?.location
                place?.let { loc ->
                    listOfNotNull(loc.zip, loc.city ?: loc.raw ?: loc.country)
                        .joinToString(" ").takeIf { it.isNotBlank() }?.let { DetailChip(it) }
                }
                if (place == null && readingItsPage) DetailChip("reading its page\u2026")
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
            vehicle?.let { v ->
                // Each row carries the field it came from, so a value the market stated and a value
                // read out of its words are not written the same way: "Lang" in a title became a
                // flat "Length L3" here, which is a guess about one maker's naming printed as fact.
                fun MutableList<Triple<String, String, VehicleField?>>.spec(
                    label: String,
                    value: String,
                    field: VehicleField? = null,
                ) = add(Triple(label, value, field))
                val specs = buildList {
                    v.firstRegYear?.let {
                        spec("First registered", v.firstRegMonth?.let { m -> "%02d/%d".format(m, it) } ?: "$it", VehicleField.FIRST_REG_YEAR)
                    }
                    v.mileageKm?.let { spec("Mileage", "${"%,d".format(it).replace(',', '.')} km", VehicleField.MILEAGE) }
                    v.powerKw?.let { spec("Power", "$it kW · ${(it * 1.35962).toInt()} hp", VehicleField.POWER) }
                    v.displacementCc?.let { spec("Engine", "$it cc", VehicleField.DISPLACEMENT) }
                    v.fuel?.let { spec("Fuel", it.name.lowercase().replace('_', ' '), VehicleField.FUEL) }
                    v.gearbox?.let { spec("Gearbox", it.name.lowercase(), VehicleField.GEARBOX) }
                    v.drivetrain?.let { spec("Drive", it.name.lowercase().replace('_', ' '), VehicleField.DRIVETRAIN) }
                    v.bodyType?.let { spec("Body", it.name.lowercase().replace('_', ' '), VehicleField.BODY_TYPE) }
                    v.doors?.let { spec("Doors", "$it", VehicleField.DOORS) }
                    v.seats?.let { spec("Seats", "$it", VehicleField.SEATS) }
                    v.condition?.let { spec("Condition", it.name.lowercase().replace('_', ' '), VehicleField.CONDITION) }
                    v.previousOwners?.let { spec("Previous owners", "$it") }
                    v.color?.let { spec("Colour", it, VehicleField.COLOR) }
                    v.emissionClassEuro?.let { spec("Emission class", "Euro $it", VehicleField.EMISSION) }
                    v.emissionSticker?.let { spec("Sticker", "$it", VehicleField.EMISSION_STICKER) }
                    v.inspectionUntil?.let { spec("Inspection until", it, VehicleField.INSPECTION) }
                    v.upholstery?.let { spec("Upholstery", it, VehicleField.UPHOLSTERY) }
                    v.vanLength?.let { spec("Length", "L$it", VehicleField.VAN_LENGTH) }
                    v.vanHeight?.let { spec("Roof", "H$it", VehicleField.VAN_HEIGHT) }
                    v.wheelbaseMm?.let { spec("Wheelbase", "$it mm", VehicleField.WHEELBASE) }
                }
                if (specs.isNotEmpty()) {
                    Text("What the market says it is", style = MaterialTheme.typography.labelLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        specs.forEach { (label, value, field) ->
                            val stated = field == null || v.isVerified(field)
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        if (stated) value else "~$value",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (stated) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (!stated) {
                                        Text(
                                            "read out of the words, not stated",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
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

            description?.takeIf { it.isNotBlank() }?.let {
                Text("Description", style = MaterialTheme.typography.labelLarge)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            // The card's description is the market's one-line summary of the ad; the ad itself is
            // on its own page, which is being read while this is on screen.
            if (readingItsPage) {
                Text(
                    "Reading the rest off the ad\u2026",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { openBrowser(listing.url) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.size(18.dp))
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
