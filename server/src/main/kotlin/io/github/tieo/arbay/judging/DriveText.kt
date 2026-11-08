package io.github.tieo.arbay.judging

import io.github.tieo.arbay.model.DriveInterface
import io.github.tieo.arbay.model.DriveRecording

/**
 * The facts of a drive as an offer's words and model number state them. What a model number decodes
 * to outranks what the words around it say; words that contradict each other ("SATA/SAS", "8TB,
 * 10TB, 12TB") are read as not stated.
 */
object DriveText {
    private const val OPT = "(?i)"

    private val MODEL = listOf(
        """\bST\d{3,5}[A-Z]{2}\d{3,4}[A-Z]?(?:-[0-9A-Z]{6})?\b""",
        """\bWD\d{2,4}[A-Z]{4}(?:-[0-9A-Z]{2,8})?\b""",
        """\bWDB[A-Z]{3}\d{4}[A-Z]{3}(?:-[A-Z0-9]{2,6})?\b""",
        """\b[WH]U[HSA]7\d{5}[A-Z0-9]{2,7}\b""",
        """\b[WH]S[HM]72\d{4}[A-Z0-9]{2,7}\b""",
        """\bMG\d{2}[A-Z]{3}\d{2,3}T[A-Z]{0,3}\b""",
        """\bMN\d{2}[A-Z]{3}\d{2,3}T[A-Z]{0,3}\b""",
        """\bHDW[A-Z]\d{2,3}[A-Z0-9]{0,8}\b""",
    ).joinToString("|", prefix = "$OPT(?:", postfix = ")").toRegex()

    /** The model numbers named in [text], first as written. */
    fun models(text: String): List<String> = MODEL.findAll(text).map { it.value.uppercase() }.distinct().toList()

    /** Gigabytes a model number encodes, or null where it encodes none this reads. */
    fun capacityOfModel(model: String): Int? {
        val m = model.substringBefore('-')
        Regex("^ST(\\d{3,5})[A-Z]").find(m)?.let { return it.groupValues[1].toInt() }
        Regex("^WD(\\d{2,4})[A-Z]{4}$").find(m)?.let {
            val n = it.groupValues[1].toInt()
            return if (it.groupValues[1].length == 4) n / 10 else n / 10 * 1000
        }
        Regex("^WDB[A-Z]{3}(\\d{4})[A-Z]{3}$").find(m)?.let { return it.groupValues[1].toInt() / 10 * 1000 }
        Regex("^(?:MG|MN)\\d{2}[A-Z]{3}(\\d{2,3})T").find(m)?.let { return it.groupValues[1].toInt() * 1000 }
        Regex("^[WH][US][HSAM]72\\d{2}(\\d{2})").find(m)?.let {
            val n = it.groupValues[1].toInt()
            return (if (n >= 30) n / 10 else n) * 1000
        }
        return null
    }

    private val SIZE = Regex("""(?<![\d.,])(\d{1,3}(?:[.,]\d{1,2})?)\s?(TB|GB|Terabyte|Gigabyte)(?![/\w])""", RegexOption.IGNORE_CASE)

    /** Gigabytes the words state, or null where they state none or several ("250 GB - 16 TB"). */
    fun capacityOfWords(text: String): Int? {
        val found = SIZE.findAll(text).map { m ->
            val n = m.groupValues[1].replace(',', '.').toDouble()
            val unit = m.groupValues[2].lowercase()
            (if (unit.startsWith("t")) (n * 1000).toInt() else n.toInt())
        }.toList()
        return found.distinct().singleOrNull()
    }

    private val SEAGATE_SATA = setOf("NM000J", "NM001G", "NM000D", "NM003D", "NM007D", "NM0008", "NM0018", "NM0007")
    private val SEAGATE_SAS = setOf("NM004J", "NM002G", "NM002D")

