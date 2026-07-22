package io.github.tieo.arbay.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.ui.screen.*
import java.io.File

/** A gallery of the app's result-view building blocks rendered with sample data, so the design can
 *  be reviewed as a set of PNGs and iterated against the UI rules. Each view is rendered light+dark. */
private fun median(values: List<Long>): Money? =
    values.sorted().let { if (it.isEmpty()) null else Money(it[it.size / 2], Currency.EUR) }

@Composable
private fun ResultsBody() {
    val active = SampleData.active
    val newL = active.filter { it.condition?.name == "NEW" }
    val usedL = active.filter { it.condition?.name != "NEW" }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PriceOverview(
                minPrice = Money(active.minOf { it.price.amount }, Currency.EUR),
                medianPrice = median(active.map { it.price.amount }),
                maxPrice = Money(active.maxOf { it.price.amount }, Currency.EUR),
                minNewPrice = newL.minOfOrNull { it.price.amount }?.let { Money(it, Currency.EUR) },
                medianNewPrice = median(newL.map { it.price.amount }),
                newCount = newL.size,
                minUsedPrice = usedL.minOfOrNull { it.price.amount }?.let { Money(it, Currency.EUR) },
                medianUsedPrice = median(usedL.map { it.price.amount }),
                usedCount = usedL.size,
                conditionFilter = null,
                onConditionFilterChange = {},
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            PriceDistributionChart(
                newListings = newL,
                usedListings = usedL,
                soldListings = SampleData.sold,
                medianNewPrice = median(newL.map { it.price.amount })?.amount,
                medianUsedPrice = median(usedL.map { it.price.amount })?.amount,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            active.take(6).forEach { listing: Listing ->
                ListingCard(listing = listing, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

private val PHONE_W = 390
private val PHONE_H = 1600

/** Every top-level view rendered inline (the sheets normally wrap in a Dialog, which an off-screen
 *  scene cannot capture; LocalRenderInline makes them paint in place). */
private val VIEWS: List<Pair<String, @Composable () -> Unit>> = listOf(
    "results" to { ResultsBody() },
    "search" to {
        androidx.compose.runtime.CompositionLocalProvider(io.github.tieo.arbay.ui.LocalRenderInline provides true) {
            io.github.tieo.arbay.ui.screen.DiscoverySheet(
                onDismiss = {}, onProductSelected = {}, onCustomSearch = {},
                onLiveSearch = {}, onFreeItems = {}, onCarSearch = {},
            )
        }
    },
    "car-search" to {
        androidx.compose.runtime.CompositionLocalProvider(io.github.tieo.arbay.ui.LocalRenderInline provides true) {
            io.github.tieo.arbay.ui.screen.CarSearchSheet(onDismiss = {}, onSearch = { _, _, _, _, _, _ -> })
        }
    },
    "settings" to {
        androidx.compose.runtime.CompositionLocalProvider(io.github.tieo.arbay.ui.LocalRenderInline provides true) {
            io.github.tieo.arbay.ui.screen.SettingsSheet(client = io.github.tieo.arbay.api.ArbayClient(), onDismiss = {})
        }
    },
)

fun main() {
    val outDir = File(System.getProperty("gallery.out") ?: "build/gallery")
    for ((name, view) in VIEWS) {
        for (dark in listOf(false, true)) {
            val suffix = if (dark) "dark" else "light"
            runCatching {
                renderToPng("$name-$suffix", PHONE_W, PHONE_H, dark = dark, outDir = outDir, content = view)
            }.onFailure { println("FAILED $name-$suffix: ${it.message}") }
        }
    }
    println("gallery written to ${outDir.absolutePath}")
}
