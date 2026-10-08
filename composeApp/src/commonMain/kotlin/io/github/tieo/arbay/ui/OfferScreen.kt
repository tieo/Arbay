package io.github.tieo.arbay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.format
import io.github.tieo.arbay.model.AUCTION_LEAD_CHOICES
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.tidyTitle
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.results.ResultsState
import io.github.tieo.arbay.results.againstMiddle
import io.github.tieo.arbay.results.copyLabel
import io.github.tieo.arbay.chat.sellerMark
import io.github.tieo.arbay.state.OfferNotes
import androidx.compose.material3.TextButton
import io.github.tieo.arbay.model.canMessage
import io.github.tieo.arbay.navigation.Panel
import io.github.tieo.arbay.navigation.Route
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import io.github.tieo.arbay.results.detailSpecs
import io.github.tieo.arbay.results.importVatNote
import io.github.tieo.arbay.results.offerFacts
import io.github.tieo.arbay.results.readOffer
import io.github.tieo.arbay.results.sourceLabel

/**
 * One offer in full, over the list it came from: its photos, its price against the others, a way to
 * each market carrying it, every fact its market published (what was read out of the words rather
 * than stated is marked), and its description. An offer its market has since removed is shown as it
 * was kept when it was found.
 */
@Composable
fun OfferScreen(session: Session, id: String, state: ResultsState) {
    val listings by session.listings.listings.collectAsState()
    val fetched by session.listings.fetched.collectAsState()
    val live = listings.firstOrNull { it.id == id } ?: fetched.firstOrNull { it.id == id }
    var archived by remember(id) { mutableStateOf<Listing?>(null) }
    LaunchedEffect(id, live == null) { if (live == null) archived = session.client.getArchivedListing(id) }
    val listing = live ?: archived
    val nav = LocalNavigator.current

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (listing == null) {
            Bare("", onBack = { nav.back() }) { Nothing("Looking for this offer") }
        } else {
            Offer(session, listing, state.elsewhere[listing.id].orEmpty(), isArchived = live == null, state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Offer(session: Session, listing: Listing, copies: List<Listing>, isArchived: Boolean, state: ResultsState) {
    val nav = LocalNavigator.current
    var fromItsPage by remember(listing.id) { mutableStateOf<ListingDetail?>(null) }
    var reading by remember(listing.id) { mutableStateOf(false) }
    LaunchedEffect(listing.id) {
        if (isArchived || listing.url.isBlank()) return@LaunchedEffect
        reading = true
        fromItsPage = session.client.listingDetail(listing)
        reading = false
    }
    val page = readOffer(listing, fromItsPage)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Muted(sourceLabel(listing, copies), maxLines = 1) },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    if (!isArchived) IconButton(onClick = { (listOf(listing) + copies).forEach(session.listings::ban); nav.back() }) {
                        Icon(Icons.Outlined.VisibilityOff, "Hide this offer")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Photos(listing)
            Column(Modifier.padding(horizontal = arbay.pad, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isArchived) Panel { Text("No longer on ${listing.platformId.displayName}. This is the copy kept when it was found.", style = MaterialTheme.typography.bodySmall) }
                Text(listing.title.tidyTitle(), style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PriceText(listing.comparablePrice, lowest = listing.id in state.summary.cheapestIds, style = MaterialTheme.typography.headlineMedium)
                    listing.oldPrice?.let { Muted(it.format(), style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.LineThrough)) }
                }
                state.summary.againstMiddle(listing)?.let { Muted(it, style = MaterialTheme.typography.bodyMedium) }
                if (OfferNotes.notes.collectAsState().value[listing.id] != null) Panel {
                    NoteLine(listing.id, full = true)
                    TextButton(onClick = { OfferNotes.set(listing.id, null) }) { Text("Remove the note") }
                }
                importVatNote(listing)?.let { Muted(it) }

                Button(onClick = { openBrowser(listing.url) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); HGap(); Text("Open on ${listing.platformId.displayName}")
                }
                copies.forEach { copy ->
                    OutlinedButton(onClick = { openBrowser(copy.url) }, modifier = Modifier.fillMaxWidth()) { Text(copyLabel(copy, listing)) }
                }
                if (listing.platformId.canMessage && !isArchived) SellerButton(session, listing)

                if (listing.saleType == SaleType.AUCTION && listing.auctionEndsAt != null && !isArchived) AuctionReminder(session, listing)

                val facts = offerFacts(listing, page.place)
                if (facts.isNotEmpty()) Choices { facts.forEach { Box(Modifier.padding(vertical = 3.dp)) { Fact(it) } } }

                page.vehicle?.let { v ->
                    val specs = detailSpecs(v)
                    if (specs.isNotEmpty()) {
                        Gap(4.dp)
                        SectionTitle("What the market says it is")
                        specs.forEach { spec ->
                            val stated = spec.stated(v)
                            Row(Modifier.fillMaxWidth()) {
                                Muted(spec.label, Modifier.weight(0.45f), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (stated) spec.value else "~${spec.value}, read from the ad",
                                    Modifier.weight(0.55f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = if (stated) FontStyle.Normal else FontStyle.Italic,
                                )
                            }
                        }
                    }
                }

                page.description?.takeIf { it.isNotBlank() }?.let {
                    Gap(4.dp)
                    SectionTitle("Description")
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (reading) Muted("Reading the rest off the ad")
            }
        }
    }
}

/** The photos, one screen wide, swiped through, with where in them the reader is. */
@Composable
private fun Photos(listing: Listing) {
    val images = listing.imageUrls.filter { it.isNotBlank() }
    if (images.isEmpty()) return
    val pager = rememberPagerState { images.size }
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(arbay.raised)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
            Photo(images[i], Modifier.fillMaxSize(), listing.title, contentScale = ContentScale.Fit)
        }
        if (images.size > 1) {
            Text(
                "${pager.currentPage + 1} / ${images.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** An auction runs out whether or not the app is open, so the useful thing is to be told a chosen
 *  stretch before it does, while a bid can still be made. */
/** The conversation with this offer's seller, or the way to start one. */
@Composable
private fun SellerButton(session: Session, listing: Listing) {
    val nav = LocalNavigator.current
    val conversations by session.chat.conversations.collectAsState()
    val outbox by session.chat.outbox.collectAsState()
    val conversation = conversations.firstOrNull { it.listingId == listing.id && it.buying }
    val mark = sellerMark(conversation, outbox.lastOrNull { it.listingId == listing.id })
    OutlinedButton(
        onClick = {
            if (conversation != null) nav.go(Route.Conversation(conversation.id))
            else (nav.current as? Route.Results)?.let { here ->
                if (listing.id !in session.chat.selected.value) session.chat.toggleSelected(listing.id)
                nav.go(here.copy(listing = null, panel = Panel.WRITE))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp)); HGap()
        Text(if (conversation != null) "Conversation" + (conversation.partner?.let { " with $it" } ?: "") else "Write to the seller")
    }
    mark?.let { Muted(it) }
}

@Composable
private fun AuctionReminder(session: Session, listing: Listing) {
    var lead by remember(listing.id) { mutableStateOf<Int?>(null) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Remind me before it ends", style = MaterialTheme.typography.bodyMedium)
        }
        Choices {
            AUCTION_LEAD_CHOICES.forEach { (minutes, label) ->
                Choice(label, lead == minutes) {
                    lead = if (lead == minutes) null else minutes
                    session.listings.remindBeforeAuction(listing, lead)
                }
            }
        }
    }
}
