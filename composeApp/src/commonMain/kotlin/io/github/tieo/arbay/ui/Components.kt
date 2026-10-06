package io.github.tieo.arbay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.tieo.arbay.format
import io.github.tieo.arbay.imageModel
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.results.PriceSummary

/** Pictures already in hand, by address, for drawing a screen where nothing can be downloaded. */
val LocalPreloadedImages = compositionLocalOf<(String) -> Painter?> { { null } }

/** A photo off a market, or an empty frame where there is none or it is gone. */
@Composable
fun Photo(url: String?, modifier: Modifier = Modifier, description: String? = null, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(arbay.raised), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) {
            Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            return@Box
        }
        val already = LocalPreloadedImages.current(url)
        if (already != null) Image(already, description, Modifier.matchParentSizeCompat(), contentScale = contentScale)
        else AsyncImage(model = imageModel(url), contentDescription = description, modifier = Modifier.matchParentSizeCompat(), contentScale = contentScale)
    }
}

private fun Modifier.matchParentSizeCompat() = this.fillMaxWidth().fillMaxHeight()

/** A price as every screen shows it. [lowest] marks the cheapest offer: its own colour and a rule
 *  beneath, so it stays apart without colour. */
@Composable
fun PriceText(money: Money, modifier: Modifier = Modifier, lowest: Boolean = false, style: TextStyle = MaterialTheme.typography.titleMedium) {
    val base = arbay.priceStyle(style).copy(fontWeight = if (lowest) FontWeight.Bold else FontWeight.SemiBold)
    Text(
        money.format(),
        modifier = modifier,
        style = if (lowest) base.copy(color = arbay.lowest, textDecoration = TextDecoration.Underline) else base,
        maxLines = 1,
    )
}

/** The spread of prices as bars, cheapest at the left; the cheapest bar stands apart. */
@Composable
fun Spread(summary: PriceSummary, modifier: Modifier = Modifier, height: Dp = 32.dp) {
    val peak = (summary.spread.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        summary.spread.forEachIndexed { index, count ->
            val share = if (count == 0) 0.06f else 0.2f + 0.8f * count / peak
            Box(
                Modifier.weight(1f).fillMaxHeight(share)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(if (index == 0 && count > 0) arbay.lowest else arbay.line),
            )
        }
    }
}

/** A choice that is on or off by itself; [opens] marks one that opens a list to pick from. */
@Composable
fun Choice(text: String, on: Boolean, opens: Boolean = false, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(text, maxLines = 1) },
        trailingIcon = if (opens) ({ Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) }) else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** Choices that wrap onto as many lines as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Choices(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }
}

/** A fact as a small tag. */
@Composable
fun Fact(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clip(CircleShape).background(arbay.raised).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** How many of something are new, as a count that stands out. */
@Composable
fun Count(n: Int, modifier: Modifier = Modifier) {
    Text(
        "$n",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** The heading over a group of rows. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** A quieter line under something. */
@Composable
fun Muted(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodySmall, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text, modifier, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** A card with the look's outline instead of a shadow. */
@Composable
fun Panel(modifier: Modifier = Modifier, highlighted: Boolean = false, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (highlighted) MaterialTheme.colorScheme.primary else arbay.line, MaterialTheme.shapes.medium)
            .padding(arbay.pad),
        verticalArrangement = Arrangement.spacedBy(arbay.gap / 2),
    ) { content() }
}

/** Where a screen has nothing to show, saying why in a sentence. */
@Composable
fun Nothing(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted(text, style = MaterialTheme.typography.bodyMedium)
        action?.invoke()
    }
}

/** A label above the value it names. */
@Composable
fun Figure(label: String, value: String, modifier: Modifier = Modifier, lowest: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle(label)
        Text(
            value,
            style = arbay.priceStyle(MaterialTheme.typography.titleMedium),
            color = if (lowest) arbay.lowest else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun Gap(size: Dp = 8.dp) = Box(Modifier.size(size))

@Composable
fun HGap(size: Dp = 8.dp) = Box(Modifier.width(size))
