package io.github.tieo.arbay.judging

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.chat.AuctionBids
import io.github.tieo.arbay.tools.runProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.DriverManager
import kotlin.math.exp
import kotlin.time.Duration.Companion.hours

/**
 * How long a hard drive can be expected to keep working from a given number of power-on hours, from
 * Backblaze's published records of every drive it has run.
 *
 * The records are the failure rate per 2,000 hours of a drive's life, by model. A model family's own
 * rate counts in a bin once it has [THIN_HOURS] of exposure there, since a rate over a handful of
 * drives says nothing; elsewhere it is the rate of all drives of 12 TB and up, because the whole
 * fleet since 2013 holds 3 TB desktop drives that failed far more often than anything sold now.
 * The expectation stops at the [Table.horizon], where the fleet's records thin out, and so reads as
 * hours of work left within what has been observed.
 */
object DriveLife {
    private val log = LoggerFactory.getLogger(DriveLife::class.java)
    private const val STEP = 2000.0
    private const val YEAR_HOURS = 8766.0
    private const val THIN_HOURS = 300 * YEAR_HOURS
    private const val FIVE_YEARS = 5 * YEAR_HOURS

    /** The fleet has to have this much exposure in a bin for the bin to lie inside the horizon. */
    private const val HORIZON_HOURS = 5000 * YEAR_HOURS

    private val binsFile = DataDir.file("drive_life_bins.csv")
    private val bigFile = DataDir.file("drive_life_big_models.csv")

    data class Expected(val hoursLeft: Long, val failChance5y: Double?, val basis: String)

    class Table(rows: List<Bin>, big: Set<String>) {
        private val byFamily = HashMap<String, HashMap<Double, DoubleArray>>()
        private val fleet = HashMap<Double, DoubleArray>()

        init {
            for (r in rows) {
                val to = byFamily.getOrPut(familyOf(r.model) ?: r.model) { HashMap() }.getOrPut(r.lo) { DoubleArray(2) }
                to[0] += r.exposure; to[1] += r.fails
                if (r.model in big) fleet.getOrPut(r.lo) { DoubleArray(2) }.let { it[0] += r.exposure; it[1] += r.fails }
            }
        }

        val horizon: Double = fleet.filterValues { it[0] >= HORIZON_HOURS }.keys.maxOrNull()?.plus(STEP) ?: 0.0

        /** What a table needs to hold to be believed over the one it would replace. */
        val usable: Boolean get() = horizon >= 40_000 && byFamily.size >= 50

        fun expected(family: String?, hours: Long): Expected? {
            if (horizon <= 0.0 || hours < 0) return null
            val own = family?.let { byFamily[it] }.orEmpty()
            val bins = ((horizon / STEP).toInt())
            val rate = DoubleArray(bins)
            val ownBin = BooleanArray(bins)
            for (b in 0 until bins) {
                val lo = b * STEP
                val mine = own[lo]
                val theirs = fleet[lo]
                ownBin[b] = mine != null && mine[0] >= THIN_HOURS
                val used = if (ownBin[b]) mine else theirs
                rate[b] = if (used != null && used[0] > 0) used[1] / used[0] else 0.0
            }
            var at = hours.toDouble()
            var alive = 1.0
            var total = 0.0
            var aliveFiveYearsOn: Double? = null
            val fiveYearsOn = hours + FIVE_YEARS
            while (at < horizon) {
                val b = (at / STEP).toInt()
                val next = minOf((b + 1) * STEP, horizon)
                val span = next - at
                val lam = rate[b]
                total += if (lam > 0) alive * (1 - exp(-lam * span)) / lam else alive * span
                if (aliveFiveYearsOn == null && next >= fiveYearsOn) aliveFiveYearsOn = alive * exp(-lam * (fiveYearsOn - at))
                alive *= exp(-lam * span)
                at = next
            }
            val first = (hours.toDouble() / STEP).toInt().coerceAtMost(bins)
            val seen = (first until bins).count { ownBin[it] }
            val basis = when {
                family == null || seen == 0 -> "drives of 12 TB and up"
                seen == bins - first -> "$family's own records"
                else -> "$family's own records, drives of 12 TB and up where they are thin"
            }
            return Expected(total.toLong(), aliveFiveYearsOn?.let { 1 - it }, basis)
        }
    }

