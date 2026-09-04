package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.tieo.arbay.imageModel
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.ui.AdaptiveSheet

/**
 * A listing's full content, in the app — the whole reason it needs to be here at all rather than
 * a card that always jumps straight to the platform's own page. Backing an ad up (see
 * [io.github.tieo.arbay.api.ArbayClient.getArchivedListing]) only means something if there is
 * somewhere to see the backup once the live page is gone; this is that somewhere, and it renders
 * a live listing exactly the same way, so nothing about it looks like a fallback path.
 */
@Composable
fun ListingDetailSheet(
    listing: Listing,
    // True when this copy came from the archive rather than the live listing already in memory —
    // shown as a banner, since "removed from the platform" is worth knowing before "open on site"
    // is tapped and does nothing.
    isArchived: Boolean = false,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                listing.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (isArchived) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Archive, null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            "No longer on ${listing.platformId.displayName} — showing your saved copy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            if (listing.imageUrls.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listing.imageUrls) { url ->
                        AsyncImage(
                            model = imageModel(url),
                            contentDescription = listing.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 260.dp, height = 200.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    listing.effectivePrice.format(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                listing.oldPrice?.let {
                    Text(
                        it.format(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough,
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailChip(listing.platformId.displayName)
                listing.condition?.let { DetailChip(it.name.lowercase().replace('_', ' ')) }
                listing.location?.let { loc ->
                    (loc.city ?: loc.raw ?: loc.country)?.let { DetailChip(it) }
                }
                if (listing.shipping?.free == true) DetailChip("Free shipping")
                if (listing.negotiable) DetailChip("Negotiable")
            }

            listing.seller?.let { seller ->
                Text(
                    "Sold by ${seller.name}" + (seller.rating?.let { " · ${"%.1f".format(it)}★" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            listing.description?.takeIf { it.isNotBlank() }?.let {
                Text("Description", style = MaterialTheme.typography.labelLarge)
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { openBrowser(listing.url) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isArchived) "Try the original page" else "Open on ${listing.platformId.displayName}")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DetailChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
