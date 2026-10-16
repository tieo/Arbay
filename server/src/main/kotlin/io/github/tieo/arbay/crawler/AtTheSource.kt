package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarFilters

/**
 * The criteria a market is asked to filter on itself.
 *
 * A market's own filter removes every ad that does not answer it, and every ad that leaves the
 * field empty, before Arbay sees either; Arbay's own filter keeps an ad that states nothing and
 * weighs a wish against the others. So the markets are asked only for the limits of the search
 * (year, mileage, price, fuel, body), and only on the fields their sellers must fill in. Gearbox,
 * power, drive, seller type and wheelbase are wishes the fit score weighs: a van that misses one
 * can still be the best one, and a market filter would delete it unseen.
 *
 * A search that excludes unstated specs itself ([CarFilters.strictUnknown]) loses nothing to a
 * market filter that Arbay would not drop anyway, so it still asks for everything.
 */
fun CarFilters.atTheSource(): CarFilters =
    if (strictUnknown) this
    else copy(
        transmission = null, minPowerKw = null, maxPowerKw = null, drivetrain = null,
        sellerType = null, minWheelbaseMm = null, maxWheelbaseMm = null,
    )
