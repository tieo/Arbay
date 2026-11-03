package io.github.tieo.arbay.ui

import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.chat.SendLine
import io.github.tieo.arbay.chat.hasOpenFillIn
import io.github.tieo.arbay.chat.kleinanzeigenAdUrl
import io.github.tieo.arbay.chat.outgoingLabel
import io.github.tieo.arbay.chat.planSend
import io.github.tieo.arbay.chat.toRequest
import io.github.tieo.arbay.model.BlockReview
import io.github.tieo.arbay.model.ChatMessage
import io.github.tieo.arbay.chat.reviewLine
import io.github.tieo.arbay.model.Conversation
import io.github.tieo.arbay.model.MessageTemplate
import io.github.tieo.arbay.model.OutgoingState
import io.github.tieo.arbay.model.TEMPLATE_FILL_INS
import io.github.tieo.arbay.model.accountName
import io.github.tieo.arbay.model.PlatformId
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import io.github.tieo.arbay.model.composeBlocks
import io.github.tieo.arbay.model.tidyTitle
import io.github.tieo.arbay.navigation.Route
import io.github.tieo.arbay.navigation.Source
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.results.ResultsState
import io.github.tieo.arbay.results.ago

/** Every conversation with a seller, newest first, and the messages still on their way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(session: Session) {
    val chat = session.chat
    val nav = LocalNavigator.current
    val account by chat.account.collectAsState()
    val conversations by chat.conversations.collectAsState()
    val outbox by chat.outbox.collectAsState()
    val error by chat.error.collectAsState()
    val review by chat.review.collectAsState()
    LaunchedEffect(Unit) { chat.watch(); chat.refresh(); chat.loadReview() }
    val onTheirWay = outbox.filter { it.state == OutgoingState.WAITING || it.state == OutgoingState.SENDING || it.state == OutgoingState.FAILED }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (account?.signedIn != true) item { Box(Modifier.padding(arbay.pad)) { AccountPanel(session) } }
            error?.let { item { Muted(it, Modifier.padding(horizontal = arbay.pad)) } }
            if (onTheirWay.isNotEmpty()) {
                item { SectionTitle("On their way", Modifier.padding(horizontal = arbay.pad, vertical = 8.dp)) }
                items(onTheirWay, key = { "out:" + it.id }) { m ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = arbay.pad, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(outgoingLabel(m), maxLines = 2)
                        }
                        if (m.state == OutgoingState.WAITING) TextButton(onClick = { chat.cancel(m.id) }) { Text("Call off") }
                    }
                }
                item { HorizontalDivider(color = arbay.line) }
            }
            if (review.isNotEmpty()) {
                item { SectionTitle("How your texts do", Modifier.padding(horizontal = arbay.pad, vertical = 8.dp)) }
                items(review, key = { "review:" + it.blockId }) { ReviewRow(it) }
                item { HorizontalDivider(color = arbay.line) }
            }
            item { ForSaleRows(session) }
            if (account?.signedIn == true && conversations.isEmpty()) item { Nothing("No conversations yet.") }
            items(conversations, key = { it.id }) { c ->
                ConversationRow(c) { nav.go(Route.Conversation(c.id)) }
                HorizontalDivider(Modifier.padding(start = arbay.pad), color = arbay.line)
            }
        }
    }
}

/** One text block: how it did, and opened, what each seller answered to it. */
@Composable
private fun ReviewRow(r: BlockReview) {
    val nav = LocalNavigator.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = arbay.pad, vertical = 6.dp)) {
        Text(r.name, style = MaterialTheme.typography.bodyMedium)
        Muted(reviewLine(r))
        if (open) r.answers.forEach { a ->
            Column(Modifier.fillMaxWidth().clickable { nav.go(Route.Conversation(a.conversationId)) }.padding(top = 8.dp)) {
                Muted(listOfNotNull(a.title, a.price?.let { "offered $it €" }).joinToString(" · "), maxLines = 1)
                Text(a.answer ?: "No answer yet", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ConversationRow(c: Conversation, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = arbay.pad, vertical = arbay.gap),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Photo(c.adImage, Modifier.size(52.dp).clip(MaterialTheme.shapes.small), c.adTitle)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.adTitle ?: "An ad", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted(listOfNotNull(c.partner, if (c.buying) null else "about your ad", c.lastAt?.let { ago(it.toEpochMilliseconds()) }).joinToString(" · "), maxLines = 1)
            c.lastText?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (c.unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (c.unread > 0) Count(c.unread)
    }
}

/** One conversation, oldest message first, with the reply field at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(session: Session, id: String) {
    val chat = session.chat
    val nav = LocalNavigator.current
    val open by chat.open.collectAsState()
    val error by chat.error.collectAsState()
    LaunchedEffect(id) { chat.openConversation(id) }
    val c = open?.takeIf { it.id == id }
    var reply by remember(id) { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(c?.messages?.size) { c?.messages?.size?.takeIf { it > 0 }?.let { list.scrollToItem(it - 1) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(c?.adTitle ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        c?.partner?.let { Muted(it, maxLines = 1) }
                    }
                },
                actions = {
                    // A thing bought here can be sold again from its own ad.
                    if (c?.buying == true) c.listingId?.let { listingId ->
                        IconButton(onClick = { session.sell.startFrom(listingId) { nav.go(Route.Sell(it)) } }) { Icon(Icons.Outlined.Sell, "Sell one like this") }
                    }
                    c?.listingId?.let(::kleinanzeigenAdUrl)?.let { url ->
                        IconButton(onClick = { openBrowser(url) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open the ad") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).imePadding().navigationBarsPadding()
                    .padding(horizontal = arbay.pad, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(reply, { reply = it }, Modifier.weight(1f), placeholder = { Text("Reply") }, maxLines = 5)
                IconButton(enabled = reply.isNotBlank(), onClick = { chat.reply(id, reply.trim()) { reply = "" } }) {
                    Icon(Icons.AutoMirrored.Outlined.Send, "Send")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding), state = list,
            contentPadding = PaddingValues(arbay.pad), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            error?.let { item { Muted(it) } }
            if (c == null) item { Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { Busy() } }
            else if (c.messages.isEmpty()) item { Nothing("Nothing said yet.") }
            items(c?.messages.orEmpty(), key = { it.id }) { Bubble(it) }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            m.attachments.forEach { Photo(it, Modifier.size(200.dp).clip(MaterialTheme.shapes.small)) }
            m.offerEur?.let { Text("Offer: ${it.toInt()} €", style = MaterialTheme.typography.titleSmall) }
            if (m.text.isNotBlank()) Text(m.text, style = MaterialTheme.typography.bodyMedium)
            m.at?.let { Muted(ago(it.toEpochMilliseconds()), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** The user's account on every market Arbay can sign in to, and signing in to one from here. */
@Composable
fun AccountPanel(session: Session) {
    val chat = session.chat
    val accounts by chat.accounts.collectAsState()
    Panel {
        if (accounts.isEmpty()) Busy()
        // One group per market, the groups a full gap apart, whatever each one ends with.
        val sites by chat.sites.collectAsState()
        LaunchedEffect(Unit) { chat.watch() }
        Column(verticalArrangement = Arrangement.spacedBy(arbay.gap)) { accounts.forEach { a -> Column(verticalArrangement = Arrangement.spacedBy(arbay.gap / 2)) {
            // Auth signs every market in: where its account stands, as the market answers now.
            a.signInSite?.let { site ->
                SiteSignIn(
                    session, site, a.market.accountName, sites?.accounts?.get(site),
                    idle = if (a.signedIn) "Signed in" + (a.name?.let { " as $it" } ?: "") else a.problem ?: "Not signed in",
                    failedIdle = a.problem != null,
                )
            }
        } } }
    }
}

/** The name a person knows a site's account by. */
fun siteName(site: String) = when (site) {
    "google.com" -> "Google"
    "kleinanzeigen.de" -> "Kleinanzeigen"
    "paypal.com" -> "PayPal"
    else -> site
}

/**
 * The accounts Neuland's account browser signs in to for buying (Google, PayPal, shops), each with
 * where its latest sign-in stands. A sign-in is asked of Auth, whose page opens here: the
 * password is given and sealed there, and the second factor shows there. A market account Neuland
 * Sign signs in (Kleinanzeigen) shows with the market accounts instead.
 */
@Composable
fun SiteAccountPanel(session: Session, only: String? = null, account: String? = null) {
    val chat = session.chat
    val sites by chat.sites.collectAsState()
    val markets by chat.accounts.collectAsState()
    LaunchedEffect(Unit) { chat.watch() }
    val all = sites ?: return Busy()
    val marketSites = markets.mapNotNull { it.signInSite }.toSet()
    Panel { Column(verticalArrangement = Arrangement.spacedBy(arbay.gap)) {
        if (only == null) SectionTitle("Shop and payment accounts")
        all.sites.filter { if (only == null) it !in marketSites else it == only }.sorted().forEach { site ->
            SiteSignIn(session, site, siteName(site), (if (site == only) account else null) ?: all.accounts[site], idle = null)
        }
    }
}
}

/**
 * One account Auth signs in: its name, where its latest sign-in stands (or [idle], what is
 * known of the account otherwise), and signing in, which opens Auth's page. Where no account
 * is [known] for the site, the e-mail is asked first.
 */
@Composable
fun SiteSignIn(session: Session, site: String, title: String, known: String?, idle: String?, failedIdle: Boolean = false) {
    val chat = session.chat
    val sites by chat.sites.collectAsState()
    val uri = LocalUriHandler.current
    val step = sites?.steps?.get(site)
    var asking by remember(site) { mutableStateOf(false) }
    val ask = { as_: String -> chat.signInSite(site, as_) { uri.openUri(it) }; asking = false }
    Column(verticalArrangement = Arrangement.spacedBy(arbay.gap / 2)) {
        when {
            step?.running == true -> SiteHead(title, step.status) {
                sites?.pages?.get(site)?.let { page -> OutlinedButton(onClick = { uri.openUri(page) }) { Text("Open") } }
            }
            asking -> {
                var typed by remember(site) { mutableStateOf("") }
                SiteHead(title, null) { TextButton(onClick = { asking = false }) { Text("Cancel") } }
                OutlinedTextField(
                    typed, { typed = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("E-mail") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (typed.isNotBlank()) ask(typed) }),
                )
                Button(onClick = { ask(typed) }, enabled = typed.isNotBlank()) { Text("Sign in") }
            }
            else -> {
                val status = idle ?: step?.status ?: "no sign-in yet"
                val signedIn = idle?.startsWith("Signed in") == true || (idle == null && step?.state == "signed-in")
                SiteHead(title, status, failed = failedIdle || (idle == null && step?.state == "failed")) {
                    OutlinedButton(onClick = { if (known != null) ask(known) else asking = true }) {
                        Text(if (signedIn) "Sign in again" else "Sign in")
                    }
                }
            }
        }
    }
}

/** A site's line: its name, what stands about it, and the one action it offers. */
@Composable
private fun SiteHead(name: String, status: String?, failed: Boolean = false, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        status?.let {
            if (failed) Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            else Muted(it, maxLines = 1)
        }
        action()
    }
}

/**
 * The send window: the all-in limit, the user's text once, and every picked seller with the price
 * they will be offered. A seller's line opens to change that one message alone.
 */
@Composable
fun WritePanel(session: Session, route: Route.Results, state: ResultsState) {
    val chat = session.chat
    val nav = LocalNavigator.current
    val selected by chat.selected.collectAsState()
    val settings by chat.settings.collectAsState()
    val account by chat.account.collectAsState()
    val fetched by session.listings.fetched.collectAsState()
    val searchId = (route.source as? Source.Saved)?.id
    val listings = remember(selected, fetched) { fetched.filter { it.id in selected } }

    var allIn by remember(searchId) { mutableStateOf(searchId?.let { settings.allInBySearch[it] }?.let { formatAmount(it) } ?: "") }
    var picked by remember { mutableStateOf(listOf<String>()) }
    var text by remember { mutableStateOf("") }
    val composed = composeBlocks(picked.mapNotNull { id -> settings.templates.firstOrNull { it.id == id } })
    val edits = remember { mutableStateMapOf<String, String>() }
    var openLine by remember { mutableStateOf<String?>(null) }
    val limit = allIn.replace(',', '.').toDoubleOrNull()
    // Each picked ad's own page: the shipping its card leaves out ("Versand ab 6,19 €"), and its
    // price and state now, since a seller who lowered the ask or paused the ad since the search
    // must not be offered what the search saw.
    val pageShipping = remember { mutableStateMapOf<String, io.github.tieo.arbay.model.Shipping>() }
    val adsNow = remember { mutableStateMapOf<String, io.github.tieo.arbay.model.AdNow>() }
    LaunchedEffect(listings.map { it.id }) {
        listings.filter { it.id !in adsNow }.forEach { l ->
            session.client.adNow(l)?.let { adsNow[l.id] = it }
            if (adsNow[l.id]?.shipping == null) session.client.listingDetail(l)?.shipping?.let { pageShipping[l.id] = it }
        }
    }
    val costs by chat.costs.collectAsState()
    val lines = planSend(listings, limit, text, edits, picked, pageShipping, settings.toDoor, costs, adsNow)
    val sendable = lines.filter { it.unreachable == null }
    val ready = account?.signedIn == true && sendable.isNotEmpty() && sendable.none { it.text.isBlank() || it.hasOpenFillIn }

    Text(if (listings.size == 1) "Write to the seller" else "Write to ${listings.size} sellers", style = MaterialTheme.typography.titleLarge)
    if (account?.signedIn != true) AccountPanel(session)
    if (listings.isEmpty()) { Muted("Nothing picked."); return }

    OutlinedTextField(
        allIn, { v -> allIn = v.filter { it.isDigit() || it == ',' || it == '.' } },
        Modifier.fillMaxWidth(), label = { Text("Most you pay, everything included") }, suffix = { Text("€") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
    Choices {
        Choice("Delivered to my door", settings.toDoor) { chat.setToDoor(true) }
        Choice("To a parcel shop", !settings.toDoor) { chat.setToDoor(false) }
    }
    if (settings.templates.isNotEmpty()) {
        Choices {
            settings.templates.forEach { t ->
                val place = picked.indexOf(t.id)
                Choice(if (place >= 0) "${place + 1} ${t.name}" else t.name, place >= 0) {
                    picked = if (place >= 0) picked - t.id else picked + t.id
                    text = composeBlocks(picked.mapNotNull { id -> settings.templates.firstOrNull { it.id == id } })
                    edits.clear()
                }
            }
        }
    }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Your message") }, minLines = 4)
    Choices {
        TEMPLATE_FILL_INS.forEach { (token, _) -> Choice(token, token in text) { text += token } }
    }
    if (text.isNotBlank() && text != composed) {
        var naming by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        if (!naming) TextButton(onClick = { naming = true }) { Text("Keep this text") }
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("Name it") }, singleLine = true)
            TextButton(enabled = name.isNotBlank(), onClick = {
                chat.saveTemplate(MessageTemplate(chat.newTemplateId(), name.trim(), text))
                naming = false
            }) { Text("Keep") }
        }
    }

    HorizontalDivider(color = arbay.line)
    lines.forEach { line ->
        SellerLine(line, open = openLine == line.listing.id,
            onToggle = { openLine = if (openLine == line.listing.id) null else line.listing.id },
            onEdit = { edits[line.listing.id] = it },
            onReset = { edits.remove(line.listing.id) },
            onDrop = { chat.toggleSelected(line.listing.id) },
        )
    }
    Button(
        enabled = ready,
        onClick = { chat.send(lines.toRequest(searchId, limit)) { nav.back() } },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (sendable.size == 1) "Send" else "Send ${sendable.size}") }
}

