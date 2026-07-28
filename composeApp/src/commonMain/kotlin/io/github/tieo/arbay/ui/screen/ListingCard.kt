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
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.rememberCoordDetector
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.openBrowser
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

@Composable
private fun VehicleSpecsRow(v: VehicleInfo) {
    data class Spec(val text: String, val field: VehicleField)
    val specs = buildList {
        v.firstRegYear?.let {
            val ym = if (v.firstRegMonth != null) "%02d/%d".format(v.firstRegMonth, it) else it.toString()
            add(Spec(ym, VehicleField.FIRST_REG_YEAR))
        }
        v.mileageKm?.let { add(Spec("${"%,d".format(it)} km", VehicleField.MILEAGE)) }
        v.powerKw?.let { add(Spec("$it kW", VehicleField.POWER)) }
        v.gearbox?.let {
            val g = if (it == Transmission.AUTOMATIC) "Automatik" else "Schaltgetriebe"
            add(Spec(g, VehicleField.GEARBOX))
        }
        v.fuel?.let { add(Spec(it.name.lowercase().replaceFirstChar { c -> c.uppercase() }, VehicleField.FUEL)) }
        // Van size code: verified when the listing stated an explicit L/H, inferred from a
        // roof/wheelbase word otherwise. Uses the length field's verification for the marker.
        val vanCode = buildString {
            v.vanLength?.let { append("L$it") }
            v.vanHeight?.let { append("H$it") }
        }
        if (vanCode.isNotEmpty()) {
            val field = if (v.vanLength != null) VehicleField.VAN_LENGTH else VehicleField.VAN_HEIGHT
            add(Spec(vanCode, field))
        }
    }
    if (specs.isEmpty()) return
    Spacer(Modifier.height(3.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        specs.take(5).forEach { spec ->
            val verified = v.isVerified(spec.field)
            Text(
                if (verified) spec.text else "~${spec.text}",
                style = MaterialTheme.typography.labelSmall,
                color = if (verified) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ListingCard(
    listing: Listing,
    onBan: (() -> Unit)? = null,
    onBlockWord: ((String) -> Unit)? = null,
    searchQuery: String = "",
    modifier: Modifier = Modifier,
) {
    var showBlockDialog by remember { mutableStateOf(false) }

    // A flat, tappable row on the sheet surface, separated by a hairline divider — not a filled card
    // per item, which reads as clutter across a long list. The price is the strongest element.
    Surface(
        onClick = { openBrowser(listing.url) },
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
      Column {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            // http for a listing off a market, a path for one drawn off-screen: both are
            // things the image loader can fetch, and the card only needs there to be one.
            val firstImage = listing.imageUrls.firstOrNull { it.isNotBlank() }
            if (firstImage != null) {
                AsyncImage(
                    model = io.github.tieo.arbay.imageModel(firstImage),
                    contentDescription = listing.title,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                // One neutral source tag (platform + optional origin flag). Condition and location
                // are quiet metadata below the title, not more coloured pills.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val origin = originCountry(listing)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            if (origin != null) "${listing.platformId.displayName} ${flagEmoji(origin)}"
                            else listing.platformId.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (listing.sold) {
                        Text(
                            "Sold",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    listing.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Condition as quiet metadata, not a coloured pill.
                listing.condition?.takeIf { !listing.sold }?.let {
                    Text(
                        it.name.lowercase().replaceFirstChar { c -> c.uppercase() }.replace("_", " "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                listing.vehicle?.let { VehicleSpecsRow(it) }

                // Semantic fit to the searcher's ideal-car description, when they gave one.
                listing.matchScore?.let { score ->
                    Spacer(Modifier.height(3.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            "${(score * 100).roundToInt()}% match",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                listing.location?.let { loc ->
                    val locText = loc.raw ?: listOfNotNull(loc.zip, loc.city).joinToString(" ")
                    // Distance from the searcher, when the search carried the device position.
                    val distText = listing.distanceKm?.let { "${it.roundToInt()} km away" }
                    val text = listOfNotNull(locText.takeIf { it.isNotBlank() }, distText).joinToString(" · ")
                    if (text.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.LocationOn, null,
                                modifier = Modifier.size(11.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // How old the ad is, when the platform exposes a posting date.
                listing.listingDate?.let { posted ->
                    ageLabel(posted)?.let { label ->
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Schedule, null,
                                modifier = Modifier.size(11.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    listing.effectivePrice.format(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (listing.sold) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                // Shipping breakdown when a cost is known, otherwise a free-shipping note.
                val shippingCost = listing.shipping?.cost
                val isFreeShipping = listing.shipping?.free == true
                if (shippingCost != null) {
                    Text(
                        "${listing.price.format()} + ${shippingCost.format()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (isFreeShipping) {
                    Text(
                        "Free shipping",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                listing.oldPrice?.let {
                    Text(
                        it.format(),
                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
