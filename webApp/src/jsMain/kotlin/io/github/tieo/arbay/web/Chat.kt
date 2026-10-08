package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.chat.SendLine
import io.github.tieo.arbay.chat.hasOpenFillIn
import io.github.tieo.arbay.chat.kleinanzeigenAdUrl
import io.github.tieo.arbay.chat.outgoingLabel
import io.github.tieo.arbay.chat.planSend
import io.github.tieo.arbay.chat.sellerMark
import io.github.tieo.arbay.chat.toRequest
import io.github.tieo.arbay.model.BlockReview
import io.github.tieo.arbay.model.ChatMessage
import io.github.tieo.arbay.chat.reviewLine
import io.github.tieo.arbay.model.Conversation
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MessageTemplate
import io.github.tieo.arbay.model.OutgoingState
import io.github.tieo.arbay.model.TEMPLATE_FILL_INS
import io.github.tieo.arbay.model.SignInAsk
import io.github.tieo.arbay.model.SignInStep
import org.jetbrains.compose.web.dom.Form
import io.github.tieo.arbay.model.composeBlocks
import io.github.tieo.arbay.model.canMessage
import io.github.tieo.arbay.model.tidyTitle
import io.github.tieo.arbay.navigation.Panel
import io.github.tieo.arbay.navigation.Route
import io.github.tieo.arbay.navigation.Source
import io.github.tieo.arbay.results.ResultsState
import io.github.tieo.arbay.results.ago
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea

/**
 * The conversations with sellers: every one on the left, the open one on the right with its reply
 * field, so a reply is written with the list still in view.
 */
@Composable
fun InboxScreen(app: WebApp, route: Route) {
    val chat = app.chat
    val account by chat.account.collectAsState()
    val conversations by chat.conversations.collectAsState()
    val outbox by chat.outbox.collectAsState()
    val error by chat.error.collectAsState()
    val review by chat.review.collectAsState()
    LaunchedEffect(Unit) { chat.watch(); chat.refresh(); chat.loadReview() }
    val openId = (route as? Route.Conversation)?.id
    val onTheirWay = outbox.filter { it.state == OutgoingState.WAITING || it.state == OutgoingState.SENDING || it.state == OutgoingState.FAILED }

    Main({ classes("results") }) {
        Header({ classes("results-head") }) { Div({ classes("title-row") }) { Div({ classes("title-text") }) { H1 { Text("Messages") } } } }
        if (account?.signedIn != true) Div({ classes("panel") }) { AccountSection(app) }
        error?.let { P({ classes("muted", "small", "inbox-error") }) { Text(it) } }
        if (onTheirWay.isNotEmpty()) {
            Div({ classes("outbox") }) {
                H3({ classes("group-title") }) { Text("On their way") }
                onTheirWay.forEach { m ->
                    Div({ classes("outgoing") }) {
                        Div({ classes("offer-text") }) {
                            Span({ classes("offer-title") }) { Text(m.title) }
                            Span({ classes(*listOfNotNull("offer-meta", "problem".takeIf { m.state == OutgoingState.FAILED }).toTypedArray()) }) { Text(outgoingLabel(m)) }
                        }
                        if (m.state == OutgoingState.WAITING) QuietButton("Call off") { chat.cancel(m.id) }
                    }
                }
            }
        }
        if (review.isNotEmpty()) Div({ classes("outbox") }) {
            H3({ classes("group-title") }) { Text("How your texts do") }
            review.forEach { ReviewEntry(it) }
        }
        Div({ classes("rows") }) {
            if (account?.signedIn == true && conversations.isEmpty()) Div({ classes("empty") }) { Text("No conversations yet.") }
            conversations.forEach { c -> ConversationRow(c, c.id == openId) }
        }
    }
    Aside({ classes("inspector") }) {
        if (openId != null) ConversationPane(app, openId)
        else Div({ classes("empty") }) { Text(if (conversations.isEmpty()) "" else "${conversations.sumOf { it.unread }} unread") }
    }
}

/** One text block: how it did, and opened, what each seller answered to it. */
@Composable
private fun ReviewEntry(r: BlockReview) {
    var open by remember { mutableStateOf(false) }
    Div({ classes("review") }) {
        Button(attrs = { attr("type", "button"); classes("text-entry"); attr("aria-expanded", open.toString()); onClick { open = !open } }) {
            Span({ classes("offer-title") }) { Text(r.name) }
            Span({ classes("offer-meta") }) { Text(reviewLine(r)) }
        }
        if (open) r.answers.forEach { a ->
            RouteLink(Route.Conversation(a.conversationId), classes = listOf("review-answer")) {
                Span({ classes("offer-meta") }) { Text(listOfNotNull(a.title, a.price?.let { "offered $it €" }).joinToString(" · ")) }
                Span { Text(a.answer ?: "No answer yet") }
            }
        }
    }
}

