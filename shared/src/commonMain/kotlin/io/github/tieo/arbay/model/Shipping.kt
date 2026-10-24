package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
data class Shipping(
    val cost: Money? = null,
    val free: Boolean = false,
    val pickup: Boolean = false,
    val available: Boolean = true,
    /** What delivery to the buyer's door costs, where [cost] is a parcel shop price; from the market's own price list. */
    val doorCost: Money? = null,
    /** The service [doorCost] is the price of, such as "DHL Paket 2 kg". */
    val doorBy: String? = null,
)

/**
 * Words a seller uses to say the thing is not sent, whatever the market's shipping box says: four
 * drive ads on 2026-10-24 offered Kleinanzeigen shipping and wrote "Nur Abholung, da mir
 * Festplattenversand zu riskant ist", "Versand biete ich aufgrund der fragilen Natur von Festplatten
 * nicht an", "Ich versende wegen des Versandrisikos keine Festplatten mehr" and "Nur an Selbstabholer".
 * Not sending abroad is still sending.
 */
private val pickupOnlyWords = Regex(
    """nur\s+(an\s+)?["„“]?(selbst)?abhol|nur\s+zur\s+abholung|kein(en)?\s+versand|versand\s+(ist\s+)?(leider\s+)?(nicht\s+möglich|ausgeschlossen)|""" +
        """versende\s+(\S+\s+){0,5}?(nicht|keine?n?)\b(?!\s+(ins|nach)\s+(ausland|übersee))|versand\s+biete\s+ich\s+(\S+\s+){0,8}?nicht\s+an""",
    RegexOption.IGNORE_CASE,
)

/** Whether the ad's own text says it is only for collection. */
fun saysPickupOnly(text: String?): Boolean = text != null && pickupOnlyWords.containsMatchIn(text)

/** The shipping as the ad's own words leave it: none, where the seller writes that nothing is sent. */
fun Shipping?.asTheTextSays(text: String?): Shipping? =
    if (saysPickupOnly(text)) (this ?: Shipping()).copy(available = false, pickup = true, cost = null, free = false, doorCost = null, doorBy = null)
    else this
