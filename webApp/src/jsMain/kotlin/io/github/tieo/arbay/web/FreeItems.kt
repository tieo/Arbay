package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.decimals
import io.github.tieo.arbay.model.FeedbackAction
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.results.ageLabel
import io.github.tieo.arbay.viewmodel.FreeItemViewModel
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Article
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent

/**
 * What is given away near home, one item at a time. Each verdict teaches the scorer what is worth a
 * detour, so the next item is the one it now thinks best. The middle pane is the item; the right
 * pane is what is wanted, where, and what has been kept.
 */
@Composable
fun FreeItemsScreen(app: WebApp) {
    val vm = app.freeItems
    val profile by vm.profile.collectAsState()
    val listings by vm.listings.collectAsState()
    val dismissed by vm.dismissedIds.collectAsState()
    val loading by vm.loading.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val error by vm.error.collectAsState()
    val lastAction by vm.lastAction.collectAsState()
    val radius by vm.currentRadiusKm.collectAsState()

    val deck = remember(listings, dismissed) { listings.filter { it.id !in dismissed } }
    val current = deck.firstOrNull()
    val hasPlace = !profile?.location.isNullOrBlank()

    LaunchedEffect(profile?.location) {
        if (hasPlace && listings.isEmpty() && !loading) vm.search()
    }
    LaunchedEffect(deck.size) { vm.onCardViewed(deck.size, 0) }
    DeckKeys(vm, current, lastAction != null)

    Main({ classes("results", "free") }) {
        Header({ classes("results-head") }) {
            Div({ classes("title-row") }) {
                Div({ classes("title-text") }) {
                    H1 { Text("Free items") }
                    Span({ classes("subtitle") }) {
                        Text(
                            when {
                                !hasPlace -> "Say where home is, and what is worth a detour"
                                else -> listOfNotNull(
                                    "Around ${profile?.location}",
                                    (radius ?: profile?.radiusKm)?.let { "within $it km" },
                                    if (deck.isNotEmpty()) "${deck.size} waiting" else null,
                                ).joinToString(" · ")
                            },
                        )
                    }
                }
                IconButton(Glyph.Refresh, "Look again") { vm.search() }
            }
        }
        error?.let { Div({ classes("notice") }) { Text(it) } }
        Div({ classes("deck") }) {
            when {
                !hasPlace -> Div({ classes("empty") }) { P { Text("Free items are found around a place. Set it on the right.") } }
                current != null -> {
                    FreeItemCard(current)
                    Div({ classes("verdicts") }) {
                        Verdict(Glyph.No, "Not for me", "←") { vm.pass(current) }
                        Verdict(Glyph.Check, "Good, not now", "↓") { vm.like(current) }
                        Verdict(Glyph.Heart, "Keep it", "→", primary = true) { vm.love(current) }
                    }
                    Div({ classes("deck-foot") }) {
                        if (lastAction != null) QuietButton("Undo", Glyph.Undo) { vm.undoLast() }
                        deck.drop(1).take(6).forEach { next -> Thumb(next, "next-thumb") }
                    }
                }
                loading || loadingMore -> Div({ classes("empty") }) { P { Text("Looking around ${profile?.location}") } }
                else -> Div({ classes("empty") }) {
                    P { Text("Nothing new is given away within reach right now.") }
                    QuietButton("Look again", Glyph.Refresh) { vm.search() }
                }
            }
        }
    }
    Aside({ classes("inspector") }) {
        ProfilePanel(vm)
        KeptPanel(vm)
    }
}

/** One item, as large as the pane allows: its photo, what it is, how far, how sure the scorer is. */
@Composable
private fun FreeItemCard(listing: Listing) {
    Article({ classes("free-card") }) {
        val image = listing.imageUrls.firstOrNull { it.isNotBlank() }
        if (image != null) Img(src = image, alt = listing.title) { classes("free-photo"); attr("referrerpolicy", "no-referrer"); onPhotoGone() }
        else Div({ classes("free-photo", "blank") }) { Icon(Glyph.Gift, 48) }
        Div({ classes("free-text") }) {
            H2({ classes("free-title") }) { Text(listing.title) }
            Span({ classes("offer-meta") }) {
                Text(
                    listOfNotNull(
                        listing.distanceKm?.let { "${decimals(it, if (it < 10) 1 else 0)} km away" },
                        listing.location?.let { listOfNotNull(it.zip, it.city).joinToString(" ").ifBlank { null } },
                        listing.listingDate?.let { ageLabel(it) },
                        listing.platformId.displayName,
                    ).joinToString(" · "),
                )
            }
            listing.relevanceScore?.let { score ->
                Div({ classes("fit") }) {
                    Span({ classes("fit-bar") }) { Span({ classes("fit-fill"); style { property("width", "${(score * 100).toInt()}%") } }) {} }
                    Span({ classes("muted", "small") }) { Text("${(score * 100).toInt()}% like what you keep") }
                }
            }
            listing.description?.takeIf { it.isNotBlank() }?.let { P({ classes("free-description") }) { Text(it) } }
            A(href = listing.url, attrs = { classes("link-button"); target(ATarget.Blank); attr("rel", "noopener") }) {
                Icon(Glyph.External, 16); Text("Open on ${listing.platformId.displayName}")
            }
        }
    }
}

