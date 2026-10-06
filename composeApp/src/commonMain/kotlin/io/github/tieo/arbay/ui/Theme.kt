package io.github.tieo.arbay.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tieo.arbay.design.Brightness
import io.github.tieo.arbay.design.Look
import io.github.tieo.arbay.design.LookChoice
import io.github.tieo.arbay.design.Palette

/** The look in force, with what Material's colour scheme has no slot for: the colour that marks
 *  the cheapest offer, whether prices are set like a till receipt, and how much room things get. */
@Immutable
class ArbayLook(val look: Look, val palette: Palette, val dark: Boolean) {
    val lowest: Color get() = color(palette.lowest)
    val line: Color get() = color(palette.line)
    val raised: Color get() = color(palette.raised)
    val selected: Color get() = color(palette.selected)

    /** Space between rows and around a card's content. */
    val gap: Dp get() = when (look.density) { 0 -> 8.dp; 2 -> 16.dp; else -> 12.dp }
    val pad: Dp get() = when (look.density) { 0 -> 12.dp; 2 -> 20.dp; else -> 16.dp }
    val radius: Dp get() = look.radius.dp

    /** How a price is set: tabular figures always, monospaced where the look says so. */
    fun priceStyle(base: TextStyle): TextStyle =
        if (look.monoPrices) base.copy(fontFamily = FontFamily.Monospace, letterSpacing = (-0.5).sp) else base
}

val LocalArbayLook = staticCompositionLocalOf { ArbayLook(LookChoice.look, LookChoice.look.light, false) }

/** The look in force wherever it is read. */
val arbay: ArbayLook @Composable get() = LocalArbayLook.current

fun color(rgb: Long): Color = Color(0xFF000000 or rgb)

private fun schemeOf(p: Palette, dark: Boolean) = if (dark) darkColorScheme(
    primary = color(p.accent), onPrimary = color(p.onAccent),
    primaryContainer = color(p.accentSoft), onPrimaryContainer = color(p.text),
    secondary = color(p.accent), onSecondary = color(p.onAccent),
    secondaryContainer = color(p.selected), onSecondaryContainer = color(p.text),
    tertiary = color(p.lowest),
    background = color(p.background), onBackground = color(p.text),
    surface = color(p.surface), onSurface = color(p.text),
    surfaceVariant = color(p.raised), onSurfaceVariant = color(p.muted),
    surfaceContainerLowest = color(p.background), surfaceContainerLow = color(p.surface),
    surfaceContainer = color(p.surface), surfaceContainerHigh = color(p.raised), surfaceContainerHighest = color(p.raised),
    outline = color(p.line), outlineVariant = color(p.line),
    error = color(p.danger),
) else lightColorScheme(
    primary = color(p.accent), onPrimary = color(p.onAccent),
    primaryContainer = color(p.accentSoft), onPrimaryContainer = color(p.text),
    secondary = color(p.accent), onSecondary = color(p.onAccent),
    secondaryContainer = color(p.selected), onSecondaryContainer = color(p.text),
    tertiary = color(p.lowest),
    background = color(p.background), onBackground = color(p.text),
    surface = color(p.surface), onSurface = color(p.text),
    surfaceVariant = color(p.raised), onSurfaceVariant = color(p.muted),
    surfaceContainerLowest = color(p.background), surfaceContainerLow = color(p.surface),
    surfaceContainer = color(p.surface), surfaceContainerHigh = color(p.raised), surfaceContainerHighest = color(p.raised),
    outline = color(p.line), outlineVariant = color(p.line),
    error = color(p.danger),
)

private val type = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

/** Arbay's look, from the same tokens the browser draws with. [darkTheme] overrides the reader's
 *  brightness choice, for drawing a screen off-device in both. */
@Composable
fun ArbayTheme(darkTheme: Boolean? = null, content: @Composable () -> Unit) {
    val look = LookChoice.look
    val dark = darkTheme ?: when (LookChoice.brightness) {
        Brightness.SYSTEM -> isSystemInDarkTheme()
        Brightness.LIGHT -> false
        Brightness.DARK -> true
    }
    val palette = if (dark) look.dark else look.light
    val r = look.radius.dp
    MaterialTheme(
        colorScheme = schemeOf(palette, dark),
        typography = type,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(r * 0.5f),
            small = RoundedCornerShape(r * 0.7f),
            medium = RoundedCornerShape(r),
            large = RoundedCornerShape(r * 1.4f),
            extraLarge = RoundedCornerShape(r * 2f),
        ),
    ) {
        CompositionLocalProvider(LocalArbayLook provides ArbayLook(look, palette, dark), content = content)
    }
}
