package io.github.tieo.arbay.state

import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.design.LookChoice
import io.github.tieo.arbay.history.SearchHistoryStore
import io.github.tieo.arbay.loadBannedIds
import io.github.tieo.arbay.saveBannedIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The user's state as the server holds it, for every device alike: lately run searches, offers put
 * away, the look and the currency.
 *
 * A device keeps its own copy only to draw the first screen before the server has answered. Every
 * change is written through to the server, and the server's copy is taken on start and every
 * minute after, so a choice made on the phone shows up in the browser and the other way round.
 * Where the server has nothing yet, this device's copy becomes the server's: the first device to
 * connect after the move hands over what it had.
 */
object SharedState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: ArbayClient? = null

    /** Keep this device's state in step with the server's for as long as the app runs. */
    fun follow(client: ArbayClient) {
        if (this.client != null) return
        this.client = client
        scope.launch {
            while (true) {
                pull(client)
                delay(60_000)
            }
        }
    }

    fun put(key: String, value: JsonElement) {
        val c = client ?: return
        scope.launch { runCatching { c.putState(key, value) } }
    }

    private suspend fun pull(c: ArbayClient) {
        val server = try { c.getState() } catch (e: CancellationException) { throw e } catch (_: Exception) { return }
        server["searchHistory"]?.let { SearchHistoryStore.adopt(it) } ?: put("searchHistory", SearchHistoryStore.asJson())
        server["hiddenOffers"]?.let { HiddenOffers.adopt(it) } ?: put("hiddenOffers", HiddenOffers.asJson())
        server["look"]?.let(::adoptLook) ?: put("look", lookAsJson())
    }

    fun lookAsJson(): JsonObject = JsonObject(
        mapOf(
            "look" to JsonPrimitive(LookChoice.look.id),
            "brightness" to JsonPrimitive(LookChoice.brightness.name),
            "offerLayout" to JsonPrimitive(LookChoice.layout.name),
            "currency" to JsonPrimitive(DisplayCurrency.current),
        ),
    )

    private fun adoptLook(value: JsonElement) {
        val o = value as? JsonObject ?: return
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        LookChoice.adopt(s("look"), s("brightness"), s("offerLayout"))
        s("currency")?.let { DisplayCurrency.adopt(it) }
    }
}

/** Offers the user put away by hand, wherever they did it. */
object HiddenOffers {
    private val _ids = MutableStateFlow(runCatching { loadBannedIds() }.getOrDefault(emptySet()))
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    /** Set the put-away offers; a device that cannot keep its copy still hands the change on. */
    fun set(ids: Set<String>) {
        _ids.value = ids
        SharedState.put("hiddenOffers", asJson())
        saveBannedIds(ids)
    }

    fun asJson(): JsonArray = JsonArray(_ids.value.sorted().map(::JsonPrimitive))

    internal fun adopt(value: JsonElement) {
        val ids = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: return
        if (ids == _ids.value) return
        _ids.value = ids
        runCatching { saveBannedIds(ids) }
    }
}
