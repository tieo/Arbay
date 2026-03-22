package io.github.tieo.arbay.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.model.Alert
import io.github.tieo.arbay.model.AlertType
import io.github.tieo.arbay.ui.viewmodel.AlertViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsSheet(
    alertViewModel: AlertViewModel,
    onDismiss: () -> Unit,
) {
    val alerts by alertViewModel.alerts.collectAsState()
    val unreadCount by alertViewModel.unreadCount.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Alerts",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (unreadCount > 0) {
                    TextButton(onClick = { alertViewModel.markAllRead() }) {
                        Icon(Icons.Default.DoneAll, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Mark all read", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (alerts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.NotificationsNone,
                            null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No alerts",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    items(alerts, key = { it.id }) { alert ->
                        AlertRow(
                            alert = alert,
                            onMarkRead = { alertViewModel.markRead(alert.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertRow(alert: Alert, onMarkRead: () -> Unit) {
    val containerColor by animateColorAsState(
        if (!alert.read) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surfaceContainer,
    )

    val (icon, label) = alertTypeInfo(alert.type)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (!alert.read) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    alert.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!alert.read) {
                IconButton(
                    onClick = onMarkRead,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        "Mark read",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private data class AlertInfo(val icon: ImageVector, val label: String)

private fun alertTypeInfo(type: AlertType): AlertInfo = when (type) {
    AlertType.PRICE_BELOW -> AlertInfo(Icons.AutoMirrored.Filled.TrendingDown, "Price below threshold")
    AlertType.PRICE_DROP_PERCENT -> AlertInfo(Icons.Default.Discount, "Price drop")
    AlertType.NEW_LISTING -> AlertInfo(Icons.Default.NewReleases, "New listing")
    AlertType.ARBITRAGE -> AlertInfo(Icons.AutoMirrored.Filled.CompareArrows, "Arbitrage")
}