    /** The interface a model number states. */
    fun interfaceOfModel(model: String): DriveInterface? {
        val m = model.substringBefore('-')
        Regex("^(?:MG|MN)\\d{2}([A-Z]{3})").find(m)?.let {
            return when (it.groupValues[1]) { "ACA", "AFA" -> DriveInterface.SATA; "SCA", "SCB" -> DriveInterface.SAS; else -> null }
        }
        Regex("^[WH]U[HSA]7\\d{5}(AL[0-9A-Z])").find(m)?.let {
            return when (it.groupValues[1]) { "ALE", "ALN", "ALA" -> DriveInterface.SATA; "AL4", "AL5" -> DriveInterface.SAS; else -> null }
        }
        if (Regex("^WD\\d").containsMatchIn(m) || Regex("^HDW").containsMatchIn(m)) return DriveInterface.SATA
        Regex("^ST\\d{3,5}([A-Z]{2}\\d{3,4}[A-Z]?)$").find(m)?.let {
            val end = it.groupValues[1]
            return when {
                end in SEAGATE_SATA -> DriveInterface.SATA
                end in SEAGATE_SAS -> DriveInterface.SAS
                end.take(2) in setOf("DM", "VN", "NE", "VE", "VX", "LM") -> DriveInterface.SATA
                else -> null
            }
        }
        return null
    }

    private val NVME = Regex("""\bnvme\b|\bpcie\b|\bm\.2\b""", RegexOption.IGNORE_CASE)
    private val SAS = Regex("""\bsas\b""", RegexOption.IGNORE_CASE)
    private val SATA = Regex("""\bsata\b""", RegexOption.IGNORE_CASE)

    /** The interface the words name, none where they name more than one. */
    fun interfaceOfWords(text: String): DriveInterface? =
        listOfNotNull(
            DriveInterface.NVME.takeIf { NVME.containsMatchIn(text) },
            DriveInterface.SAS.takeIf { SAS.containsMatchIn(text) },
            DriveInterface.SATA.takeIf { SATA.containsMatchIn(text) },
        ).singleOrNull()

    // WD's own list: of the Red EFAX drives 2 to 6 TB are shingled, 8 TB and up are not.
    private val SMR_MODEL = Regex("^(WD[2-6]0EFAX|WD[2-6]0EZAZ|ST\\d+DM00[3-8]|ST\\d+AS0002|ST\\d+LM0\\d{2}|[WH]S[HM]72)")
    private val CMR_MODEL = Regex("^(MG\\d{2}|MN\\d{2}|[WH]U[HSA]7|ST\\d+(NM|VN|NE|VE)|WD\\d+(EFZX|EFBX|EFFX|EFRX|EDFZ|EDGZ|EMFZ|EDAZ|EMAZ|ELFZ|KFGX|KRYZ|FRYZ)|WD(80|101|121|140)EFAX)")

    fun recordingOfModel(model: String): DriveRecording? = when {
        SMR_MODEL.containsMatchIn(model) -> DriveRecording.SMR
        CMR_MODEL.containsMatchIn(model) -> DriveRecording.CMR
        else -> null
    }

    private val CMR = Regex("""\b(cmr|pmr)\b""", RegexOption.IGNORE_CASE)
    private val SMR = Regex("""\bsmr\b""", RegexOption.IGNORE_CASE)

    fun recordingOfWords(text: String): DriveRecording? {
        val cmr = CMR.containsMatchIn(text)
        val smr = SMR.containsMatchIn(text)
        return when {
            cmr && !smr -> DriveRecording.CMR
            smr && !cmr -> DriveRecording.SMR
            else -> null
        }
    }

    private val EXTERNAL = Regex("""\b(elements|my\s?book|expansion|backup\s?plus|one\s?touch|canvio|easystore|extern\w*|portable|usb[\s-]?(3|c|festplatte|hdd)\w*)\b""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""\b(intern\w*|3[.,]5\s?(?:"|”|zoll|inch)|exos|ultrastar|ironwolf|skyhawk|barracuda|wd\s?(?:red|gold|purple))\b""", RegexOption.IGNORE_CASE)

    /** Whether the offer is in a USB case, false where it names a bare drive. */
    fun externalOf(title: String, model: String?): Boolean? = when {
        EXTERNAL.containsMatchIn(title) -> true
        model != null && model.startsWith("WDB") -> true
        model != null || BARE.containsMatchIn(title) -> false
        else -> null
    }

