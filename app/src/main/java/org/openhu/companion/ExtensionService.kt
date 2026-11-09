package org.openhu.companion

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The open door for extension apps (openhu-companion#3): any app can bind it (no permission), but it gets only
 * what the plugin it belongs to declares, and only after the user allowed it once ([ExtensionAccess]). The caller
 * is identified by the uid Android puts into each message, not by what it claims.
 */
class ExtensionService : Service() {
    private class Client(val uid: Int, val replyTo: Messenger) {
        var plugin: String = ""
        var scope: Extensions.Declared? = null
        var access = Extensions.Access.NOT_DECLARED
        val topics = linkedSetOf<String>()
    }

    private val scope = MainScope()
    private lateinit var link: HeadUnitLink
    private val clients = mutableMapOf<IBinder, Client>()
    private val asked = mutableSetOf<String>()
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper()) { onMessage(it); true }) }

    override fun onCreate() {
        super.onCreate()
        link = HeadUnitLink.get(this)
        link.start()
        scope.launch {
            link.events.collect { event ->
                clients.values.filter { c -> c.access == Extensions.Access.ALLOWED && c.topics.any { Extensions.matches(it, event.topic) } }
                    .forEach { send(it, Extensions.event(event.topic, event.data)) }
            }
        }
        scope.launch {
            combine(link.state, link.declared, ExtensionAccess.version) { _, _, _ -> }.collect { clients.values.toList().forEach(::refresh) }
        }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun onMessage(message: Message) {
        if (message.what != Extensions.MSG_JSON) return
        val replyTo = message.replyTo ?: return
        val json = try { JSONObject(message.data?.getString(Extensions.KEY_JSON) ?: return) } catch (_: Exception) { return }
        val client = clients.getOrPut(replyTo.binder) {
            Client(message.sendingUid, replyTo).also { c ->
                try { replyTo.binder.linkToDeath({ Handler(Looper.getMainLooper()).post { clients.remove(c.replyTo.binder) } }, 0) }
                catch (_: RemoteException) {}
            }
        }
        if (json.optString("type") == "hello") {
            client.plugin = json.optString("plugin")
            refresh(client)
            return
        }
        val declared = client.scope
        if (client.access != Extensions.Access.ALLOWED || declared == null) {
            send(client, Extensions.error("not allowed (${client.access.wire})"))
            return
        }
        when (val action = Extensions.decide(json, declared)) {
            is Extensions.Action.Subscribe -> {
                client.topics += action.topics
                link.subscribe(action.topics)  // OpenHU answers with the current values
            }
            is Extensions.Action.Get -> scope.launch {
                val result = link.request("bus.get", JSONObject().put("topic", action.topic))
                send(client, Extensions.response(action.id, result, if (result == null) "no answer" else null))
            }
            is Extensions.Action.Emit -> link.emit(action.topic, action.data)
            is Extensions.Action.Reject ->
                send(client, if (action.id != null) Extensions.response(action.id, null, action.error) else Extensions.error(action.error))
            Extensions.Action.Ignore -> {}
        }
    }

    /** Who the client is, what its plugin allows and what the user said; tells the client. */
    private fun refresh(client: Client) {
        val packages = packageManager.getPackagesForUid(client.uid)?.toList().orEmpty()
        val declared = link.declared.value
        val match = packages.firstNotNullOfOrNull { pkg -> Extensions.find(declared, client.plugin, pkg)?.let { pkg to it } }
        client.scope = match?.second
        client.access = if (match == null) {
            Extensions.Access.NOT_DECLARED
        } else {
            val (pkg, scope) = match
            val digest = ExtensionAccess.digest(this, pkg)
            when (digest?.let { ExtensionAccess.decision(this, pkg, it) }) {
                true -> Extensions.Access.ALLOWED
                false -> Extensions.Access.DENIED
                null -> {
                    if (digest != null && asked.add(pkg)) ExtensionAccess.ask(this, pkg, digest, scope.name)
                    Extensions.Access.PENDING
                }
            }
        }
        if (client.access == Extensions.Access.ALLOWED && client.topics.isNotEmpty()) link.subscribe(client.topics.toList())
        send(client, Extensions.state(link.connected, client.access, client.scope?.name))
    }

    private fun send(client: Client, json: JSONObject) {
        val message = Message.obtain(null, Extensions.MSG_JSON)
        message.data = Bundle().apply { putString(Extensions.KEY_JSON, json.toString()) }
        try {
            client.replyTo.send(message)
        } catch (e: RemoteException) {
            Log.i(TAG, "extension gone: $e")
            clients.remove(client.replyTo.binder)
        }
    }

    private companion object {
        const val TAG = "OpenHUCompanion"
    }
}
