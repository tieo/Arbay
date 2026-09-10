package io.github.tieo.arbay.ui.screen

import io.github.tieo.arbay.model.DropReason

/** What the rule behind a reason is actually doing, in the words of the thing it is looking at. */
fun explainDropReason(reason: DropReason?): String = when (reason) {
    DropReason.MARKET_IGNORED_SEARCH ->
        "Not one listing this market sent carries a word of the search, so it answered a " +
            "different question and its whole answer was set aside."
    DropReason.OFF_TARGET ->
        "The title carries too few of the words searched for."
    DropReason.ONE_OF_SEVERAL_SIZES ->
        "One listing selling the same thing in several sizes, whose price is the smallest one."
    DropReason.BUILT_INTO_A_DEVICE ->
        "The title names a machine of its own and lists the thing searched for among its parts."
    DropReason.ACCESSORY ->
        "The title reads as a part or add-on made for the thing rather than the thing itself."
    DropReason.CONSUMABLE ->
        "The title reads as something the thing uses up — paper, bags, filters."
    DropReason.WANTED_AD ->
        "The title reads as someone asking to buy one, or as a job ad."
    DropReason.RENTAL ->
        "The title offers it for hire, so its price is a rate rather than what one costs."
    DropReason.NOT_A_SINGLE_OFFER ->
        "A placeholder title, or a bulk lot priced for many units."
    DropReason.TOO_FAR ->
        "Further from where this search is centred than it reaches. A listing whose market never " +
            "says where it is stays, since not knowing is not the same as being far."
    DropReason.BLOCKED_WORD ->
        "Carries a word you blocked for this search. Drop the word in Filters to see these again."
    DropReason.IMPLAUSIBLE_PRICE ->
        "The price read off the page is not a price — digits from several fields run together."
    null -> ""
}
