package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.tieo.arbay.model.AdDraft
import io.github.tieo.arbay.model.AdPriceType
import io.github.tieo.arbay.model.postedUrl
import io.github.tieo.arbay.model.problem
import io.github.tieo.arbay.model.saleLine
import io.github.tieo.arbay.navigation.Route
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea

/** The user's ads for sale, above the conversations: each opens beside the list. */
@Composable
fun ForSaleList(app: WebApp, openId: String?) {
    val drafts by app.sell.drafts.collectAsState()
    LaunchedEffect(Unit) { app.sell.load() }
    if (drafts.isEmpty()) return
    Div({ classes("outbox") }) {
        H3({ classes("group-title") }) { Text("For sale") }
    }
    Div({ classes("rows") }) {
        drafts.forEach { d ->
            Div({ classes(*listOfNotNull("offer-row", "active".takeIf { d.id == openId }).toTypedArray()) }) {
                RouteLink(Route.Sell(d.id), classes = listOf("offer-link"), replace = true) {
                    val first = d.photos.firstOrNull()
                    if (first != null) Img(src = app.sell.photoAddress(d, first), alt = "") { classes("thumb") }
                    else Div({ classes("thumb", "no-photo") }) { Icon(Glyph.Tag, 22) }
                    Div({ classes("offer-text") }) {
                        Span({ classes("offer-title") }) { Text(d.title) }
                        Span({ classes("offer-meta") }) { Text(d.saleLine()) }
                    }
                }
            }
        }
    }
}

/**
 * One ad for sale beside the list: its photos, title, text, price and details, changed here and
 * posted on Kleinanzeigen with the one button at the bottom. A posted ad shows as it went out.
 */
@Composable
fun SellPane(app: WebApp, draftId: String) {
    val sell = app.sell
    val open by sell.open.collectAsState()
    val busy by sell.busy.collectAsState()
    val error by sell.error.collectAsState()
    val account by app.chat.account.collectAsState()
    LaunchedEffect(draftId) { sell.open(draftId) }
    val d = open?.takeIf { it.id == draftId }

    Div({ classes("panel", "sell") }) {
        Div({ classes("panel-head") }) {
            H2 { Text(if (d?.postedAdId != null) "Your ad" else "Ad for sale") }
            d?.postedUrl()?.let { url ->
                A(href = url, attrs = { classes("icon-button"); attr("target", "_blank"); attr("rel", "noopener noreferrer"); attr("title", "Open the ad") }) { Icon(Glyph.External) }
            }
            IconButton(Glyph.Close, "Close") { Router.replace(Route.Inbox) }
        }
        if (d == null) { Busy(); return@Div }
        val posted = d.postedAdId != null
        busy?.let { Div({ classes("inline-form") }) { Busy(); Span({ classes("muted", "small") }) { Text(it) } } }
        error?.let { P({ classes("offer-meta", "problem") }) { Text(it) } }

        Div({ classes("sale-photos") }) {
            d.photos.forEach { p ->
                Div({ classes("sale-photo") }) {
                    Img(src = sell.photoAddress(d, p), alt = "Photo $p")
                    if (!posted) IconButton(Glyph.Close, "Leave this photo out") { sell.change(d.copy(photos = d.photos - p)) }
                }
            }
        }
        Label(attrs = { classes("field") }) {
            Span({ classes("field-label") }) { Text("Title") }
            Input(InputType.Text) {
                classes("control"); value(d.title)
                if (posted) attr("readonly", "")
                onInput { sell.change(d.copy(title = it.value)) }
            }
        }
        Label(attrs = { classes("field") }) {
            Span({ classes("field-label") }) { Text("Text") }
            TextArea(d.description) {
                classes("control"); attr("rows", "14")
                if (posted) attr("readonly", "")
                onInput { sell.change(d.copy(description = it.value)) }
            }
        }
        if (d.priceType != AdPriceType.GIVE_AWAY) Label(attrs = { classes("field") }) {
            Span({ classes("field-label") }) { Text("Price") }
            Div({ classes("with-unit") }) {
                Input(InputType.Text) {
                    classes("control"); attr("inputmode", "numeric"); value(d.priceEur?.toString().orEmpty())
                    if (posted) attr("readonly", "")
                    onInput { e -> sell.change(d.copy(priceEur = e.value.filter(Char::isDigit).take(7).toIntOrNull())) }
                }
                Span({ classes("unit") }) { Text("€") }
            }
        }
        Div({ classes("chips") }) { AdPriceType.entries.forEach { t -> Chip(t.label, d.priceType == t) { if (!posted) sell.change(d.copy(priceType = t)) } } }
        Div({ classes("chips") }) {
            Chip("Ships", d.shipping) { if (!posted) sell.change(d.copy(shipping = true)) }
            Chip("Pickup only", !d.shipping) { if (!posted) sell.change(d.copy(shipping = false, buyNow = false)) }
            if (d.shipping) Chip("Direkt kaufen", d.buyNow) { if (!posted) sell.change(d.copy(buyNow = !d.buyNow)) }
        }
        if (d.attributes.isNotEmpty()) {
            H3({ classes("group-title") }) { Text("Details") }
            Div({ classes("sale-details") }) { d.attributes.forEach { (label, value) -> DetailField(d, label, value, posted) { sell.change(it) } } }
        }
        if (!posted) {
            val problem = d.problem() ?: if (account?.signedIn != true) "Sign in to Kleinanzeigen first" else null
            problem?.let { P({ classes("muted", "small") }) { Text(it) } }
            Div({ classes("actions") }) {
                QuietButton("Throw away") { sell.delete(d.id) { Router.replace(Route.Inbox) } }
                PrimaryButton("Post on Kleinanzeigen", Glyph.Send, enabled = problem == null && busy == null) { sell.post() }
            }
        }
    }
}

/** One detail as the post form words it; cleared, the detail is dropped. */
@Composable
private fun DetailField(d: AdDraft, label: String, value: String, posted: Boolean, onChange: (AdDraft) -> Unit) {
    Label(attrs = { classes("field") }) {
        Span({ classes("field-label") }) { Text(label) }
        Input(InputType.Text) {
            classes("control"); value(value)
            if (posted) attr("readonly", "")
            onInput { e -> onChange(d.copy(attributes = if (e.value.isEmpty()) d.attributes - label else d.attributes + (label to e.value))) }
        }
    }
}
