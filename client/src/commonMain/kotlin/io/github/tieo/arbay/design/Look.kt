package io.github.tieo.arbay.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.loadDeviceSettings
import io.github.tieo.arbay.saveDeviceSettings

/**
 * How Arbay looks, defined once for every app that draws it: the phone builds its theme from these
 * colours and the browser turns them into its stylesheet's variables, so the two never drift.
 *
 * Colours are 0xRRGGBB. [lowest] is the one colour with a meaning of its own: it marks the cheapest
 * offer and nothing else, and wherever it appears the thing it marks also differs in form.
 */
data class Palette(
    val background: Long,
    val surface: Long,
    val raised: Long,
    val line: Long,
    val text: Long,
    val muted: Long,
    val accent: Long,
    val onAccent: Long,
    val accentSoft: Long,
    val lowest: Long,
    val selected: Long,
    val danger: Long,
)

/** One of the looks offered, with a light and a dark palette and the shape of its type and corners. */
data class Look(
    val id: String,
    val name: String,
    val light: Palette,
    val dark: Palette,
    /** Corner radius of cards and controls, in dp/px. */
    val radius: Int,
    /** Whether prices are set in a monospaced face, as on a till receipt. */
    val monoPrices: Boolean,
    /** Space between rows and inside cards: 0 is compact, 2 is roomy. */
    val density: Int,
)

/** The looks to choose from while one is being settled on. */
object Looks {
    val receipt = Look(
        id = "receipt",
        name = "Receipt",
        light = Palette(
            background = 0xF4F1EA, surface = 0xFBF9F4, raised = 0xEDE8DD, line = 0xDDD6C8,
            text = 0x1E1C18, muted = 0x6E675B, accent = 0x1E1C18, onAccent = 0xFBF9F4,
            accentSoft = 0xE8E2D4, lowest = 0x23803A, selected = 0xEAE4D6, danger = 0xB3261E,
        ),
        dark = Palette(
            background = 0x161512, surface = 0x1D1B18, raised = 0x27251F, line = 0x34312A,
            text = 0xEDE8DD, muted = 0xA39C8E, accent = 0xEDE8DD, onAccent = 0x161512,
            accentSoft = 0x2C2A24, lowest = 0x6CCB7F, selected = 0x2A2822, danger = 0xF2B8B5,
        ),
        radius = 6,
        monoPrices = true,
        density = 1,
    )

    val studio = Look(
        id = "studio",
        name = "Studio",
        light = Palette(
            background = 0xF3F4F6, surface = 0xFFFFFF, raised = 0xEEF0F3, line = 0xE2E5EA,
            text = 0x15171A, muted = 0x5E6672, accent = 0x2F5BEA, onAccent = 0xFFFFFF,
            accentSoft = 0xE6ECFD, lowest = 0x15803D, selected = 0xEAF0FE, danger = 0xC62828,
        ),
        dark = Palette(
            background = 0x0F1115, surface = 0x171A20, raised = 0x1F232B, line = 0x2A2F38,
            text = 0xE8EBF0, muted = 0x98A1AE, accent = 0x8AA8FF, onAccent = 0x0F1115,
            accentSoft = 0x1E2740, lowest = 0x4ADE80, selected = 0x1C2333, danger = 0xFF8A80,
        ),
        radius = 14,
        monoPrices = false,
        density = 2,
    )

    val graphite = Look(
        id = "graphite",
        name = "Graphite",
        light = Palette(
            background = 0xEDEEEC, surface = 0xF7F7F6, raised = 0xE4E5E2, line = 0xD3D5D0,
            text = 0x111211, muted = 0x5B5F59, accent = 0x0F766E, onAccent = 0xFFFFFF,
            accentSoft = 0xD9EEEB, lowest = 0x15803D, selected = 0xDDE7E5, danger = 0xB42318,
        ),
        dark = Palette(
            background = 0x0B0C0C, surface = 0x121414, raised = 0x1A1D1D, line = 0x262A2A,
            text = 0xDCE0DE, muted = 0x858C89, accent = 0x5EEAD4, onAccent = 0x0B0C0C,
            accentSoft = 0x123330, lowest = 0x86EFAC, selected = 0x17201F, danger = 0xFDA29B,
        ),
        radius = 4,
        monoPrices = true,
        density = 0,
    )

    val all = listOf(receipt, studio, graphite)

    fun byId(id: String?): Look = all.firstOrNull { it.id == id } ?: receipt
}

/** Light, dark, or whatever the device is set to. */
enum class Brightness(val label: String) { SYSTEM("Follow the device"), LIGHT("Light"), DARK("Dark") }

/** How a list of offers is laid out: rows to read and compare, or photos to look at. */
enum class OfferLayout(val label: String) { ROWS("Rows"), PHOTOS("Photos") }

/** The look this device uses, kept with the device's other choices. Observable, so changing it in
 *  Settings redraws every screen at once. */
object LookChoice {
    var look: Look by mutableStateOf(Looks.byId(loadDeviceSettings()["look"]))
        private set
    var brightness: Brightness by mutableStateOf(
        loadDeviceSettings()["brightness"]?.let { runCatching { Brightness.valueOf(it) }.getOrNull() } ?: Brightness.SYSTEM,
    )
        private set
    var layout: OfferLayout by mutableStateOf(
        loadDeviceSettings()["offerLayout"]?.let { runCatching { OfferLayout.valueOf(it) }.getOrNull() } ?: OfferLayout.ROWS,
    )
        private set

    fun choose(look: Look) { this.look = look; remember("look", look.id) }
    fun choose(brightness: Brightness) { this.brightness = brightness; remember("brightness", brightness.name) }
    fun choose(layout: OfferLayout) { this.layout = layout; remember("offerLayout", layout.name) }

    private fun remember(key: String, value: String) = saveDeviceSettings(loadDeviceSettings() + (key to value))
}
