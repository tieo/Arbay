package io.github.tieo.arbay.ui.screen
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.tieo.arbay.ImportRules
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.model.importVat
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.rememberCoordDetector
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.READABLE_WIDTH
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import io.github.tieo.arbay.ui.viewmodel.PlatformStatus
import io.github.tieo.arbay.model.SortMode
import androidx.compose.ui.geometry.Size
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Human age of a listing from its posting date ("today", "3 days ago", …); null if in the future
 *  or the date is implausible. */
private fun ageLabel(posted: Instant): String? {
    val days = (Clock.System.now() - posted).inWholeDays
    return when {
        days < 0 -> null
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} wk ago"
        days < 365 -> "${days / 30} mo ago"
        else -> "${days / 365} yr ago"
    }
}

/** The country a listing is sourced from, as ISO-2, for the cross-border origin badge. Prefers the
 *  listing's own location (multi-country platforms like AutoScout24 mix markets), else the platform's
 *  home country. Returns null for the home market (DE), which gets no badge, and for unknown origins. */

/** The country a listing is sourced from, as ISO-2, for the cross-border origin badge. Prefers the
 *  listing's own location (multi-country platforms like AutoScout24 mix markets), else the platform's
 *  home country. Returns null for the home market (DE), which gets no badge, and for unknown origins. */
private fun originCountry(listing: Listing): String? {
    val iso = listing.location?.country?.let { normalizeCountry(it) } ?: listing.platformId.country
    return iso?.uppercase()?.takeUnless { it == "DE" }
}

/** AutoScout24 single-letter codes and German/English country names → ISO-2. */

/** Two-letter ISO country code → its flag emoji (regional-indicator pair). "DK" → 🇩🇰.
 *  Each letter maps to a code point above U+FFFF, so it's emitted as a UTF-16 surrogate pair. */
internal fun flagEmoji(cc: String): String {
    if (cc.length != 2) return ""
    return buildString {
        for (c in cc.uppercase()) {
            if (c !in 'A'..'Z') return ""
            val cp = 0x1F1E6 + (c - 'A')
            val offset = cp - 0x10000
            append((0xD800 + (offset shr 10)).toChar())
            append((0xDC00 + (offset and 0x3FF)).toChar())
        }
    }
}

/**
 * One line of metadata: what the listing is, what it states, and how much it never stated.
 *
 * Condition, specs and the unchecked criteria used to be three stacked lines of a few words each,
 * which spent more of the card on gaps than on the listing. The specs wrap when there are many,
 * and the unchecked count sits at the end as a quiet pill that opens the detail.
 */