    class Bin(val model: String, val lo: Double, val exposure: Double, val fails: Double)

    private val MODEL_PREFIX = Regex("^(WDC|TOSHIBA|HGST|Seagate)\\s+", RegexOption.IGNORE_CASE)

    @Volatile private var table: Table = load()

    private fun load(): Table = runCatching {
        if (binsFile.exists() && bigFile.exists()) parse(binsFile.readText(), bigFile.readText()).takeIf { it.usable } else null
    }.getOrNull() ?: parse(resource("bins.csv"), resource("big_models.csv"))

    private fun resource(name: String): String =
        DriveLife::class.java.getResourceAsStream("/drive_life/$name")!!.readBytes().decodeToString()

    internal fun parse(bins: String, big: String): Table {
        val rows = bins.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
            val cells = line.split(',')
            Bin(cells[0], cells[1].toDouble(), cells[2].toDouble(), cells[3].toDouble())
        }.toList()
        return Table(rows, big.lineSequence().drop(1).map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }

    /** The expectation for a drive of [model] at [hours] power-on hours, or for one of the fleet
     *  where the model is unknown or not among the records. */
    fun expected(family: String?, hours: Long): Expected? = table.expected(family, hours)

    /**
     * The family a model number belongs to, as drives of it share their failure rate: Toshiba's
     * generation (MG08), Ultrastar's platform (WUH721816 and WUH721818 are both HC550's 7218), the
     * ending of Seagate's number (NM001G is Exos X16 in 12, 14 and 16 TB), WD's four letters.
     */
    fun familyOf(model: String): String? {
        val m = MODEL_PREFIX.replace(model.trim().uppercase(), "").substringBefore('-')
        Regex("^(MG\\d{2})[A-Z]{3}\\d{2,3}T").find(m)?.let { return it.groupValues[1] }
        Regex("^[WH]U[HS](72\\d{2})\\d{2}").find(m)?.let { return "Ultrastar ${it.groupValues[1]}" }
        Regex("^ST\\d{3,5}([A-Z]{2}\\d{3,4}[A-Z]?)$").find(m)?.let { return "Seagate ${it.groupValues[1]}" }
        Regex("^WD\\d{2,4}([A-Z]{4})$").find(m)?.let { return "WD ${it.groupValues[1]}" }
        return null
    }

    /** The family a title names by its line where no model number is written. */
    fun familyOfLine(text: String): String? = LINES.firstOrNull { it.first.containsMatchIn(text) }?.second

    private val LINES = listOf(
        Regex("""\bexos\s*x\s?16\b""", RegexOption.IGNORE_CASE) to "Seagate NM001G",
        Regex("""\bexos\s*x\s?18\b""", RegexOption.IGNORE_CASE) to "Seagate NM000J",
        Regex("""\bhc\s?510\b""", RegexOption.IGNORE_CASE) to "Ultrastar 7210",
        Regex("""\bhc\s?520\b""", RegexOption.IGNORE_CASE) to "Ultrastar 7212",
        Regex("""\bhc\s?530\b""", RegexOption.IGNORE_CASE) to "Ultrastar 7214",
        Regex("""\bhc\s?550\b""", RegexOption.IGNORE_CASE) to "Ultrastar 7218",
        Regex("""\bhc\s?560\b""", RegexOption.IGNORE_CASE) to "Ultrastar 7220",
    ) + (7..10).map { n ->
        val g = "MG%02d".format(n)
        Regex("""\b$g(?:[A-Z]{3}\d{2,3}T\w*)?\b""", RegexOption.IGNORE_CASE) to g
    }

    // ── Keeping the records current ──

    /** Backblaze publishes the records quarterly, so a month is often enough to look. */
    private val REFRESH_EVERY = (30 * 24).hours