@Composable
private fun ConversationRow(c: Conversation, active: Boolean) {
    Div({ classes(*listOfNotNull("offer-row", "active".takeIf { active }).toTypedArray()) }) {
        RouteLink(Route.Conversation(c.id), classes = listOf("offer-link"), replace = true) {
            val image = c.adImage
            if (image != null) Img(src = image, alt = "") { classes("thumb"); attr("referrerpolicy", "no-referrer"); onPhotoGone() }
            else Div({ classes("thumb", "no-photo") }) { Icon(Glyph.Chat, 22) }
            Div({ classes("offer-text") }) {
                Span({ classes("offer-title") }) { Text(c.adTitle ?: "An ad") }
                Span({ classes("offer-meta") }) {
                    Text(listOfNotNull(c.partner, if (c.buying) null else "about your ad", c.lastAt?.let { ago(it.toEpochMilliseconds()) }).joinToString(" · "))
                }
                c.lastText?.let { Span({ classes(*listOfNotNull("offer-foot", "unread".takeIf { c.unread > 0 }).toTypedArray()) }) { Text(it) } }
            }
            if (c.unread > 0) Span({ classes("count") }) { Text("${c.unread}") }
        }
    }
}

@Composable
private fun ConversationPane(app: WebApp, id: String) {
    val chat = app.chat
    val open by chat.open.collectAsState()
    LaunchedEffect(id) { chat.openConversation(id) }
    val c = open?.takeIf { it.id == id }
    var reply by remember(id) { mutableStateOf("") }

    Div({ classes("conversation") }) {
        Div({ classes("panel-head") }) {
            Div({ classes("title-text") }) {
                H2 { Text(c?.adTitle ?: "") }
                c?.partner?.let { Span({ classes("muted", "small") }) { Text(it) } }
            }
            c?.listingId?.let(::kleinanzeigenAdUrl)?.let { url ->
                A(href = url, attrs = { classes("icon-button"); attr("target", "_blank"); attr("rel", "noopener noreferrer"); attr("title", "Open the ad") }) { Icon(Glyph.External) }
            }
            IconButton(Glyph.Close, "Close") { Router.replace(Route.Inbox) }
        }
        Div({ classes("bubbles") }) {
            if (c == null) P({ classes("muted") }) { Text("Loading") }
            c?.messages?.forEach { Bubble(it) }
        }
        Div({ classes("reply") }) {
            TextArea(reply) {
                classes("control")
                placeholder("Reply")
                attr("aria-label", "Reply")
                onInput { reply = it.value }
            }
            PrimaryButton("Send", Glyph.Send, enabled = reply.isNotBlank()) { chat.reply(id, reply.trim()) { reply = "" } }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    Div({ classes("bubble", if (m.mine) "mine" else "theirs") }) {
        m.attachments.forEach { url -> A(href = url, attrs = { attr("target", "_blank") }) { Img(src = url, alt = "") { classes("bubble-photo"); onPhotoGone() } } }
        m.offerEur?.let { Span({ classes("bubble-offer") }) { Text("Offer: ${it.toInt()} €") } }
        if (m.text.isNotBlank()) P { Text(m.text) }
        m.at?.let { Span({ classes("muted", "small") }) { Text(ago(it.toEpochMilliseconds())) } }
    }
}

/** Whether Arbay can write from the user's Kleinanzeigen account, and signing in to it from here. */
@Composable
fun AccountSection(app: WebApp) {
    val chat = app.chat
    val account by chat.account.collectAsState()
    val signIn by chat.signIn.collectAsState()
    val busy by chat.signInBusy.collectAsState()
    LaunchedEffect(Unit) { chat.watch() }
    val a = account
    val step = signIn
    H3 { Text("Kleinanzeigen") }
    when {
        step != null -> SignInSteps(app, step, busy)
        a == null -> P({ classes("muted") }) { Text("Asking the server") }
        a.signedIn -> P({ classes("muted") }) { Text("Signed in" + (a.name?.let { " as $it" } ?: "")) }
        else -> {
            a.problem?.let { P({ classes("muted", "small") }) { Text(it) } }
            Div({ classes("actions") }) { PrimaryButton(if (busy) "Opening the sign-in" else "Sign in", enabled = !busy) { chat.beginSignIn() } }
        }
    }
}

/**
 * The market's login page as it stands: a field for what it asks (e-mail, password, a code), and
 * its picture, which takes clicks for anything else it shows.
 */
@Composable
private fun SignInSteps(app: WebApp, step: SignInStep, busy: Boolean) {
    val chat = app.chat
    var value by remember(step.step) { mutableStateOf("") }
    val label = when (step.step) {
        SignInAsk.EMAIL -> "E-mail"
        SignInAsk.PASSWORD -> "Password"
        SignInAsk.CODE -> "Code"
        else -> null
    }
    step.error?.let { P({ classes("sign-in-error") }) { Text(it) } }
    Form(attrs = {
        classes("inline-form")
        addEventListener("submit") { it.preventDefault(); if (value.isNotBlank() && !busy) chat.signInWith(value) }
    }) {
        if (label != null) {
            Input(if (step.step == SignInAsk.PASSWORD) InputType.Password else if (step.step == SignInAsk.EMAIL) InputType.Email else InputType.Text) {
                classes("control"); placeholder(label); attr("aria-label", label); attr("autofocus", "")
                if (step.step == SignInAsk.CODE) attr("autocomplete", "one-time-code")
                if (busy) attr("disabled", "")
                value(value); onInput { value = it.value }
            }
            PrimaryButton(if (busy) "Waiting for Kleinanzeigen" else "Continue", enabled = !busy && value.isNotBlank()) {}
        }
        QuietButton("Cancel") { chat.cancelSignIn() }
    }
    step.picture?.let { picture ->
        Img(src = "data:image/jpeg;base64,$picture", alt = "The Kleinanzeigen sign-in page") {
            classes("sign-in-page")
            addEventListener("click") { e ->
                val img = e.target as org.w3c.dom.HTMLImageElement
                val m = e as org.w3c.dom.events.MouseEvent
                if (img.clientWidth > 0 && step.width > 0) {
                    chat.signInTap(m.offsetX * step.width / img.clientWidth, m.offsetY * step.height / img.clientHeight)
                }
            }
        }
    }
}

/** The tick that picks an offer to write to; shown on hover, and always once anything is picked. */
@Composable
fun PickBox(app: WebApp, listing: Listing, picking: Boolean) {
    if (!listing.platformId.canMessage) return
    val selected by app.chat.selected.collectAsState()
    val on = listing.id in selected
    Label(attrs = {
        classes(*listOfNotNull("pick", "on".takeIf { on }, "picking".takeIf { picking }).toTypedArray())
        attr("title", if (on) "Picked" else "Pick to write to")
    }) {
        Input(InputType.Checkbox) {
            checked(on)
            attr("aria-label", "Pick to write to the seller")
            onChange { app.chat.toggleSelected(listing.id) }
        }
    }
}

/** Where the user stands with an offer's seller, for its row. */
@Composable
fun SellerMarkLine(app: WebApp, listing: Listing) {
    val conversations by app.chat.conversations.collectAsState()
    val outbox by app.chat.outbox.collectAsState()
    sellerMark(conversations.firstOrNull { it.listingId == listing.id && it.buying }, outbox.lastOrNull { it.listingId == listing.id })?.let {
        Span({ classes("seller-mark") }) { Icon(Glyph.Chat, 13); Text(it) }
    }
}

/** The bar over the offers while some are picked. */
@Composable
fun PickBar(app: WebApp, route: Route.Results) {
    val selected by app.chat.selected.collectAsState()
    if (selected.isEmpty()) return
    Div({ classes("pick-bar") }) {
        Span { Text("${selected.size} picked") }
        QuietButton("Clear") { app.chat.clearSelection() }
        PrimaryButton(if (selected.size == 1) "Write to the seller" else "Write to ${selected.size} sellers", Glyph.Chat) {
            Router.replace(route.copy(panel = Panel.WRITE, listing = null))
        }
    }
}

/** The conversation with an offer's seller, or the way to start one, under its open button. */
@Composable
fun SellerAction(app: WebApp, route: Route.Results, listing: Listing) {
    if (!listing.platformId.canMessage) return
    val conversations by app.chat.conversations.collectAsState()
    val conversation = conversations.firstOrNull { it.listingId == listing.id && it.buying }
    if (conversation != null) {
        RouteLink(Route.Conversation(conversation.id), classes = listOf("quiet", "link-button")) {
            Icon(Glyph.Chat); Text("Conversation" + (conversation.partner?.let { " with $it" } ?: ""))
        }
    } else {
        QuietButton("Write to the seller", Glyph.Chat) {
            if (listing.id !in app.chat.selected.value) app.chat.toggleSelected(listing.id)
            Router.replace(route.copy(panel = Panel.WRITE, listing = null))
        }
    }
}

/**
 * The send window: the all-in limit, the user's text once, and every picked seller with the price
 * they will be offered. A seller's line opens to change that one message alone.
 */
@Composable
fun WritePanel(app: WebApp, route: Route.Results, state: ResultsState) {
    val chat = app.chat
    val selected by chat.selected.collectAsState()
    val settings by chat.settings.collectAsState()
    val account by chat.account.collectAsState()
    val fetched by app.listings.fetched.collectAsState()
    val searchId = (route.source as? Source.Saved)?.id
    val listings = remember(selected, fetched) { fetched.filter { it.id in selected } }

    var allIn by remember(searchId, settings.allInBySearch[searchId ?: ""]) {
        mutableStateOf(searchId?.let { settings.allInBySearch[it] }?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "")
    }
    var picked by remember { mutableStateOf(listOf<String>()) }
    var text by remember { mutableStateOf("") }
    val composed = composeBlocks(picked.mapNotNull { id -> settings.templates.firstOrNull { it.id == id } })
    val edits = remember { mutableStateMapOf<String, String>() }
    var openLine by remember { mutableStateOf<String?>(null) }
    val limit = allIn.replace(',', '.').toDoubleOrNull()
    // Each picked ad's own page, for the shipping its card leaves out ("Versand ab 6,19 €").
    val pageShipping = remember { mutableStateMapOf<String, io.github.tieo.arbay.model.Shipping>() }
    LaunchedEffect(listings.map { it.id }) {
        listings.filter { it.id !in pageShipping }.forEach { l ->
            app.client.listingDetail(l)?.shipping?.let { pageShipping[l.id] = it }
        }
    }
    val lines = planSend(listings, limit, text, edits, picked, pageShipping)
    val sendable = lines.filter { it.unreachable == null }
    val ready = account?.signedIn == true && sendable.isNotEmpty() && sendable.none { it.text.isBlank() || it.hasOpenFillIn }

    Div({ classes("panel", "write") }) {
        Div({ classes("panel-head") }) {
            H2 { Text(if (listings.size == 1) "Write to the seller" else "Write to ${listings.size} sellers") }
            IconButton(Glyph.Close, "Close") { Router.replace(route.copy(panel = null)) }
        }
        if (account?.signedIn != true) AccountSection(app)
        if (listings.isEmpty()) { P({ classes("muted") }) { Text("Nothing picked.") }; return@Div }

        Label(attrs = { classes("field") }) {
            Span({ classes("field-label") }) { Text("Most you pay, everything included") }
            Div({ classes("with-unit") }) {
                Input(InputType.Text) {
                    classes("control")
                    attr("inputmode", "decimal")
                    value(allIn)
                    onInput { e -> allIn = e.value.filter { it.isDigit() || it == ',' || it == '.' } }
                }
                Span({ classes("unit") }) { Text("€") }
            }
        }
        if (settings.templates.isNotEmpty()) Div({ classes("chips") }) {
            settings.templates.forEach { t ->
                val place = picked.indexOf(t.id)
                Chip(if (place >= 0) "${place + 1} ${t.name}" else t.name, place >= 0) {
                    picked = if (place >= 0) picked - t.id else picked + t.id
                    text = composeBlocks(picked.mapNotNull { id -> settings.templates.firstOrNull { it.id == id } })
                    edits.clear()
                }
            }
        }
        Label(attrs = { classes("field") }) {
            Span({ classes("field-label") }) { Text("Your message") }
            TextArea(text) { classes("control"); attr("rows", "6"); onInput { text = it.value } }
        }
        Div({ classes("chips") }) { TEMPLATE_FILL_INS.forEach { (token, means) -> FillInChip(token, means, token in text) { text += token } } }
        if (text.isNotBlank() && text != composed) KeepText(app, text)

        Div({ classes("seller-lines") }) {
            lines.forEach { line ->
                SellerLineView(line, openLine == line.listing.id,
                    onToggle = { openLine = if (openLine == line.listing.id) null else line.listing.id },
                    onEdit = { edits[line.listing.id] = it },
                    onReset = { edits.remove(line.listing.id) },
                    onDrop = { chat.toggleSelected(line.listing.id) },
                )
            }
        }
        Div({ classes("actions") }) {
            PrimaryButton(if (sendable.size == 1) "Send" else "Send ${sendable.size}", Glyph.Send, enabled = ready) {
                chat.send(lines.toRequest(searchId, limit)) { Router.replace(route.copy(panel = null)) }
            }
        }
    }
}

@Composable
private fun FillInChip(token: String, means: String, on: Boolean, onClick: () -> Unit) {
    Button(attrs = {
        attr("type", "button")
        classes(*listOfNotNull("chip", "fill-in", "on".takeIf { on }).toTypedArray())
        attr("title", means)
        onClick { onClick() }
    }) { Text(token) }
}

@Composable
private fun KeepText(app: WebApp, text: String) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    if (!naming) { QuietButton("Keep this text") { naming = true }; return }
    Div({ classes("inline-form") }) {
        Input(InputType.Text) { classes("control"); placeholder("Name it"); attr("aria-label", "Name it"); value(name); onInput { name = it.value } }
        PrimaryButton("Keep", enabled = name.isNotBlank()) {
            app.chat.saveTemplate(MessageTemplate(app.chat.newTemplateId(), name.trim(), text))
            naming = false
        }
    }
}

@Composable
private fun SellerLineView(line: SendLine, open: Boolean, onToggle: () -> Unit, onEdit: (String) -> Unit, onReset: () -> Unit, onDrop: () -> Unit) {
    Div({ classes(*listOfNotNull("seller-line", "unreachable".takeIf { line.unreachable != null }).toTypedArray()) }) {
        Div({ classes("seller-line-head") }) {
            Button(attrs = {
                attr("type", "button")
                classes("seller-line-toggle")
                if (line.unreachable != null) attr("disabled", "")
                attr("aria-expanded", open.toString())
                onClick { onToggle() }
            }) {
                Span({ classes("offer-title") }) { Text(line.listing.title.tidyTitle()) }
                val foot = line.unreachable ?: listOfNotNull(if (line.edited) "your own wording" else null, line.note).joinToString(" · ")
                if (foot.isNotEmpty()) Span({ classes("offer-meta") }) { Text(foot) }
            }
            line.price?.let { Span({ classes("price") }) { Text("$it €") } }
            IconButton(Glyph.Close, "Leave out") { onDrop() }
        }
        if (open) {
            TextArea(line.text) { classes("control"); attr("rows", "5"); onInput { onEdit(it.value) } }
            if (line.edited) QuietButton("Use the shared text") { onReset() }
        }
    }
}

/** The user's own texts for sellers: each named, written and changed here, never by Arbay. */
@Composable
fun TextsSection(app: WebApp) {
    val chat = app.chat
    val settings by chat.settings.collectAsState()
    LaunchedEffect(Unit) { chat.watch() }
    var editing by remember { mutableStateOf<MessageTemplate?>(null) }
    P({ classes("muted", "small") }) { Text(TEMPLATE_FILL_INS.joinToString("  ·  ") { (token, means) -> "$token $means" }) }
    settings.templates.forEach { t ->
        val e = editing
        if (e != null && e.id == t.id) TemplateEditor(e, { editing = it }, onSave = { chat.saveTemplate(it); editing = null }, onDelete = { chat.deleteTemplate(t.id); editing = null })
        else Button(attrs = { attr("type", "button"); classes("text-entry"); onClick { editing = t } }) {
            Span({ classes("offer-title") }) { Text(t.name) }
            Span({ classes("offer-meta") }) { Text(t.text) }
        }
    }
    val draft = editing
    if (draft != null && settings.templates.none { it.id == draft.id }) {
        TemplateEditor(draft, { editing = it }, onSave = { chat.saveTemplate(it); editing = null }, onDelete = { editing = null })
    } else if (draft == null) {
        Div({ classes("actions") }) { QuietButton("New text") { editing = MessageTemplate(chat.newTemplateId(), "", "") } }
    }
}

@Composable
private fun TemplateEditor(t: MessageTemplate, onChange: (MessageTemplate) -> Unit, onSave: (MessageTemplate) -> Unit, onDelete: () -> Unit) {
    Div({ classes("template-editor") }) {
        Input(InputType.Text) { classes("control"); placeholder("Name"); attr("aria-label", "Name"); value(t.name); onInput { onChange(t.copy(name = it.value)) } }
        TextArea(t.text) { classes("control"); attr("rows", "5"); attr("aria-label", "Text"); onInput { onChange(t.copy(text = it.value)) } }
        Div({ classes("chips") }) { TEMPLATE_FILL_INS.forEach { (token, means) -> FillInChip(token, means, token in t.text) { onChange(t.copy(text = t.text + token)) } } }
        Div({ classes("actions") }) {
            PrimaryButton("Keep", enabled = t.name.isNotBlank() && t.text.isNotBlank()) { onSave(t.copy(name = t.name.trim())) }
            QuietButton("Delete") { onDelete() }
        }
    }
}
