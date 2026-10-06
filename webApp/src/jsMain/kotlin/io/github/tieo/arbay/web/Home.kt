package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.tieo.arbay.history.SearchHistoryStore
import io.github.tieo.arbay.history.summary
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.results.savedSearchSubtitle
import io.github.tieo.arbay.results.watchLabel
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Where a visit starts: the saved searches with what each found since it was last opened, those
 * with something new first, and the searches run lately that nobody saved.
 */
@Composable
fun HomeScreen(app: WebApp) {
    val products by app.products.products.collectAsState()
    val status by app.products.status.collectAsState()
    val history by SearchHistoryStore.entries.collectAsState()
    val freeMatches by app.freeItems.newMatches.collectAsState()
    val ordered = products.sortedByDescending { status[it.id]?.newSinceOpened ?: 0 }
    val waiting = products.sumOf { status[it.id]?.newSinceOpened ?: 0 }

    Main({ classes("results", "home") }) {
        Header({ classes("results-head") }) {
            Div({ classes("title-text") }) {
                H1 { Text(if (waiting > 0) "$waiting new since you last looked" else "Nothing new since you last looked") }
                Span({ classes("subtitle") }) {
                    Text(if (products.isEmpty()) "Search above, and save what is worth coming back to" else "${products.size} saved searches")
                }
            }
        }
        Div({ classes("cards") }) {
            ordered.forEach { product ->
                val found = status[product.id]
                val fresh = found?.newSinceOpened ?: 0
                RouteLink(Route.Results(Source.Saved(product.id)), classes = listOfNotNull("card", "fresh".takeIf { fresh > 0 })) {
                    Div({ classes("card-head") }) {
                        Icon(if (product.searchQuery.category == MarketGroup.VEHICLES) Glyph.Car else Glyph.Bookmark, 16)
                        Span({ classes("card-title") }) { Text(product.name) }
                        if (fresh > 0) Span({ classes("count") }) { Text("$fresh new") }
                    }
                    Span({ classes("offer-meta") }) { Text(savedSearchSubtitle(product)) }
                    found?.takeIf { it.watched }?.let { s ->
                        Span({ classes("card-foot") }) {
                            Icon(Glyph.Bell, 14)
                            Text(watchLabel(s))
                        }
                    }
                }
            }
        }
    }
    Aside({ classes("inspector") }) {
        if (freeMatches.isNotEmpty()) {
            Div({ classes("panel") }) {
                RouteLink(Route.FreeItems, classes = listOf("notice", "link")) {
                    Icon(Glyph.Gift, 16)
                    Text("${freeMatches.size} free ${if (freeMatches.size == 1) "item" else "items"} near you since you last looked")
                }
            }
        }
        Div({ classes("panel") }) {
            H2({ classes("panel-title") }) { Text("Lately") }
            if (history.isEmpty()) P({ classes("muted", "small") }) { Text("Searches you run show up here until you save them.") }
            history.forEach { entry ->
                val q = entry.searchQuery
                val source = if (q.category == MarketGroup.VEHICLES) Source.Vehicle(q.text) else Source.Typed(q.text)
                Div({ classes("recent-row") }) {
                    RouteLink(Route.Results(source), classes = listOf("recent")) {
                        Icon(if (q.category == MarketGroup.VEHICLES) Glyph.Car else Glyph.Search, 14)
                        Span({ classes("recent-name") }) { Text(entry.name) }
                        Span({ classes("muted", "small") }) { Text(entry.summary()) }
                    }
                    IconButton(Glyph.Close, "Forget") { SearchHistoryStore.remove(q.text, q.category) }
                }
            }
        }
    }
}
