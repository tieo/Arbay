package io.github.tieo.arbay

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.landedPrice

/**
 * What this listing costs to have here: what the market quotes, plus shipping, plus the import VAT
 * charged on the way in. This is the number every screen shows and sorts by, because comparing a
 * quoted price from outside the VAT area against one from inside compares two different things.
 * The market's own price is still on [Listing.effectivePrice], and the card shows it underneath.
 */
val Listing.comparablePrice: Money get() = landedPrice(ImportRules.current)
