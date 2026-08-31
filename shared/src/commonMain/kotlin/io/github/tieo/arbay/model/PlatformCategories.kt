package io.github.tieo.arbay.model

/**
 * Which platforms a search reaches when nothing narrows it further — derived from each
 * [PlatformId]'s own [PlatformId.categories], the one place a platform's kind is declared. Before
 * this existed, "general" and "car" defaults were two separately hand-maintained lists that could
 * (and did) drift from what a platform actually is — a real-estate site with no category at all,
 * reachable only when a caller explicitly forced every platform in rather than a curated default.
 */
object PlatformCategories {
    /** Every platform in [category], including one deferred from its default search. Groups the
     *  markets picker by kind, so it reads as sections rather than one flat unlabeled wall. */
    fun allIn(category: PlatformCategory): List<PlatformId> =
        PlatformId.entries.filter { category in it.categories }

    /** The ready subset of [allIn] — what a search actually reaches by default. */
    fun defaultsFor(category: PlatformCategory): List<PlatformId> =
        allIn(category).filterNot { it.deferredFromDefaults }

    /** Default platforms for general product searches (excludes car/real-estate sites). */
    val GENERAL: List<PlatformId> = defaultsFor(PlatformCategory.GENERAL)

    /** Vehicle sites, plus general marketplaces that also carry cars (Kleinanzeigen, eBay DE). */
    val CAR: List<PlatformId> = defaultsFor(PlatformCategory.CARS)

    /** Real-estate sites. Not yet used as a search default anywhere — kept as its own group so a
     *  housing-shaped search has somewhere to grow into rather than staying uncategorized. */
    val REAL_ESTATE: List<PlatformId> = defaultsFor(PlatformCategory.REAL_ESTATE)
}