    private val NUMBER = """(\d{1,3}(?:[.\s]\d{3})+|\d{2,6})"""
    private val HOUR_WORDS = """betriebsstunden|betriebsstd\.?|laufstunden|power[\s-]?on[\s-]?hours|\bpoh\b"""
    private val HOURS_AFTER = Regex("""$NUMBER\s*(?:h\b|std\.?\b|stunden\b|$HOUR_WORDS)""", RegexOption.IGNORE_CASE)
    private val HOURS_BEFORE = Regex("""(?:$HOUR_WORDS|laufzeit)\W{0,6}(?:ca\.?\s*|~\s*|von\s*|nur\s*)?$NUMBER""", RegexOption.IGNORE_CASE)

    /** The most hours the seller's words state of any drive in the ad, or null. A figure of 100 or
     *  less is a delivery time rather than a drive's life ("Versand in 24 h"). */
    fun hoursOfWords(text: String): Long? =
        (HOURS_AFTER.findAll(text) + HOURS_BEFORE.findAll(text))
            .mapNotNull { it.groupValues[1].replace(Regex("""[.\s]"""), "").toLongOrNull() }
            .filter { it in 100..300_000 }
            .maxOrNull()

    private val NEW = Regex("""\b(originalverpackt|ovp|versiegelt|eingeschwei\w+|sealed|ungeöffnet|unbenutzt|fabrikneu|neu\s+und\s+unbenutzt)\b""", RegexOption.IGNORE_CASE)

    fun saysNew(text: String): Boolean = NEW.containsMatchIn(text)

    private val BAD = Regex("""\bsmart\W+(vorsicht|caution|schlecht|bad)\b""", RegexOption.IGNORE_CASE)
    private val BROKEN = Regex("""(?<!nicht\s)(?<!keine\s)(?<!kein\s)(?<!ohne\s)\bdefekt\w*\b""", RegexOption.IGNORE_CASE)

    /** The seller's own word that the drive is failing, in the title or anywhere as a SMART status. */
    fun saysFailing(title: String, text: String): String? =
        BAD.find(text)?.value ?: BROKEN.find(title)?.value

    private val MONEY = """(\d{1,4}(?:[.,]\d{1,2})?)\s*(?:€|eur\b|euro\b)"""
    private val PIECE = """(?:stück|stueck|stk\.?|platte|hdd|festplatte|laufwerk)"""
    private val PRICE_THEN_PER = Regex("""$MONEY\s*(?:/|pro|je|das|per)\s*$PIECE""", RegexOption.IGNORE_CASE)
    private val PER_THEN_PRICE = Regex("""(?:zu\s+je|je\s+um|\bje\b|pro\s+$PIECE|stückpreis|einzelpreis|preis\s+pro\s+$PIECE)\s*:?\s*(?:nur\s*)?$MONEY""", RegexOption.IGNORE_CASE)
    private val PER_PIECE_WORDS = Regex("""\bpreis\s+(?:gilt\s+)?(?:pro|je)\s+$PIECE|\bpro\s+stück\b|\bje\s+stück\b|\bprice\s+per\s+piece\b""", RegexOption.IGNORE_CASE)

    /** What one drive costs where the words state it, in euro cents. */
    fun unitCents(text: String): Long? =
        (PRICE_THEN_PER.find(text) ?: PER_THEN_PRICE.find(text))?.groupValues?.get(1)
            ?.replace(',', '.')?.toDoubleOrNull()?.let { Math.round(it * 100) }

    fun saysPerPiece(text: String): Boolean = PER_PIECE_WORDS.containsMatchIn(text)

    private val STOCK = listOf(
        Regex("""\b(\d{1,3})\s*(?:stück|stueck|stk\.?|platten|festplatten)\s*(?:vorhanden|verfügbar|auf\s+lager|da\b)""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:noch|insgesamt|habe|hab)\s+(\d{1,3})\s*(?:stück|stueck|stk\.?|platten|festplatten)\b""", RegexOption.IGNORE_CASE),
    )

    fun stockOf(text: String): Int? = STOCK.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it > 0 }

    private val LOT = Regex("""\b(\d{1,2})\s*[x×]\s*(?=[\w"]*\s*(?:\d+\s?tb|seagate|wd\b|western|toshiba|hgst|exos|ultrastar|festplatte|hdd|ironwolf|mg\d))""", RegexOption.IGNORE_CASE)

    /** How many drives the title sells together ("4x 18TB"). */
    fun lotOf(title: String): Int? = LOT.find(title)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 1 }
}