@Composable
private fun SellerLine(line: SendLine, open: Boolean, onToggle: () -> Unit, onEdit: (String) -> Unit, onReset: () -> Unit, onDrop: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = line.unreachable == null, onClick = onToggle).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(line.listing.title.tidyTitle(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val foot = line.unreachable ?: listOfNotNull(if (line.edited) "your own wording" else null, line.note).joinToString(" · ")
                if (foot.isNotEmpty()) Muted(foot, maxLines = 2)
            }
            line.price?.let { Text("$it €", style = arbay.priceStyle(MaterialTheme.typography.titleMedium)) }
            IconButton(onClick = onDrop) { Icon(Icons.Outlined.Close, "Leave out") }
        }
        if (open) {
            OutlinedTextField(line.text, onEdit, Modifier.fillMaxWidth(), minLines = 3)
            if (line.edited) TextButton(onClick = onReset) { Text("Use the shared text") }
        }
    }
}

private fun formatAmount(eur: Double): String = if (eur % 1.0 == 0.0) eur.toInt().toString() else eur.toString()

/** The user's own texts for sellers: each named, written and changed here, never by Arbay. */
@Composable
fun TextsPanel(session: Session) {
    val chat = session.chat
    val settings by chat.settings.collectAsState()
    LaunchedEffect(Unit) { chat.watch() }
    var editing by remember { mutableStateOf<MessageTemplate?>(null) }
    Panel {
        Text("Your texts", style = MaterialTheme.typography.titleMedium)
        Muted(TEMPLATE_FILL_INS.joinToString("  ·  ") { (token, means) -> "$token $means" })
        settings.templates.forEach { t ->
            if (editing?.id == t.id) TemplateEditor(editing!!, { editing = it }, onSave = { chat.saveTemplate(it); editing = null }, onDelete = { chat.deleteTemplate(t.id); editing = null })
            else Column(Modifier.fillMaxWidth().clickable { editing = t }.padding(vertical = 6.dp)) {
                Text(t.name, style = MaterialTheme.typography.bodyMedium)
                Muted(t.text, maxLines = 2)
            }
        }
        val draft = editing
        if (draft != null && settings.templates.none { it.id == draft.id }) {
            TemplateEditor(draft, { editing = it }, onSave = { chat.saveTemplate(it); editing = null }, onDelete = { editing = null })
        } else if (draft == null) {
            TextButton(onClick = { editing = MessageTemplate(chat.newTemplateId(), "", "") }) { Text("New text") }
        }
    }
}

@Composable
private fun TemplateEditor(t: MessageTemplate, onChange: (MessageTemplate) -> Unit, onSave: (MessageTemplate) -> Unit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(t.name, { onChange(t.copy(name = it)) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        OutlinedTextField(t.text, { onChange(t.copy(text = it)) }, Modifier.fillMaxWidth(), label = { Text("Text") }, minLines = 4)
        Choices { TEMPLATE_FILL_INS.forEach { (token, _) -> Choice(token, token in t.text) { onChange(t.copy(text = t.text + token)) } } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = t.name.isNotBlank() && t.text.isNotBlank(), onClick = { onSave(t.copy(name = t.name.trim())) }) { Text("Keep") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}