    /**
     * Reads the records again now, in a process of its own: the query scans a few hundred million
     * rows through a native engine, which must not be able to take the server and its bids down
     * with it. The table in use is replaced only by one that passes [Table.usable].
     */
    suspend fun refresh(): String {
        val dir = DataDir.file("drive_life_refresh").apply { deleteRecursively(); mkdirs() }
        val jar = File(DriveLife::class.java.protectionDomain.codeSource.location.toURI()).path
        val java = File(System.getProperty("java.home"), "bin/java").path
        val outcome = withTimeoutOrNull(3.hours) {
            runProcess(listOf("nice", "-n", "19", java, "-Xmx256m", "-cp", jar, "io.github.tieo.arbay.judging.DriveLifeKt", dir.path))
        } ?: error("the records took over three hours to read")
        val out = outcome.stdout.decodeToString().trim() + " " + outcome.stderr.takeLast(500)
        val bins = File(dir, "bins.csv")
        val big = File(dir, "big_models.csv")
        check(bins.exists() && big.exists()) { "the records were not written: $out" }
        val read = parse(bins.readText(), big.readText())
        check(read.usable) { "the records read back too thin to use: $out" }
        bins.copyTo(binsFile, overwrite = true)
        big.copyTo(bigFile, overwrite = true)
        table = read
        dir.deleteRecursively()
        return "records replaced, horizon ${read.horizon.toLong()} h. $out".also { log.info("Drive records: {}", it) }
    }

    /** Refreshes the records once they are old, whenever no bid is near. */
    suspend fun run() {
        while (true) {
            delay(1.hours)
            try {
                val age = if (binsFile.exists()) System.currentTimeMillis() - binsFile.lastModified() else Long.MAX_VALUE
                if (age > REFRESH_EVERY.inWholeMilliseconds && !AuctionBids.busySoon(3.hours)) refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Drive records not refreshed: {}", e.message)
                delay(24.hours)
            }
        }
    }
}

/** Backblaze publishes these read-only keys for reading its Drive Stats. */
private const val KEY_ID = "0045f0571db506a0000000017"
private const val KEY = "K004Fs/bgmTk5dgo6GAVm2Waj3Ka+TE"

/** Writes bins.csv and big_models.csv into the folder named by its one argument. */
fun main(args: Array<String>) {
    val dir = File(args[0])
    Class.forName("org.duckdb.DuckDBDriver")
    DriverManager.getConnection("jdbc:duckdb:").use { c ->
        c.createStatement().use { s ->
            listOf(
                "SET home_directory='${dir.path}'",
                "SET temp_directory='${dir.path}/tmp'",
                "SET threads=2",
                "SET memory_limit='3GB'",
                "INSTALL httpfs", "LOAD httpfs", "INSTALL iceberg", "LOAD iceberg",
                "CREATE SECRET bb (TYPE s3, KEY_ID '$KEY_ID', SECRET '$KEY', ENDPOINT 's3.us-west-004.backblazeb2.com', REGION 'us-west-004')",
            ).forEach { s.execute(it) }
            val meta = s.executeQuery("SELECT max(file) FROM glob('s3://drivestats-iceberg/drivestats/metadata/*.metadata.json')").use { it.next(); it.getString(1) }
            val drives = "${dir.path}/drives.parquet"
            s.execute(
                """COPY (SELECT serial_number, any_value(model) AS model, max(capacity_bytes) AS bytes,
                   max(smart_9_raw) AS hours_last, min(smart_9_raw) AS hours_first, max(failure) AS failed
                   FROM iceberg_scan('$meta') GROUP BY serial_number) TO '$drives' (FORMAT parquet)""",
            )
            s.execute(
                """CREATE TABLE d AS SELECT model, hours_first::DOUBLE h0, hours_last::DOUBLE h1, failed FROM '$drives'
                   WHERE hours_last IS NOT NULL AND hours_first IS NOT NULL AND hours_first <= hours_last AND hours_last < 200000 AND hours_last > 0""",
            )
            s.execute("CREATE TABLE b AS SELECT range*2000.0 lo, range*2000.0+2000 hi FROM range(0,100)")
            s.execute(
                """COPY (SELECT model, lo, sum(greatest(0, least(hi, h1) - greatest(lo, h0))) AS exposure_h,
                   sum(CASE WHEN failed=1 AND h1>=lo AND h1<hi THEN 1 ELSE 0 END) AS fails
                   FROM d JOIN b ON h0 < hi AND h1 >= lo GROUP BY model, lo) TO '${dir.path}/bins.csv' (HEADER)""",
            )
            s.execute("COPY (SELECT DISTINCT model FROM '$drives' WHERE bytes >= 12e12) TO '${dir.path}/big_models.csv' (HEADER)")
            s.executeQuery("SELECT count(*), sum(failed) FROM d").use { it.next(); println("${it.getLong(1)} drives, ${it.getLong(2)} failures") }
        }
    }
}
