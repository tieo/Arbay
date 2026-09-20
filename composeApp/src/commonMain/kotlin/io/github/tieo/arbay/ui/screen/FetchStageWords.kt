package io.github.tieo.arbay.ui.screen

/**
 * What a market is being asked with, in words rather than in the name of the code doing it.
 *
 * The server names each tier of its fetch chain as it starts one, and those names are the ones the
 * code uses: HTTP, Rnet, CurlCffi, Chromium, Firefox, BrowseAPI, PAGE_3. They were shown to the
 * reader as they arrived, so the free items screen sat under the word "HTTP" while it worked.
 *
 * The app sets this field itself in one place, to say a market was not asked, and that is already
 * a sentence. A single word that is not a tier named here is one this function has not been told
 * about, and it is dropped rather than shown raw.
 */
fun fetchStageWords(stage: String): String? = when {
    stage.startsWith("PAGE_") -> stage.removePrefix("PAGE_").toIntOrNull()?.let { "page $it" }
    // The three plain asks differ only in which client speaks; to a reader they are one thing.
    stage == "HTTP" || stage == "Rnet" || stage == "CurlCffi" -> "asking the site"
    // A browser is what comes after the plain ask was refused, which is worth saying: it is slow
    // because the market pushed back, not because the app is idling.
    stage == "Chromium" || stage == "Firefox" -> "opening a browser, the plain ask was turned away"
    stage == "BrowseAPI" -> "asking eBay's own service"
    // Written by the app rather than named by the fetch chain: tier names are one word.
    else -> stage.takeIf { ' ' in it }
}
