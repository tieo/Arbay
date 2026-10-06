package io.github.tieo.arbay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.model.CarTaxonomy
import io.github.tieo.arbay.model.CarTaxonomySeed

/** Holds the car make/model taxonomy the car-search picker draws from. Starts with the
 *  bundled seed so the picker works offline and on first launch; refreshed from the server
 *  on start when the version differs. */
object CarTaxonomyStore {
    var taxonomy by mutableStateOf(CarTaxonomySeed.taxonomy)
        private set

    fun update(fetched: CarTaxonomy) {
        if (fetched.makes.isNotEmpty() && fetched.version != taxonomy.version) {
            taxonomy = fetched
        }
    }
}
