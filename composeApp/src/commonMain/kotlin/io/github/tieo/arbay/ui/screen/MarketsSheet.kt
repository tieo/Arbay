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
import io.github.tieo.arbay.model.MarketCapability
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.PlatformSearchStatus
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.READABLE_WIDTH
import io.github.tieo.arbay.ui.viewmodel.PlatformStatus

/**
 * What actually happened at each market.
 *
 * The server distinguishes a market that was blocked, one that timed out, one holding a captcha and
 * one that simply had nothing. On the results canvas all four looked the same: a shorter list. Here
 * each says what it is, alongside the term it was sent — which is translated per country, so a
 * German search reaches eBay Italy as Italian — and how many results it gave before and after
 * filtering.
 */
@Composable
fun MarketsSheet(
    statuses: List<PlatformStatus>,
    offers: Map<PlatformId, Int>,
    // What each market can do, so a blank field reads as "this market does not publish that"
    // rather than as a gap in the app.
    capabilities: Map<PlatformId, MarketCapability>,
    onSelectMarket: (PlatformId) -> Unit,
    onDismiss: () -> Unit,
) {
    val answered = statuses.count { it.status == PlatformSearchStatus.DONE }
    val failed = statuses.count { it.isFailure }

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
            // Failures first: they are the reason a result list is shorter than it should be.
            items(statuses.sortedWith(compareByDescending<PlatformStatus> { it.isFailure }
                .thenByDescending { offers[runCatching { PlatformId.valueOf(it.platformId) }.getOrNull()] ?: 0 })) { status ->
                MarketRow(
                    status = status,
                    kept = offers[runCatching { PlatformId.valueOf(status.platformId) }.getOrNull()] ?: 0,
                    can = capabilities[runCatching { PlatformId.valueOf(status.platformId) }.getOrNull()],
                    onShowOnly = {
                        runCatching { PlatformId.valueOf(status.platformId) }.getOrNull()?.let(onSelectMarket)
                        onDismiss()
                    },
                )
            }
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

/** What happened at one market, in its own words rather than as an absence. */
private fun PlatformStatus.saidWhat(kept: Int): String = when (status) {
    PlatformSearchStatus.PENDING -> "waiting its turn"
    PlatformSearchStatus.SEARCHING -> fetchStage ?: "being asked"
    PlatformSearchStatus.CAPTCHA -> "answered with a captcha instead of results"
    PlatformSearchStatus.TIMEOUT -> "took too long and was given up on"
    PlatformSearchStatus.IP_BLOCKED -> "refused this machine — 403"
    PlatformSearchStatus.BLOCKED -> "rate limited, and is cooling down"
    PlatformSearchStatus.ERROR -> shortError(error) ?: "failed for a reason it did not give"
    PlatformSearchStatus.DONE -> when {
        rawCount == 0 -> "had nothing for this search"
        kept == rawCount -> "$rawCount, all of them kept"
        else -> "$rawCount found, $kept kept after filtering"
    }
}

/**
 * The first line of a failure, in a length someone can read.
 *
 * A crawler failure arrives as whatever the underlying library threw, which for the browser-driven
 * markets is a stack trace hundreds of lines long. The row says what happened; the whole thing is
 * one tap away for when it needs reporting.
 */
private fun shortError(error: String?): String? {
    val first = error?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    return if (first.length <= 120) first else first.take(117) + "…"
}

private fun PlatformStatus.tint(): Color? = when (status) {
    PlatformSearchStatus.DONE -> null
    PlatformSearchStatus.PENDING, PlatformSearchStatus.SEARCHING -> null
    else -> Color(0xFFE03131)
}

@Composable
private fun MarketRow(
    status: PlatformStatus,
    kept: Int,
    can: MarketCapability?,
    onShowOnly: () -> Unit,
) {
    val accent = status.tint()
    Surface(
        color = if (accent != null) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(enabled = kept > 0, onClick = onShowOnly)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.platformName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                if (kept > 0) {
                    Text("$kept", style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                status.saidWhat(kept),
                style = MaterialTheme.typography.bodySmall,
                color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val detail = status.error
            if (detail != null && detail.length > 120) {
                var expanded by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp),
                ) { Text(if (expanded) "Hide the detail" else "What it said in full") }
                if (expanded) {
                    Text(
                        detail.take(4000),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            status.queryUsed?.takeIf { it.isNotBlank() }?.let { term ->
                Text(
                    "asked for “$term”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // A market that held more pages gave a sample, not an answer; saying so is the
            // difference between "nothing cheaper exists" and "nothing cheaper was looked at".
            if (status.hasMore) {
                Text(
                    "held more than it was asked for — this is the first pages only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (status.fromCache) {
                Text(
                    "answered from a stored crawl, not a fresh one",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // What this market cannot answer, said once rather than left as an empty field on
            // every one of its cards.
            can?.let { c ->
                val cannot = buildList {
                    if (!c.listingAge) add("when an ad was posted")
                    if (!c.location) add("where the thing is")
                    if (!c.paginates) add("more than its first page")
                }
                if (cannot.isNotEmpty() && status.status == PlatformSearchStatus.DONE) {
                    Text(
                        "does not give ${cannot.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (c.nativeCriteria.isNotEmpty()) {
                    Text(
                        "narrowed by the market itself: ${c.nativeCriteria.sorted().joinToString(", ") { it.lowercase() }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (status.status == PlatformSearchStatus.DONE) {
                    Text(
                        "applies no filter of its own — every criterion was applied afterwards",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
