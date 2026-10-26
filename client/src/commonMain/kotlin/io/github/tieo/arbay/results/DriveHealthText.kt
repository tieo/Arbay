package io.github.tieo.arbay.results

import io.github.tieo.arbay.grouped
import io.github.tieo.arbay.model.DriveVerdict
import io.github.tieo.arbay.model.SmartReading

/** A verdict in a word or two, as a buyer says it. */
val DriveVerdict.label: String
    get() = when (this) {
        DriveVerdict.FAILING -> "Failing"
        DriveVerdict.WORN -> "Wearing"
        DriveVerdict.CLEAN -> "Clean"
        DriveVerdict.UNREADABLE -> "Not enough shown"
    }

/** The values one screenshot showed: "37.150 h · 0 reallocated · 0 pending · 0 uncorrectable", or an
 *  NVMe drive's "6.259 h · 0 uncorrectable · 1 % worn · 100 % spare". */
fun SmartReading.values(): String = listOfNotNull(
    powerOnHours?.let { "${grouped(it, '.')} h" },
    reallocated?.let { "${grouped(it, '.')} reallocated" },
    pending?.let { "${grouped(it, '.')} pending" },
    uncorrectable?.let { "${grouped(it, '.')} uncorrectable" },
    wearUsed?.let { "$it % worn" },
    spareLeft?.let { "$it % spare" },
).joinToString(" · ")