@Composable
private fun Verdict(glyph: Glyph, label: String, key: String, primary: Boolean = false, onClick: () -> Unit) {
    org.jetbrains.compose.web.dom.Button(attrs = {
        attr("type", "button")
        classes(*listOfNotNull("verdict", "primary".takeIf { primary }).toTypedArray())
        onClick { onClick() }
    }) {
        Icon(glyph, 20)
        Span { Text(label) }
        Span({ classes("kbd") }) { Text(key) }
    }
}

/** The arrows give the verdicts, so a run of items is gone through without the mouse. */
@Composable
private fun DeckKeys(vm: FreeItemViewModel, current: Listing?, canUndo: Boolean) {
    DisposableEffect(current?.id, canUndo) {
        val handler: (org.w3c.dom.events.Event) -> Unit = handler@{ event ->
            val key = event as KeyboardEvent
            val target = document.activeElement
            if (target is HTMLInputElement || target is HTMLTextAreaElement) return@handler
            when (key.key) {
                "ArrowLeft" -> current?.let { vm.pass(it) }
                "ArrowDown" -> current?.let { vm.like(it) }
                "ArrowRight" -> current?.let { vm.love(it) }
                "Backspace", "z" -> if (canUndo) vm.undoLast() else return@handler
                else -> return@handler
            }
            key.preventDefault()
        }
        document.addEventListener("keydown", handler)
        onDispose { document.removeEventListener("keydown", handler) }
    }
}

/** What is wanted and where: the words the scorer starts from, the place and how far to go, and
 *  whether the server keeps looking while nobody is here. */
@Composable
private fun ProfilePanel(vm: FreeItemViewModel) {
    val profile by vm.profile.collectAsState()
    var description by remember(profile) { mutableStateOf(profile?.description.orEmpty()) }
    var place by remember(profile) { mutableStateOf(profile?.location.orEmpty()) }
    var radius by remember(profile) { mutableStateOf((profile?.radiusKm ?: 30).toString()) }
    val changed = profile == null || description != profile?.description.orEmpty() ||
        place != profile?.location.orEmpty() || radius != (profile?.radiusKm ?: 30).toString()

    Div({ classes("panel") }) {
        H2({ classes("panel-title") }) { Text("What is worth a detour") }
        Form(attrs = {
            classes("stack")
            addEventListener("submit") { event ->
                event.preventDefault()
                if (place.isBlank()) return@addEventListener
                vm.saveProfile(description.trim(), place.trim(), radius.toIntOrNull() ?: 30)
                vm.search()
            }
        }) {
            Label(attrs = { classes("field") }) {
                Span({ classes("field-label") }) { Text("Things you would pick up") }
                TextArea(description) {
                    classes("control", "area")
                    placeholder("Furniture in solid wood, tools, plants, a bike")
                    onInput { description = it.value }
                }
            }
            Div({ classes("field-row") }) {
                Label(attrs = { classes("field") }) {
                    Span({ classes("field-label") }) { Text("Home") }
                    Input(InputType.Text) { classes("control"); placeholder("Town or postcode"); value(place); onInput { place = it.value } }
                }
                Label(attrs = { classes("field", "narrow") }) {
                    Span({ classes("field-label") }) { Text("Within km") }
                    Input(InputType.Number) { classes("control"); value(radius); onInput { radius = it.value?.toString().orEmpty() } }
                }
            }
            if (changed) PrimaryButton("Save and look", Glyph.Search, enabled = place.isNotBlank()) {}
        }
        profile?.let { p ->
            Switch("Keep looking while I am away, and tell me about a good one", p.trackingEnabled) { vm.toggleTracking(it) }
        }
    }
}

/** What was kept, newest first, each one a way back to its listing and a way to take it back. */
@Composable
private fun KeptPanel(vm: FreeItemViewModel) {
    val history by vm.history.collectAsState()
    val kept = history.filter { it.action == FeedbackAction.LOVE }
    Div({ classes("panel") }) {
        H2({ classes("panel-title") }) { Text(if (kept.isEmpty()) "Kept" else "Kept · ${kept.size}") }
        if (kept.isEmpty()) P({ classes("muted", "small") }) { Text("Items you keep stay here after they are gone from the market.") }
        kept.forEach { item ->
            Div({ classes("kept") }) {
                val image = item.imageUrl
                if (image != null) Img(src = image, alt = "") { classes("thumb"); attr("loading", "lazy"); attr("referrerpolicy", "no-referrer"); onPhotoGone() }
                else Div({ classes("thumb", "no-photo") }) { Icon(Glyph.Gift, 18) }
                Div({ classes("offer-text") }) {
                    if (item.url != null) A(href = item.url!!, attrs = { classes("offer-title"); target(ATarget.Blank); attr("rel", "noopener") }) { Text(item.title) }
                    else Span({ classes("offer-title") }) { Text(item.title) }
                    item.locationText?.let { Span({ classes("offer-meta") }) { Text(it) } }
                }
                IconButton(Glyph.Undo, "Take back") { vm.undoFeedback(item.listingId) }
            }
        }
    }
}
