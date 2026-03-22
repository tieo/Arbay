package io.github.tieo.arbay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ArbayGreen = Color(0xFF2E7D32)
private val ArbayGreenLight = Color(0xFF60AD5E)
private val ArbayGreenDark = Color(0xFF005005)
private val ArbayTeal = Color(0xFF00796B)
private val ArbayOrange = Color(0xFFE65100)

private val LightColors = lightColorScheme(
    primary = ArbayGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF002106),
    secondary = ArbayTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = ArbayOrange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFCCBC),
    onTertiaryContainer = Color(0xFF3E0400),
    error = Color(0xFFB00020),
    surface = Color(0xFFFCFDF7),
    onSurface = Color(0xFF1A1C18),
    surfaceVariant = Color(0xFFDEE5D9),
    onSurfaceVariant = Color(0xFF424940),
    outline = Color(0xFF72796F),
    outlineVariant = Color(0xFFC2C9BD),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F7F1),
    surfaceContainer = Color(0xFFF0F1EB),
    surfaceContainerHigh = Color(0xFFEBECE6),
    surfaceContainerHighest = Color(0xFFE5E6E0),
)

private val DarkColors = darkColorScheme(
    primary = ArbayGreenLight,
    onPrimary = Color(0xFF003910),
    primaryContainer = Color(0xFF005319),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFB2DFDB),
    tertiary = Color(0xFFFF8A65),
    onTertiary = Color(0xFF5F1600),
    tertiaryContainer = Color(0xFF862200),
    onTertiaryContainer = Color(0xFFFFCCBC),
    error = Color(0xFFCF6679),
    surface = Color(0xFF121410),
    onSurface = Color(0xFFE2E3DD),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BD),
    outline = Color(0xFF8C9388),
    outlineVariant = Color(0xFF424940),
    surfaceContainerLowest = Color(0xFF0C0F0B),
    surfaceContainerLow = Color(0xFF1A1C18),
    surfaceContainer = Color(0xFF1E201C),
    surfaceContainerHigh = Color(0xFF282B26),
    surfaceContainerHighest = Color(0xFF333631),
)

@Composable
fun ArbayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ArbayTypography,
        content = content,
    )
}