@Composable
private fun MetaLine(
    source: String,
    sold: Boolean,
    condition: Condition?,
    vehicle: VehicleInfo?,
    unchecked: List<String>,
) {
    val v = vehicle
    val specs = buildList {
        add(source to true)
        if (sold) add("sold" to true)
        condition?.let {
            add(it.name.lowercase().replaceFirstChar { c -> c.uppercase() }.replace("_", " ") to true)
        }
        if (v != null) {
            v.firstRegYear?.let {
                val ym = if (v.firstRegMonth != null) "%02d/%d".format(v.firstRegMonth, it) else it.toString()
                add(ym to v.isVerified(VehicleField.FIRST_REG_YEAR))
            }
            v.mileageKm?.let { add("${"%,d".format(it)} km" to v.isVerified(VehicleField.MILEAGE)) }
            v.powerKw?.let { add("$it kW" to v.isVerified(VehicleField.POWER)) }
            v.gearbox?.let {
                add((if (it == Transmission.AUTOMATIC) "Automatik" else "Schaltgetriebe") to
                    v.isVerified(VehicleField.GEARBOX))
            }
            v.fuel?.takeIf { it != Fuel.OTHER }?.let {
                add(it.name.lowercase().replaceFirstChar { c -> c.uppercase() } to v.isVerified(VehicleField.FUEL))
            }
            val vanCode = buildString {
                v.vanLength?.let { append("L$it") }
                v.vanHeight?.let { append("H$it") }
            }
            if (vanCode.isNotEmpty()) {
                add(vanCode to v.isVerified(
                    if (v.vanLength != null) VehicleField.VAN_LENGTH else VehicleField.VAN_HEIGHT,
                ))
            }
        }
    }
    var explaining by remember { mutableStateOf(false) }
    Spacer(Modifier.height(2.dp))
    // One line that never wraps: the specs shorten, the count stays. Left to wrap, a card ran to
    // six lines and the next one to three, and a list of them reads as chaos rather than as a list.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (specs.isNotEmpty()) {
            Text(
                specs.joinToString(" · ") { (text, verified) -> if (verified) text else "~$text" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (unchecked.isNotEmpty()) {
            Surface(
                onClick = { explaining = true },
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    "${unchecked.size} unchecked",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
    if (explaining) UncheckedDialog(unchecked) { explaining = false }
}

/** The criteria this listing was never tested against, as the list it is, with one line of why. */
@Composable
private fun UncheckedDialog(unchecked: List<String>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Never checked", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    unchecked.forEach { name ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                Text(
                    "This market never published them, so the listing was kept rather than dropped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
internal fun ListingCard(
    listing: Listing,
    onBan: (() -> Unit)? = null,
    onBlockWord: ((String) -> Unit)? = null,
    searchQuery: String = "",
    // The vehicle criteria in force, so a listing can say which of them it was never checked
    // against: a market that publishes no power and no gearbox cannot be filtered by either, and
    // the listing is here on sufferance rather than on merit.
    carFilters: CarFilters? = null,
    modifier: Modifier = Modifier,
) {
    var showBlockDialog by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }

    // A flat, tappable row on the sheet surface, separated by a hairline divider — not a filled card
    // per item, which reads as clutter across a long list. The price is the strongest element.
    Surface(
        onClick = { showDetail = true },
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
      Column {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Thumbnail
            // http for a listing off a market, a path for one drawn off-screen: both are
            // things the image loader can fetch, and the card only needs there to be one.
            val firstImage = listing.imageUrls.firstOrNull { it.isNotBlank() }
            if (firstImage != null) {
                val thumbnail = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                val already = io.github.tieo.arbay.ui.LocalPreloadedImages.current(firstImage)
                if (already != null) {
                    androidx.compose.foundation.Image(
                        painter = already,
                        contentDescription = listing.title,
                        modifier = thumbnail,
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    AsyncImage(
                        model = io.github.tieo.arbay.imageModel(firstImage),
                        contentDescription = listing.title,
                        modifier = thumbnail,
                        contentScale = ContentScale.Crop,
                    )
                }
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                // The title leads and the price sits beside it. A source pill on a line of its own
                // left a band of empty card across every row and put three differently shaped tags
                // at three different heights.
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        listing.title.tidyTitle(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            listing.comparablePrice.format(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (listing.sold) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        // The price above includes import VAT where it is due, which is not what
                        // the market's own page will say — so say what was added and what they ask.
                        val vat = listing.importVat(ImportRules.current)
                        if (vat != null) {
                            Text(
                                "incl. ${ImportRules.current.importVatPercent}% import VAT",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Text(
                                "${listing.effectivePrice.format()} there",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        val shippingCost = listing.shipping?.cost
                        when {
                            shippingCost != null -> Text(
                                "+ ${shippingCost.format()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            listing.shipping?.free == true -> Text(
                                "free shipping",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Source, condition and specs on one line, in the same quiet type: the source is
                // metadata like the rest of it, not a badge.
                val origin = originCountry(listing)
                MetaLine(
                    source = if (origin != null) "${listing.platformId.displayName} ${flagEmoji(origin)}"
                    else listing.platformId.displayName,
                    sold = listing.sold,
                    condition = listing.condition?.takeIf { !listing.sold },
                    vehicle = listing.vehicle,
                    unchecked = carFilters?.uncheckedFor(listing.vehicle).orEmpty(),
                )

                // Where it is, how old it is, how well it fits: one line, because three lines of
                // two words each is what turned a list of vans into a wall.
                val foot = buildList {
                    listing.location?.let { loc ->
                        val place = loc.raw ?: listOfNotNull(loc.zip, loc.city).joinToString(" ")
                        if (place.isNotBlank()) add(place)
                    }
                    listing.distanceKm?.let { add("${it.roundToInt()} km away") }
                    listing.listingDate?.let { posted -> ageLabel(posted)?.let { add(it) } }
                    listing.matchScore?.let { add("${(it * 100).roundToInt()}% match") }
                }
                if (foot.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        foot.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // The old price, when the market shows one, is the only thing left for this column:
            // the price itself now sits beside the title.
            listing.oldPrice?.let {
                Spacer(Modifier.width(4.dp))
                Text(
                    it.format(),
                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (onBan != null || onBlockWord != null) {
                Column {
                    if (onBan != null) {
                        IconButton(
                            onClick = onBan,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Outlined.DeleteOutline, "Hide this listing",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                    if (onBlockWord != null) {
                        IconButton(
                            onClick = { showBlockDialog = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Block, "Block a word from this listing",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
        )
      }
    }

    if (showBlockDialog && onBlockWord != null) {
        BlockTermDialog(
            listingTitle = listing.title,
            searchQuery = searchQuery,
            onBlock = { term ->
                onBlockWord(term)
                showBlockDialog = false
            },
            onDismiss = { showBlockDialog = false },
        )
    }
    if (showDetail) {
        ListingDetailSheet(listing = listing, onDismiss = { showDetail = false })
    }
}

@Composable
internal fun BlockTermDialog(
    listingTitle: String,
    searchQuery: String,
    onBlock: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // The query's own words are not offered as block candidates; blocking "crafter" would empty the list.
    val queryWords = remember(searchQuery) {
        searchQuery.lowercase().split(Regex("[\\s\\-]+"))
            .filter { it.length > 1 && !it.startsWith("-") && it != "or" }
            .toSet()
    }
    val candidateWords = remember(listingTitle, queryWords) {
        // Split on every run of non-letters, so punctuation separates words instead of vanishing
        // between them: "OVP!Lagerverkauf" is two words, not one unblockable "ovplagerverkauf".
        listingTitle.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .map { it.lowercase() }
            .distinct()
            .filter { word -> queryWords.none { q -> word.contains(q) || q.contains(word) } }
    }

    var phraseMode by remember { mutableStateOf(false) }
    val selectedWords = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block a word", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                if (phraseMode && selectedWords.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Text(
                            selectedWords.joinToString(" "),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                Text(
                    if (phraseMode) "Tap words to add to phrase:" else "Tap to block. Long press for phrase:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    candidateWords.forEach { word ->
                        val isSelected = word in selectedWords
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                                .pointerInput(word, phraseMode) {
                                    detectTapGestures(
                                        onLongPress = {
                                            phraseMode = true
                                            selectedWords.clear()
                                            selectedWords.add(word)
                                        },
                                        onTap = {
                                            if (phraseMode) {
                                                if (isSelected) selectedWords.remove(word)
                                                else if (word !in selectedWords) selectedWords.add(word)
                                            } else {
                                                onBlock(word)
                                            }
                                        },
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                word,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (phraseMode && selectedWords.size >= 2) {
                TextButton(onClick = {
                    onBlock(selectedWords.joinToString(" "))
                }) { Text("Block phrase") }
            }
        },
        dismissButton = {
            Row {
                if (phraseMode) {
                    TextButton(onClick = { phraseMode = false; selectedWords.clear() }) { Text("Back") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// === Price distribution histogram (active listings, New vs Used bars) ===
