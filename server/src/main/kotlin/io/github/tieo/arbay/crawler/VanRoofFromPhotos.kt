package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.VanSize
import io.github.tieo.arbay.repo.ListingArchive

/**
 * How tall a van's roof is, told from its photos ([PhotoLikeness]) where its ad does not settle it:
 * most van ads name no roof at all, and a seller's "Hochdach" leaves high and super-high open.
 *
 * The examples are the archived closed vans of the same model whose roof their own ad settles to one
 * size ([VanDimensions]), or a reviewer checked ([PhotoFactChecks]); each model on
 * its own, since every maker's roofs are its own heights and its own shapes. A roof told from photos
 * stays inside what the ad's words leave open.
 */
object VanRoofFromPhotos {
    /** The fact [PhotoFactChecks] keeps the roof's verdicts under. */
    const val FACT = "van roof"

    private val tellers = PhotoLikeness.Tellers()

    /** The van model of [l], as [VanDimensions] names it. */
    fun modelOf(l: Listing): String? = VanDimensions.modelOf(l.title)

    /**
     * The roof [l]'s own ad settles it to, on the [VanSize] scale, or null where it leaves it open.
     * Read again from its words rather than taken from the archived copy, which holds what the
     * reading of the day it was found made of them.
     */
    internal fun settled(l: Listing): Int? {
        val reading = VanDimensions.read("${l.title} ${l.description.orEmpty()}", wheelbaseMm = l.vehicle?.wheelbaseMm)
        return reading.height.takeIf { reading.heightSure }
    }

    // A van without a closed box has no roof to tell: a tipper, a flatbed, a car transporter, a
    // chassis cab or a box body built on one shows its cab and a load, nothing of the van's roof.
    private val noVanRoof = Regex(
        """\b(pritsche|pritschenwagen|kipper|dreiseitenkipper|fahrgestell|chassis|autotransporter|abschleppwagen|""" +
            """plane|koffer|kofferaufbau|doka|doppelkabine|pick-?up|tipper|bakwagen|open\s+laadbak|kraan)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [l] is a closed van or bus, whose roof the photos show. */
    internal fun hasAVanRoof(l: Listing): Boolean = !noVanRoof.containsMatchIn(l.title)

    private fun label(roof: Int) = VanSize.roofLabel(roof)
    private fun roof(label: String) = VanSize.roofs.firstOrNull { label(it) == label }

    /** The archived vans of [model] whose roof is known, labelled by the roof's name. */
    fun examples(model: String): List<PhotoLikeness.Example> {
        val checks = PhotoFactChecks.all(FACT)
        return PhotoLikeness.examples(ListingArchive.all().filter { modelOf(it) == model && hasAVanRoof(it) }.mapNotNull { l ->
            val roof = checks[offerKey(l.id)]?.toIntOrNull() ?: settled(l)
            roof?.let { l to label(it) }
        })
    }

    /** Every way of telling measured on [model]'s examples. */
    fun measure(model: String): PhotoLikeness.Measured = PhotoLikeness.measure(examples(model))

    /**
     * The roof [l]'s photos show, where its ad leaves it open and that roof is told right often
     * enough, with its record as confidence.
     */
    fun tell(l: Listing): Pair<Int, Double>? {
        if (settled(l) != null || !hasAVanRoof(l)) return null
        val model = modelOf(l) ?: return null
        val v = l.vehicle
        // What the words leave open: "Hochdach" is high or higher, so a photo may say which.
        val low = v?.vanHeight?.takeIf { v.vanHeightMax != null } ?: VanSize.roofs.first()
        val high = v?.vanHeightMax ?: VanSize.roofs.last()
        val teller = tellers.get(model) { PhotoLikeness.Teller.of(examples(model), "the roof of a $model") }
        val (name, share) = teller.tell(l) { roof(it)?.let { r -> r in low..high } == true } ?: return null
        return roof(name)?.let { it to share }
    }
}
