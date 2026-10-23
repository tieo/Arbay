package io.github.tieo.arbay.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.model.AdDraft
import io.github.tieo.arbay.model.AdPriceType
import io.github.tieo.arbay.model.postedUrl
import io.github.tieo.arbay.navigation.Route
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.rememberPhotoPicker
import io.github.tieo.arbay.model.problem
import io.github.tieo.arbay.model.saleLine

/**
 * One ad for sale: its photos, title, text, details and price, changed here and posted on
 * Kleinanzeigen with the one button at the bottom. A posted ad keeps its photos and details; its
 * title, text and price change here and go to the market with the button that appears once one did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SellScreen(session: Session, draftId: String) {
    val sell = session.sell
    val nav = LocalNavigator.current
    val open by sell.open.collectAsState()
    val busy by sell.busy.collectAsState()
    val error by sell.error.collectAsState()
    val account by session.chat.account.collectAsState()
    sell.drafts.collectAsState().value
    LaunchedEffect(draftId) { sell.open(draftId) }
    val d = open?.takeIf { it.id == draftId }
    val posted = d?.postedAdId != null
    val pickPhotos = rememberPhotoPicker { sell.addPhotos(it) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                title = { Text(if (posted) "Your ad" else "Ad for sale", maxLines = 1) },
                actions = {
                    d?.postedUrl()?.let { url -> IconButton(onClick = { openBrowser(url) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open the ad") } }
                    if (d != null && !posted) IconButton(onClick = { sell.delete(d.id) { nav.back() } }) { Icon(Icons.Outlined.Delete, "Throw the draft away") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (d != null && (!posted || sell.unsaved(d))) {
                val problem = d.problem() ?: if (account?.signedIn != true) "Sign in to Kleinanzeigen first" else null
                Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = arbay.pad, vertical = 8.dp)) {
                    problem?.let { Muted(it) }
                    Button(enabled = problem == null && busy == null, onClick = { if (posted) sell.save() else sell.post() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (posted) "Save on Kleinanzeigen" else "Post on Kleinanzeigen")
                    }
                }
            }
        },
    ) { padding ->
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Busy() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(arbay.pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            busy?.let { item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Busy(); Muted(it) } } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            item {
                Photos(d, posted, photo = { sell.photoAddress(d, it) }, onAdd = pickPhotos.takeIf { busy == null }) {
                    sell.change(d.copy(photos = d.photos - it))
                }
            }
            item {
                OutlinedTextField(d.title, { sell.change(d.copy(title = it)) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
            }
            item {
                OutlinedTextField(d.description, { sell.change(d.copy(description = it)) }, Modifier.fillMaxWidth(), label = { Text("Text") }, minLines = 8)
            }
            item { PriceFields(d) { sell.change(it) } }
            item { Details(d, posted) { sell.change(it) } }
        }
    }
}

@Composable
private fun Photos(d: AdDraft, posted: Boolean, photo: (String) -> String, onAdd: (() -> Unit)?, onRemove: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // First, where a row of many photos still shows it without scrolling.
        if (!posted && onAdd != null) item(key = "add") {
            Box(
                Modifier.size(width = 96.dp, height = 128.dp).clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.AddAPhoto, "Add your own photos", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(d.photos, key = { it }) { p ->
            Box {
                Photo(photo(p), Modifier.size(width = 96.dp, height = 128.dp).clip(MaterialTheme.shapes.small), "Photo $p")
                if (!posted) IconButton(onClick = { onRemove(p) }, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                    Icon(Icons.Outlined.Close, "Leave this photo out", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun PriceFields(d: AdDraft, onChange: (AdDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (d.priceType != AdPriceType.GIVE_AWAY) OutlinedTextField(
            d.priceEur?.toString().orEmpty(),
            { v -> onChange(d.copy(priceEur = v.filter(Char::isDigit).take(7).toIntOrNull())) },
            Modifier.fillMaxWidth(), label = { Text("Price") }, suffix = { Text("€") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Choices { AdPriceType.entries.forEach { t -> Choice(t.label, d.priceType == t) { onChange(d.copy(priceType = t)) } } }
        Choices {
            Choice("Ships", d.shipping) { onChange(d.copy(shipping = true)) }
            Choice("Pickup only", !d.shipping) { onChange(d.copy(shipping = false, buyNow = false)) }
            if (d.shipping) Choice("Direkt kaufen", d.buyNow) { onChange(d.copy(buyNow = !d.buyNow)) }
        }
    }
}

/** The details the post form asks for, as the form words them; a value cleared drops the detail. */
@Composable
private fun Details(d: AdDraft, posted: Boolean, onChange: (AdDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("Details")
        d.attributes.forEach { (label, value) ->
            OutlinedTextField(
                value, { v -> onChange(d.copy(attributes = if (v.isEmpty()) d.attributes - label else d.attributes + (label to v))) },
                Modifier.fillMaxWidth(), label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true, readOnly = posted,
            )
        }
    }
}

/** The user's ads for sale, for the messages screen: each opens its draft. */
@Composable
fun ForSaleRows(session: Session) {
    val nav = LocalNavigator.current
    val drafts by session.sell.drafts.collectAsState()
    LaunchedEffect(Unit) { session.sell.load() }
    if (drafts.isEmpty()) return
    SectionTitle("For sale", Modifier.padding(horizontal = arbay.pad, vertical = 8.dp))
    drafts.forEach { d ->
        Row(
            Modifier.fillMaxWidth().clickable { nav.go(Route.Sell(d.id)) }.padding(horizontal = arbay.pad, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Photo(d.photos.firstOrNull()?.let { session.sell.photoAddress(d, it) }, Modifier.size(52.dp).clip(MaterialTheme.shapes.small), d.title)
            Column(Modifier.weight(1f)) {
                Text(d.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(d.saleLine(), maxLines = 1)
            }
        }
    }
}
