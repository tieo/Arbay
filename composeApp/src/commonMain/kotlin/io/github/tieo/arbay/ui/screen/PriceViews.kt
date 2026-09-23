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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.model.SortMode
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.rememberCoordDetector
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.READABLE_WIDTH
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import io.github.tieo.arbay.ui.viewmodel.PlatformStatus
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Median of price amounts (cents, already in the display currency) as Money; null when empty. */
internal fun medianMoney(prices: List<Long>, currency: Currency): Money? {
    if (prices.isEmpty()) return null
    val sorted = prices.sorted()
    return Money(sorted[sorted.size / 2], currency)
}

@Composable
internal fun PriceOverview(
    minPrice: Money?,
    medianPrice: Money?,
    minNewPrice: Money?,
    medianNewPrice: Money?,
    newCount: Int,
    minUsedPrice: Money?,
    medianUsedPrice: Money?,
    usedCount: Int,
    maxPrice: Money? = null,
    conditionFilter: String?,
    onConditionFilterChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // One restrained summary: the best price is the single loud number (accent); the median and
        // upper bound sit beside it as quiet reference. No competing colour blocks, one statistic
        // per figure (median only — never a mean next to it).
        if (minPrice != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Best price",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            minPrice.format(),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (medianPrice != null) {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Median ${medianPrice.format()}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (maxPrice != null) {
                                Text(
                                    "up to ${maxPrice.format()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // New / Used as compact, neutral filter stats — tap to narrow the list to that condition.
        // Sold is not a stock condition, so it lives on the price chart, not here.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (minNewPrice != null) {
                ConditionStat("New", minNewPrice, newCount, conditionFilter == "NEW",
                    { onConditionFilterChange("NEW") }, Modifier.weight(1f))
            }
            if (minUsedPrice != null) {
                ConditionStat("Used", minUsedPrice, usedCount, conditionFilter == "USED",
                    { onConditionFilterChange("USED") }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ConditionStat(
    label: String,
    from: Money,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, accent) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "from ${from.format()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// === Individual listing card ===

/** Key vehicle specs under a car listing's title. A verified value (from the site's structured
 *  data) is shown solid; an inferred one (guessed from text) is muted and prefixed "~" so the
 *  user can tell confirmed specs from guesses. */

@Composable
internal fun PriceDistributionChart(
    newListings: List<Listing>,
    usedListings: List<Listing>,
    soldListings: List<Listing> = emptyList(),
    medianNewPrice: Long?,
    medianUsedPrice: Long?,
    // The one shared New/Used selection (null = both); the chart dims to match the listing filter.
    conditionFilter: String? = null,
    onSearchSold: () -> Unit = {},
    soldLoading: Boolean = false,
    // No market in this search publishes what sold, so there is no history to switch to.
    soldPossible: Boolean = true,
    onBan: ((Listing) -> Unit)? = null,
    onBlockWord: ((String) -> Unit)? = null,
    searchQuery: String = "",
    modifier: Modifier = Modifier,
) {
    // New/Used comes from the one condition filter shared with the listing cards, so the chart and
    // the list never disagree. Sold is not a condition a listing is in — it is price history, so it
    // is the chart's own mode and nothing else responds to it.
    var showSoldHistory by remember {
        mutableStateOf(newListings.isEmpty() && usedListings.isEmpty() && soldListings.isNotEmpty())
    }
    // Distribution render style, user-switchable: discrete bars or a continuous line over buckets.
    var chartStyle by remember { mutableStateOf("BAR") }

    if (newListings.isEmpty() && usedListings.isEmpty() && soldListings.isEmpty()) return

    val allActive = newListings + usedListings
    val hasActive = allActive.isNotEmpty()
    val allPrices = allActive.map { it.comparablePrice.amount }.sorted()
    // Axis bounds are the 5th/95th percentile, not the extremes: a single dear outlier otherwise
    // stretches the axis so every real listing collapses into one spike at the left. Prices outside
    // the band still count — bucket() clamps them into the first/last bucket.
    val minPriceAll = when {
        allPrices.isEmpty() -> 0L
        allPrices.size >= 12 -> allPrices[(allPrices.size * 0.05f).toInt()]
        else -> allPrices.first()
    }
    val maxPriceAll = when {
        allPrices.isEmpty() -> 0L
        allPrices.size >= 12 -> allPrices[(allPrices.size - 1 - (allPrices.size * 0.05f).toInt()).coerceIn(0, allPrices.size - 1)]
        else -> allPrices.last()
    }
    val priceRange = (maxPriceAll - minPriceAll).coerceAtLeast(500L)
    val bucketCount = 9

    data class BucketData(var newCount: Int = 0, var usedCount: Int = 0)
    val buckets = Array(bucketCount) { BucketData() }
    fun bucket(price: Long) = ((price - minPriceAll).toDouble() / priceRange * bucketCount).toInt().coerceIn(0, bucketCount - 1)
    if (hasActive) {
        newListings.forEach { buckets[bucket(it.comparablePrice.amount)].newCount++ }
        usedListings.forEach { buckets[bucket(it.comparablePrice.amount)].usedCount++ }
    }
    val maxBarCount = buckets.maxOf { maxOf(it.newCount, it.usedCount) }.coerceAtLeast(1)

    var tappedBucket by remember { mutableStateOf<Int?>(null) }

    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header with internal filter chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Price chart",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Bar/line switch, only meaningful for the active distribution (not the sold view).
                    if (!showSoldHistory) {
                        IconButton(
                            onClick = { chartStyle = if (chartStyle == "BAR") "LINE" else "BAR" },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                if (chartStyle == "BAR") Icons.AutoMirrored.Outlined.ShowChart else Icons.Outlined.BarChart,
                                contentDescription = if (chartStyle == "BAR") "Switch to line" else "Switch to bars",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (soldPossible) FilterChip(
                        selected = showSoldHistory,
                        onClick = {
                            val wasSold = showSoldHistory
                            showSoldHistory = !wasSold
                            if (!wasSold && soldListings.isEmpty()) onSearchSold()
                        },
                        label = {
                            if (soldLoading) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                    Text("Sold", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                Text(
                                    if (soldListings.isNotEmpty()) "Sold history (${soldListings.size})" else "Sold history",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = onSurface.copy(alpha = 0.15f),
                            selectedLabelColor = onSurface,
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.height(26.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            if (showSoldHistory) {
                if (soldLoading) {
                    Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (soldListings.isNotEmpty()) {
                    PriceHistoryChart(
                        soldListings = soldListings,
                        onBan = onBan,
                        onBlockWord = onBlockWord,
                        searchQuery = searchQuery,
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onSearchSold) { Text("Search sold listings") }
                    }
                }
            } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .pointerInput(buckets) {
                        detectTapGestures { offset ->
                            val padL = 4f; val padR = 4f
                            val chartW = size.width - padL - padR
                            val bw = chartW / bucketCount
                            val tapped = ((offset.x - padL) / bw).toInt().coerceIn(0, bucketCount - 1)
                            tappedBucket = if (tappedBucket == tapped) null else tapped
                        }
                    },
            ) {
                val padL = 4f; val padR = 4f; val labelH = 16f
                val chartW = size.width - padL - padR
                val chartH = size.height - labelH
                val bw = chartW / bucketCount
                val barW = bw * 0.38f   // each bar takes 38% of bucket width
                val barGap = bw * 0.06f  // gap between new and used bars
                val bucketPad = bw * 0.09f

                val newAlpha = if (conditionFilter == "USED") 0.2f else 1f
                val usedAlpha = if (conditionFilter == "NEW") 0.2f else 1f
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))

                // Draw bars first
                for (i in 0 until bucketCount) {
                    val bucketX = padL + i * bw
                    val newBarH = (buckets[i].newCount.toFloat() / maxBarCount) * chartH
                    val usedBarH = (buckets[i].usedCount.toFloat() / maxBarCount) * chartH

                    if (chartStyle == "BAR") {
                        if (buckets[i].newCount > 0) {
                            drawRect(
                                primary.copy(alpha = newAlpha),
                                topLeft = Offset(bucketX + bucketPad, chartH - newBarH),
                                size = Size(barW, newBarH),
                            )
                        }
                        if (buckets[i].usedCount > 0) {
                            drawRect(
                                tertiary.copy(alpha = usedAlpha),
                                topLeft = Offset(bucketX + bucketPad + barW + barGap, chartH - usedBarH),
                                size = Size(barW, usedBarH),
                            )
                        }
                    }

                    // Tap highlight
                    if (tappedBucket == i) {
                        drawRect(onSurface.copy(alpha = 0.06f), topLeft = Offset(bucketX, 0f), size = Size(bw, chartH))
                    }

                    // X-axis price label every 2nd bucket
                    if (i % 2 == 0 || i == bucketCount - 1) {
                        val price = minPriceAll + (priceRange * i / bucketCount)
                        val labelStr = "\u20AC${price / 100}"
                        val tr = textMeasurer.measure(labelStr, TextStyle(fontSize = 9.sp, color = onSurface.copy(alpha = 0.78f)))
                        val lx = (bucketX + bw / 2 - tr.size.width / 2).coerceIn(0f, size.width - tr.size.width)
                        drawText(tr, topLeft = Offset(lx, size.height - tr.size.height))
                    }
                }

                // Continuous mode: a smoothed line over the per-bucket counts, filled to the axis,
                // one series each for new and used (dimmed by the active New/Used filter, as the bars).
                if (chartStyle == "LINE") {
                    fun seriesLine(count: (Int) -> Int, color: Color, alpha: Float) {
                        if ((0 until bucketCount).all { count(it) == 0 }) return
                        val pts = (0 until bucketCount).map { i ->
                            Offset(padL + i * bw + bw / 2f, chartH - (count(i).toFloat() / maxBarCount) * chartH)
                        }
                        val stroke = Path().apply {
                            moveTo(pts.first().x, pts.first().y)
                            for (i in 1 until pts.size) {
                                val midX = (pts[i - 1].x + pts[i].x) / 2f
                                cubicTo(midX, pts[i - 1].y, midX, pts[i].y, pts[i].x, pts[i].y)
                            }
                        }
                        val area = Path().apply {
                            addPath(stroke)
                            lineTo(pts.last().x, chartH)
                            lineTo(pts.first().x, chartH)
                            close()
                        }
                        drawPath(area, color.copy(alpha = alpha * 0.15f))
                        drawPath(stroke, color.copy(alpha = alpha), style = Stroke(width = 3f))
                    }
                    seriesLine({ buckets[it].newCount }, primary, newAlpha)
                    seriesLine({ buckets[it].usedCount }, tertiary, usedAlpha)
                }

                // Draw median lines ON TOP of bars
                medianNewPrice?.let {
                    if (conditionFilter != "USED") {
                        val x = padL + ((it - minPriceAll).toFloat() / priceRange) * chartW
                        drawLine(primary, Offset(x, 0f), Offset(x, chartH), strokeWidth = 2f, pathEffect = dash)
                    }
                }
                medianUsedPrice?.let {
                    if (conditionFilter != "NEW") {
                        val x = padL + ((it - minPriceAll).toFloat() / priceRange) * chartW
                        drawLine(tertiary, Offset(x, 0f), Offset(x, chartH), strokeWidth = 2f, pathEffect = dash)
                    }
                }
            }

            // Tapped bucket info
            tappedBucket?.let { b ->
                val priceFrom = minPriceAll + (priceRange * b / bucketCount)
                val priceTo = minPriceAll + (priceRange * (b + 1) / bucketCount)
                val inNew = newListings.count { it.comparablePrice.amount in priceFrom..priceTo }
                val inUsed = usedListings.count { it.comparablePrice.amount in priceFrom..priceTo }
                Spacer(Modifier.height(4.dp))
                Text(
                    "\u20AC${priceFrom / 100}–\u20AC${priceTo / 100}: ${if (inNew > 0) "$inNew new" else ""}${if (inNew > 0 && inUsed > 0) " · " else ""}${if (inUsed > 0) "$inUsed used" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Legend: one swatch + count per series, and the dashed marker explained once.
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (newListings.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(9.dp, 9.dp)) { drawRoundRect(primary, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)) }
                        Text("New (${newListings.size})", style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.7f))
                    }
                }
                if (usedListings.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(9.dp, 9.dp)) { drawRoundRect(tertiary, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)) }
                        Text("Used (${usedListings.size})", style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.7f))
                    }
                }
                if (medianNewPrice != null || medianUsedPrice != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(14.dp, 2.dp)) { drawLine(onSurface.copy(alpha = 0.5f), Offset.Zero, Offset(size.width, 0f), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 2f))) }
                        Text("median", style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.5f))
                    }
                }
            }
            } // else (not SOLD)
        }
    }
}

