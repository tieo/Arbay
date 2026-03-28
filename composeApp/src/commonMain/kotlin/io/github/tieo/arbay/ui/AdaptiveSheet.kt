package io.github.tieo.arbay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

val LocalDesktopMode = compositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveSheet(

    onDismiss: () -> Unit,
    widthFraction: Float = 0.55f,
    maxWidth: Int = 640,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalDesktopMode.current) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = maxWidth.dp)
                    .fillMaxWidth(widthFraction)
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    content = content,
                )
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveFormSheet(
    onDismiss: () -> Unit,
    maxWidth: Int = 520,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalDesktopMode.current) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = maxWidth.dp)
                    .fillMaxWidth(0.45f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 6.dp,
            ) {
                Column(content = content)
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            content()
        }
    }
}
