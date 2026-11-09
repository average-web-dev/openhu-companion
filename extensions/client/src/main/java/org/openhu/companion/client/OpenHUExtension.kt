package org.openhu.companion.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import org.json.JSONArray
import org.json.JSONObject

/**
 * An extension app's way to OpenHU: binds the OpenHU companion app's extension service and talks JSON with it.
 * The companion holds the Bluetooth link to the head unit; this app gets the topics and commands its plugin
 * declares (`companion` in the plugin's manifest), after the user allowed it once in the companion.
 *
 * ```
 * val openhu = OpenHUExtension(context, "org.openhu.vehicle", object : OpenHUExtension.Listener {
 *     override fun onState(state: OpenHUExtension.State) { … }
 *     override fun onEvent(topic: String, data: JSONObject) { … }
 * })
 * openhu.subscribe("vehicle.*")   // also before the connection: sent once allowed
 * openhu.connect()
 * ```
 *
 * All callbacks run on the main thread. One file without dependencies: copy it if you don't want the module.
 * Protocol: openhu-companion, `Extensions.kt`.
 */
class OpenHUExtension(
    private val context: Context,
    private val plugin: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: State) {}
        fun onEvent(topic: String, data: JSONObject) {}
        fun onError(error: String) {}
    }

    /**
     * [bound]: the companion app is installed and running; [connected]: it has a link to the head unit;
     * [access]: `allowed`, `pending` (the user has not answered yet), `denied` or `notDeclared` (no plugin on the
     * head unit names this app, or no head unit yet); [name]: the plugin's name for this app.
     */
    data class State(val bound: Boolean, val connected: Boolean, val access: String, val name: String) {
        val ready: Boolean get() = bound && connected && access == ACCESS_ALLOWED
    }

    var state = State(bound = false, connected = false, access = ACCESS_NOT_DECLARED, name = "")
        private set

    private val main = Handler(Looper.getMainLooper())
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { onMessage(it); true })
    private var service: Messenger? = null
    private var binding = false
    private val topics = linkedSetOf<String>()
    private val pending = mutableMapOf<Int, (JSONObject?) -> Unit>()
    private var nextId = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            send(JSONObject().put("type", "hello").put("plugin", plugin).put("protocol", PROTOCOL))
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            failPending()
            update(state.copy(bound = false, connected = false))
        }

        override fun onBindingDied(name: ComponentName) {
            // the companion was updated or killed for good: bind again
            context.unbindService(this)
            binding = false
            main.postDelayed({ connect() }, RETRY_MS)
        }
    }

    /** Binds the companion; false when it is not installed (or this app cannot see it). */
    fun connect(): Boolean {
        if (binding) return true
        binding = try {
            context.bindService(Intent(ACTION).setPackage(COMPANION), connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            false
        }
        return binding
    }

    fun disconnect() {
        if (!binding) return
        binding = false
        context.unbindService(connection)
        service = null
        failPending()
        update(state.copy(bound = false, connected = false))
    }

    /** Topics (OpenHU patterns: `vehicle.*`) to get as [Listener.onEvent], with their current value first. */
    fun subscribe(vararg patterns: String) {
        topics += patterns
        if (state.ready) send(JSONObject().put("type", "subscribe").put("topics", JSONArray(patterns.toList())))
    }

    /** The current value of a topic, null when there is none or no answer. */
    fun get(topic: String, callback: (JSONObject?) -> Unit) {
        val id = ++nextId
        pending[id] = callback
        val sent = send(JSONObject().put("type", "request").put("id", id).put("method", "bus.get")
            .put("params", JSONObject().put("topic", topic)))
        if (!sent) pending.remove(id)?.invoke(null)
    }

    /** A command to the plugin (one of the topics it declares as commands). */
    fun emit(topic: String, data: JSONObject = JSONObject()) {
        send(JSONObject().put("type", "emit").put("topic", topic).put("data", data))
    }

    private fun onMessage(message: Message) {
        if (message.what != MSG_JSON) return
        val json = try { JSONObject(message.data?.getString(KEY_JSON) ?: return) } catch (_: Exception) { return }
        when (json.optString("type")) {
            "state" -> {
                val wasReady = state.ready
                update(State(true, json.optBoolean("connected"), json.optString("access"), json.optString("name")))
                if (state.ready && !wasReady && topics.isNotEmpty()) {
                    send(JSONObject().put("type", "subscribe").put("topics", JSONArray(topics.toList())))
                }
            }
            "event" -> listener.onEvent(json.optString("topic"), json.optJSONObject("data") ?: JSONObject())
            "response" -> pending.remove(json.optInt("id"))?.invoke(
                if (json.has("error")) null else json.optJSONObject("result"),
            )
            "error" -> listener.onError(json.optString("error"))
        }
    }

    private fun send(json: JSONObject): Boolean {
        val target = service ?: return false
        val message = Message.obtain(null, MSG_JSON)
        message.data = Bundle().apply { putString(KEY_JSON, json.toString()) }
        message.replyTo = incoming
        return try {
            target.send(message)
            true
        } catch (_: RemoteException) {
            false
        }
    }

    private fun update(new: State) {
        if (new == state) return
        state = new
        listener.onState(new)
    }

    private fun failPending() {
        val callbacks = pending.values.toList()
        pending.clear()
        callbacks.forEach { it(null) }
    }

    companion object {
        const val COMPANION = "org.openhu.companion"
        const val ACTION = "org.openhu.companion.EXTENSION"
        const val MSG_JSON = 1
        const val KEY_JSON = "json"
        const val PROTOCOL = 1
        const val ACCESS_ALLOWED = "allowed"
        const val ACCESS_PENDING = "pending"
        const val ACCESS_DENIED = "denied"
        const val ACCESS_NOT_DECLARED = "notDeclared"
        private const val RETRY_MS = 2000L
    }
}
