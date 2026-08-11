package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.state.ToggleableState
import io.github.tieo.arbay.model.MarketCapability
import io.github.tieo.arbay.model.MarketSets
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.PlatformSearchStatus
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.READABLE_WIDTH
import io.github.tieo.arbay.ui.viewmodel.PlatformStatus

/**
 * What happened at each market, and which of them the results are narrowed to.
 *
 * The server distinguishes a market that was blocked, one that timed out, one holding a captcha and
 * one that simply had nothing. On the results canvas all four looked the same: a shorter list. Here
 * each says what it is, alongside the term it was sent — which is translated per country, so a
 * German search reaches eBay Italy as Italian — and how many results it gave before and after
 * filtering.
 *
 * The picking lives here too. It used to sit in Filters as a second list of the same markets, so
 * the place that said what a market did and the place where you chose it were different screens
 * showing different subsets.
 */
@Composable
fun MarketsSheet(
    statuses: List<PlatformStatus>,
    offers: Map<PlatformId, Int>,
    // What each market can do, so a blank field reads as "this market does not publish that"
    // rather than as a gap in the app.
    capabilities: Map<PlatformId, MarketCapability>,
    // Which markets and countries the results are narrowed to. Empty means every one of them.
    shownMarkets: Set<PlatformId> = emptySet(),
    onShowMarkets: (Set<PlatformId>) -> Unit = {},
    shownCountries: Set<String> = emptySet(),
    onShowCountries: (Set<String>) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val answered = statuses.count { it.status == PlatformSearchStatus.DONE }
    val failed = statuses.count { it.isFailure }
    val narrowed = shownMarkets.isNotEmpty() || shownCountries.isNotEmpty()

    AdaptiveSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Markets",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    buildString {
                        append("$answered of ${statuses.size} answered")
                        if (failed > 0) append(" · $failed could not")
                        append(if (narrowed) " · showing some" else " · showing all")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        }
        LazyColumn(
            modifier = Modifier.widthIn(max = READABLE_WIDTH).fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("what-ticking-does") {
                Text(
                    "Tick as many as you want to narrow the results to them. A country takes every " +
                        "market in it. A market with nothing to give cannot be ticked, and says why.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item("every-market") {
                PickRow(
                    label = "Every market",
                    trailing = "${offers.values.sum()}",
                    state = if (narrowed) ToggleableState.Off else ToggleableState.On,
                    enabled = true,
                    onClick = {
                        onShowMarkets(emptySet())
                        onShowCountries(emptySet())
                    },
                )
            }

            // Grouped by country, and inside a country the failures come first: they are the reason
            // a result list is shorter than it should be.
            val byCountry = statuses.groupBy { s ->
                runCatching { PlatformId.valueOf(s.platformId) }.getOrNull()
                    ?.let { MarketSets.countryOf(it) } ?: "?"
            }
            val ordered = byCountry.entries.sortedWith(
                compareByDescending<Map.Entry<String, List<PlatformStatus>>> { entry ->
                    entry.value.sumOf { offers[runCatching { PlatformId.valueOf(it.platformId) }.getOrNull()] ?: 0 }
                }.thenBy { it.key },
            )
            ordered.forEach { (country, group) ->
                val platformsHere = group.mapNotNull { runCatching { PlatformId.valueOf(it.platformId) }.getOrNull() }
                val withOffers = platformsHere.filter { (offers[it] ?: 0) > 0 }
                val pickedHere = withOffers.count { it in shownMarkets }
                item("country-$country") {
                    PickRow(
                        heading = true,
                        label = country,
                        trailing = group.size.let { if (it == 1) "1 market" else "$it markets" } +
                            " · " + platformsHere.sumOf { offers[it] ?: 0 },
                        state = when {
                            country in shownCountries ||
                                (withOffers.isNotEmpty() && pickedHere == withOffers.size) -> ToggleableState.On
                            pickedHere > 0 -> ToggleableState.Indeterminate
                            else -> ToggleableState.Off
                        },
                        // A country whose markets all came back empty cannot narrow anything.
                        enabled = withOffers.isNotEmpty(),
                        onClick = {
                            val taking = country !in shownCountries && pickedHere < withOffers.size
                            onShowCountries(if (taking) shownCountries + country else shownCountries - country)
                            onShowMarkets(if (taking) shownMarkets else shownMarkets - platformsHere.toSet())
                        },
                    )
                }
                items(
                    group.sortedWith(
                        compareByDescending<PlatformStatus> { it.isFailure }
                            .thenByDescending { offers[runCatching { PlatformId.valueOf(it.platformId) }.getOrNull()] ?: 0 },
                    ),
                ) { status ->
                    val platform = runCatching { PlatformId.valueOf(status.platformId) }.getOrNull()
                    val kept = offers[platform] ?: 0
                    val wholeCountry = country in shownCountries
                    MarketRow(
                        modifier = Modifier.padding(start = 22.dp),
                        status = status,
                        kept = kept,
                        can = capabilities[platform],
                        picked = kept > 0 && (wholeCountry || platform in shownMarkets),
                        pickable = kept > 0,
                        onPick = {
                            if (platform != null) {
                                when {
                                    wholeCountry -> {
                                        onShowCountries(shownCountries - country)
                                        onShowMarkets(shownMarkets + platformsHere - platform)
                                    }
                                    platform in shownMarkets -> onShowMarkets(shownMarkets - platform)
                                    else -> onShowMarkets(shownMarkets + platform)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** A line that is only a tick and a label: the all-markets row and the country headers. */
@Composable
private fun PickRow(
    label: String,
    trailing: String,
    state: ToggleableState,
    enabled: Boolean,
    // A country stands over the markets drawn under it, so it is drawn heavier and with air above.
    heading: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = { if (enabled) onClick() },
        color = if (state != ToggleableState.Off) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = if (heading) 10.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(state = state, enabled = enabled, onClick = onClick)
            Text(
                label,
                style = if (heading) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                else MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val PlatformStatus.isFailure: Boolean
    get() = status in setOf(
        PlatformSearchStatus.ERROR,
        PlatformSearchStatus.BLOCKED,
        PlatformSearchStatus.IP_BLOCKED,
        PlatformSearchStatus.TIMEOUT,
        PlatformSearchStatus.CAPTCHA,
    )

/**
 * What happened at one market, in as few words as carry it.
 *
 * A market that worked says nothing: the count beside its name is the whole story. Only a market
 * that gave you less than it could explains itself, and then in one line. What each market can and
 * cannot publish used to be printed on every row, four lines of it, which turned a list you pick
 * from into a wall of prose nobody reads.
 */
private fun PlatformStatus.saidWhat(kept: Int): String? = when (status) {
    PlatformSearchStatus.PENDING -> "waiting its turn"
    PlatformSearchStatus.SEARCHING -> fetchStage ?: "being asked"
    PlatformSearchStatus.CAPTCHA -> "asked for a captcha instead of answering"
    PlatformSearchStatus.TIMEOUT -> "too slow, given up on"
    PlatformSearchStatus.IP_BLOCKED -> "blocked us"
    PlatformSearchStatus.BLOCKED -> "rate limited, cooling down"
    PlatformSearchStatus.ERROR -> shortError(error) ?: "failed"
    PlatformSearchStatus.DONE -> if (kept == 0) "nothing here" else null
}

/** The first line of a failure, cut to a length someone reads rather than skips. A crawler failure
 *  arrives as whatever the underlying library threw, which for the browser-driven markets is a
 *  stack trace hundreds of lines long. */
private fun shortError(error: String?): String? {
    val first = error?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    return if (first.length <= 60) first else first.take(57) + "…"
}

private fun PlatformStatus.tint(): Color? = when (status) {
    PlatformSearchStatus.DONE -> null
    PlatformSearchStatus.PENDING, PlatformSearchStatus.SEARCHING -> null
    else -> Color(0xFFE03131)
}

@Composable
private fun MarketRow(
    modifier: Modifier = Modifier,
    status: PlatformStatus,
    kept: Int,
    can: MarketCapability?,
    picked: Boolean,
    pickable: Boolean,
    onPick: () -> Unit,
) {
    val accent = status.tint()
    val note = status.saidWhat(kept)
    Surface(
        color = if (accent != null) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(enabled = pickable, onClick = onPick)
                .padding(start = 4.dp, end = 14.dp, top = 2.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = picked, enabled = pickable, onCheckedChange = { onPick() })
                Text(
                    status.platformName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                if (kept > 0) {
                    Text("$kept", style = MaterialTheme.typography.labelLarge)
                }
            }
            // One line, and only when the market gave less than it could have.
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            // A market that held more pages gave a sample, not an answer. That much a buyer needs:
            // it is the difference between "nothing cheaper exists" and "nothing cheaper was seen".
            if (status.hasMore) {
                Text(
                    "first pages only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            status.captchaUrl?.let { url ->
                TextButton(
                    onClick = { openBrowser(url) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) { Text("Solve the captcha in the crawler's own browser") }
            }
        }
    }
}