// === Price History Chart (sold price over time) ===

@Composable
internal fun PriceHistoryChart(
    soldListings: List<Listing>,
    onBan: ((Listing) -> Unit)? = null,
    onBlockWord: ((String) -> Unit)? = null,
    searchQuery: String = "",
    modifier: Modifier = Modifier,
) {
    if (soldListings.isEmpty()) return

    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val textMeasurer = rememberTextMeasurer()

    // Every price is converted to the user's display currency, so cross-border results
    // (EUR/PLN/DKK/SEK/CZK) sit on one comparable axis.
    val displayCurrency = Currency.valueOf(DisplayCurrency.current)
    val currencySymbol = when (displayCurrency) {
        Currency.EUR -> "\u20AC"
        Currency.USD -> "$"
        Currency.CHF -> "CHF "
        Currency.GBP -> "\u00A3"
        Currency.PLN -> "z\u0142"
        Currency.CZK -> "K\u010D"
        Currency.DKK, Currency.SEK, Currency.NOK -> "kr"
        Currency.RSD -> "din"
    }
    fun Listing.priceIn() = DisplayCurrency.convert(comparablePrice.amount, comparablePrice.currency.name)

    // A time axis is only honest for listings that carry a real sold date. When too few do,
    // fall back to a price distribution (points ranked by price) rather than faking dates.
    val dated = remember(soldListings) { soldListings.filter { it.soldDate != null }.sortedBy { it.soldDate } }
    val timeMode = dated.size >= 3
    val points = remember(soldListings, timeMode) {
        if (timeMode) dated else soldListings.sortedBy { it.priceIn() }
    }
    val undatedCount = soldListings.size - dated.size

    val prices = points.map { it.priceIn() }
    val minP = prices.min()
    val maxP = prices.max()
    val priceRange = (maxP - minP).coerceAtLeast(100L)

    val timestamps = points.map { (it.soldDate ?: it.scrapedAt).epochSeconds }
    val minT = timestamps.min()
    val maxT = timestamps.max()
    val timeRange = (maxT - minT).coerceAtLeast(1L)
    fun xFrac(i: Int): Float =
        if (timeMode) (timestamps[i] - minT).toFloat() / timeRange
        else if (points.size == 1) 0.5f else i.toFloat() / (points.size - 1)

    var tappedIdx by remember { mutableStateOf<Int?>(null) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                if (timeMode) "Sold price over time (${points.size})"
                else "Sold price distribution (${points.size})",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            if (undatedCount > 0) {
                Text(
                    if (timeMode) "$undatedCount more sold without a date"
                    else "Dates unavailable — showing price spread",
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(6.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .pointerInput(points) {
                        detectTapGestures { offset ->
                            val padL = 48f; val padR = 8f; val padT = 4f; val labelH = 18f
                            val chartW = size.width - padL - padR
                            val chartH = size.height - padT - labelH
                            val tapped = points.indices.minByOrNull { i ->
                                val px = padL + xFrac(i) * chartW
                                val py = padT + chartH - ((prices[i] - minP).toFloat() / priceRange) * chartH
                                val dx = offset.x - px; val dy = offset.y - py
                                dx * dx + dy * dy
                            }
                            tappedIdx = if (tappedIdx == tapped) null else tapped
                        }
                    },
            ) {
                val padL = 48f; val padR = 8f; val padT = 4f; val labelH = 18f
                val chartW = size.width - padL - padR
                val chartH = size.height - padT - labelH

                fun px(i: Int) = padL + xFrac(i) * chartW
                fun py(p: Long) = padT + chartH - ((p - minP).toFloat() / priceRange) * chartH

                // Y-axis price labels in the display currency
                val yTicks = 4
                for (i in 0..yTicks) {
                    val p = minP + priceRange * i / yTicks
                    val y = py(p)
                    drawLine(onSurface.copy(alpha = 0.08f), Offset(padL, y), Offset(size.width - padR, y), strokeWidth = 0.5f)
                    val lbl = "$currencySymbol${p / 100}"
                    val tr = textMeasurer.measure(lbl, TextStyle(fontSize = 9.sp, color = onSurface.copy(alpha = 0.78f)))
                    drawText(tr, topLeft = Offset(0f, y - tr.size.height / 2f))
                }

                // X-axis date labels only in time mode
                if (timeMode) {
                    val xTicks = 4
                    for (i in 0..xTicks) {
                        val t = minT + timeRange * i / xTicks
                        val x = padL + ((t - minT).toFloat() / timeRange) * chartW
                        val date = Instant.fromEpochSeconds(t).toLocalDateTime(TimeZone.currentSystemDefault())
                        val lbl = "${date.dayOfMonth}.${date.monthNumber}"
                        val tr = textMeasurer.measure(lbl, TextStyle(fontSize = 9.sp, color = onSurface.copy(alpha = 0.78f)))
                        drawText(tr, topLeft = Offset((x - tr.size.width / 2).coerceIn(0f, size.width - tr.size.width), size.height - tr.size.height))
                    }
                }

                // Trend: a moving average through the time-ordered points; robust for the
                // small samples we get, and only meaningful when points are dated.
                if (timeMode && points.size >= 3) {
                    val window = maxOf(2, points.size / 6)
                    var prev: Offset? = null
                    for (i in points.indices) {
                        val lo = maxOf(0, i - window); val hi = minOf(points.size - 1, i + window)
                        val avg = (lo..hi).sumOf { prices[it] } / (hi - lo + 1)
                        val pt = Offset(px(i), py(avg))
                        prev?.let { drawLine(primary.copy(alpha = 0.35f), it, pt, strokeWidth = 2.5f) }
                        prev = pt
                    }
                }

                // Data points, coloured by condition
                points.forEachIndexed { i, listing ->
                    val x = px(i)
                    val y = py(prices[i])
                    val isNew = listing.condition == Condition.NEW || listing.condition == null
                    val color = if (isNew) primary else tertiary
                    val isTapped = tappedIdx == i
                    val radius = if (isTapped) 7f else 4.5f
                    drawCircle(color.copy(alpha = 0.85f), radius, Offset(x, y))
                    if (isTapped) drawCircle(color, 2.5f, Offset(x, y))
                }
            }

            // Tapped dot: show the full listing card.
            tappedIdx?.let { i ->
                val listing = points[i]
                Spacer(Modifier.height(4.dp))
                ListingCard(
                    listing = listing,
                    onBan = onBan?.let { { it(listing) } },
                    onBlockWord = onBlockWord,
                    searchQuery = searchQuery,
                )
            }
        }
    }
}

// === Sold history row (compact timeline entry) ===

@Composable
internal fun SoldHistoryRow(
    listing: Listing,
    onBan: (() -> Unit)? = null,
    onBlockWord: ((String) -> Unit)? = null,
    searchQuery: String = "",
    modifier: Modifier = Modifier,
) {
    var showBlockDialog by remember { mutableStateOf(false) }
    val dateStr = remember(listing) {
        val instant = listing.soldDate ?: listing.scrapedAt
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val month = local.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        "${local.dayOfMonth} $month ${local.year}"
    }
    Card(
        onClick = { openBrowser(listing.url) },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Price, most prominent
            Text(
                listing.comparablePrice.format(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(min = 64.dp),
            )
            // Platform chip
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    listing.platformId.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            listing.platformId.country?.let { cc ->
                Text(flagEmoji(cc), style = MaterialTheme.typography.labelSmall)
            }
            listing.condition?.let {
                Text(
                    it.name.lowercase().replaceFirstChar { c -> c.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Title
            Text(
                listing.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Date
            Text(
                dateStr,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onBan != null) {
                IconButton(onClick = onBan, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.DeleteOutline, "Hide this listing",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
            if (onBlockWord != null) {
                IconButton(onClick = { showBlockDialog = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Block, "Block a word from this listing",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
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

// === Crawler status row ===

/**
 * Unified platform chips: crawler status and platform filter in one row. Each chip shows the live
 * search state, then acts as a filter when done. Tapping a chip with results filters to that
 * platform; tapping a failed chip shows its error detail below. The "All" chip is always first.
 */
