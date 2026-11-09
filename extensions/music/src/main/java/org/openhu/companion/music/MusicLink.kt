package org.openhu.companion.music

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.openhu.companion.client.OpenHUExtension
import java.util.concurrent.atomic.AtomicInteger

/**
 * The music plugin through the companion app ([OpenHUExtension]), as coroutines: its state as [events], `bus.get`,
 * `music.command` and `music.query` → `music.result`. One per process ([get]); all calls on the main thread.
 */
class MusicLink private constructor(context: Context) : OpenHUExtension.Listener {
    data class Event(val topic: String, val data: JSONObject)

    private val extension = OpenHUExtension(context, PLUGIN, this)
    private val _state = MutableStateFlow(extension.state)
    val state: StateFlow<OpenHUExtension.State> = _state
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events
    private val queries = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private val ids = AtomicInteger()

    init {
        extension.subscribe("music.state", "music.result", "media.metadata")
    }

    fun connect() {
        extension.connect()
    }

    override fun onState(state: OpenHUExtension.State) {
        _state.value = state
        if (!state.ready) {
            queries.values.forEach { it.cancel() }
            queries.clear()
        }
    }

    override fun onEvent(topic: String, data: JSONObject) {
        if (topic == "music.result") queries.remove(data.optString("id"))?.complete(data)
        _events.tryEmit(Event(topic, data))
    }

    /** The current value of a topic, null without one or without the head unit. */
    suspend fun get(topic: String): JSONObject? {
        val answer = CompletableDeferred<JSONObject?>()
        extension.get(topic) { answer.complete(it) }
        return withTimeoutOrNull(TIMEOUT_MS) { answer.await() }
    }

    fun emit(topic: String, data: JSONObject) = extension.emit(topic, data)

    /** music.query → music.result with the same id, null on a timeout or without the head unit. */
    suspend fun query(data: JSONObject): JSONObject? {
        if (!extension.state.ready) return null
        val id = "m" + ids.incrementAndGet()
        val answer = CompletableDeferred<JSONObject>()
        queries[id] = answer
        extension.emit("music.query", data.put("id", id))
        return withTimeoutOrNull(TIMEOUT_MS) { try { answer.await() } catch (_: Exception) { null } }
            .also { queries.remove(id) }
    }

    /** Waits (up to [timeoutMs]) until the head unit can be reached, e.g. for a voice request right after start. */
    suspend fun awaitReady(timeoutMs: Long): Boolean {
        connect()
        return withTimeoutOrNull(timeoutMs) { state.first { it.ready } } != null
    }

    companion object {
        const val PLUGIN = "org.openhu.media"
        private const val TIMEOUT_MS = 8000L

        // holds only the application context
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: MusicLink? = null
        fun get(context: Context): MusicLink = instance ?: synchronized(this) {
            instance ?: MusicLink(context.applicationContext).also { instance = it }
        }
    }
}
