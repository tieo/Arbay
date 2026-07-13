package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarTaxonomy
import io.github.tieo.arbay.model.CarTaxonomySeed

/**
 * Holds the canonical car taxonomy the app pulls. Starts from the bundled seed; a daily job
 * calls [refresh] to rebuild it from each marketplace's own filter catalog.
 *
 * Per-site probing is not wired yet: [refresh] currently keeps the seed. Each platform's
 * make/model discovery plugs in here (see reference_car_filter_tokens for the endpoints to
 * probe), so adding a platform is a local change to this provider, not the app.
 */
object CarTaxonomyProvider {

    @Volatile
    var current: CarTaxonomy = CarTaxonomySeed.taxonomy
        private set

    /** Rebuilds the taxonomy from live site catalogs. No-op until per-site probes land. */
    suspend fun refresh() {
        // TODO: probe each platform's make/model catalog, merge into a canonical taxonomy,
        // bump the version, then assign to `current`. Until then the seed is authoritative.
    }
}
