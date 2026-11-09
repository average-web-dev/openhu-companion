package org.openhu.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * Extension apps (openhu-companion#3): other apps on the phone reach OpenHU through the companion's open
 * [ExtensionService]. An extension belongs to one plugin; OpenHU publishes which plugins want an app and what that
 * app may see (`companion.extensions`, from the plugins' manifests). An extension gets only its plugin's topics and
 * commands, and only after the user allowed it once. Pure logic here (no Android classes: unit tested).
 *
 * Messages, one JSON object per message (`Bundle` key [KEY_JSON]):
 *  - extension → companion: `hello` {plugin, version}, `subscribe` {topics}, `request` {id, method: "bus.get",
 *    params: {topic}}, `emit` {topic, data}
 *  - companion → extension: `state` {connected, access, name}, `event` {topic, data}, `response` {id, result | error}
 */
object Extensions {
    const val ACTION = "org.openhu.companion.EXTENSION"
    const val MSG_JSON = 1
    const val KEY_JSON = "json"
    const val TOPIC = "companion.extensions"

    /** What a plugin declares for its app (`companion` in its manifest). */
    data class Declared(
        val plugin: String, val pkg: String, val name: String, val topics: List<String>, val commands: List<String>,
    )

    enum class Access(val wire: String) {
        /** No plugin on the head unit declares this app (or no head unit connected yet). */
        NOT_DECLARED("notDeclared"),
        PENDING("pending"),
        ALLOWED("allowed"),
        DENIED("denied"),
    }

    /** `companion.extensions` {extensions: [{plugin, package, name, topics, commands}]} */
    fun parseDeclared(data: JSONObject): List<Declared> {
        val list = data.optJSONArray("extensions") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val entry = list.optJSONObject(i) ?: return@mapNotNull null
            val plugin = entry.optString("plugin")
            val pkg = entry.optString("package")
            if (plugin.isEmpty() || pkg.isEmpty()) return@mapNotNull null
            Declared(plugin, pkg, entry.optString("name").ifEmpty { plugin }, strings(entry.optJSONArray("topics")),
                strings(entry.optJSONArray("commands")))
        }
    }

    /** The declaration of [plugin] that names [pkg] as its app, null if there is none. */
    fun find(declared: List<Declared>, plugin: String, pkg: String): Declared? =
        declared.firstOrNull { it.plugin == plugin && it.pkg == pkg }

    /** OpenHU's patterns: `*` everything, `prefix.*` everything below prefix, else the exact topic. */
    fun matches(pattern: String, topic: String): Boolean = when {
        pattern == "*" -> true
        pattern.endsWith(".*") -> topic.startsWith(pattern.dropLast(1))
        else -> pattern == topic
    }

    /** A subscription pattern is allowed when every topic it can match is allowed. */
    fun covers(allowed: List<String>, pattern: String): Boolean = allowed.any { allow ->
        allow == "*" || allow == pattern || (allow.endsWith(".*") && pattern.startsWith(allow.dropLast(1)))
    }

    /** What the companion does with a message of an allowed extension. */
    sealed interface Action {
        data class Subscribe(val topics: List<String>) : Action
        data class Get(val id: Int, val topic: String) : Action
        data class Emit(val topic: String, val data: JSONObject) : Action
        data class Reject(val id: Int?, val error: String) : Action
        data object Ignore : Action
    }

    fun decide(message: JSONObject, scope: Declared): Action = when (message.optString("type")) {
        "subscribe" -> {
            val topics = strings(message.optJSONArray("topics"))
            val allowed = topics.filter { covers(scope.topics, it) }
            if (allowed.isEmpty()) Action.Reject(null, "topics not allowed: ${topics.joinToString()}")
            else Action.Subscribe(allowed)
        }
        "request" -> {
            val id = message.optInt("id")
            val method = message.optString("method")
            val topic = message.optJSONObject("params")?.optString("topic").orEmpty()
            when {
                method != "bus.get" -> Action.Reject(id, "method not allowed: $method")
                topic.isEmpty() || scope.topics.none { matches(it, topic) } -> Action.Reject(id, "topic not allowed: $topic")
                else -> Action.Get(id, topic)
            }
        }
        "emit" -> {
            val topic = message.optString("topic")
            if (scope.commands.any { matches(it, topic) }) Action.Emit(topic, message.optJSONObject("data") ?: JSONObject())
            else Action.Reject(null, "command not allowed: $topic")
        }
        else -> Action.Ignore
    }

    fun state(connected: Boolean, access: Access, name: String?): JSONObject = JSONObject()
        .put("type", "state").put("connected", connected).put("access", access.wire).put("name", name ?: "")

    fun event(topic: String, data: JSONObject): JSONObject =
        JSONObject().put("type", "event").put("topic", topic).put("data", data)

    fun response(id: Int, result: Any?, error: String? = null): JSONObject = JSONObject()
        .put("type", "response").put("id", id)
        .apply { if (error != null) put("error", error) else put("result", result ?: JSONObject.NULL) }

    fun error(error: String): JSONObject = JSONObject().put("type", "error").put("error", error)

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
}
