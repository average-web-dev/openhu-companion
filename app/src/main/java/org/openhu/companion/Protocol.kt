package org.openhu.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * The messages between the app and OpenHU. Over Bluetooth they are OpenHU's WebSocket API (docs/api.md in openhu),
 * one JSON object per line. OpenHU connects to the app (it knows the phone of the Android Auto session); the app
 * announces itself with [hello] first, then uses the API like any client.
 */
object Protocol {
    const val VERSION = 1
    const val APP_ID = "org.openhu.companion"

    /** [label]: the app's name as Android Auto reports it as the media source (OpenHU recognizes the app by it). */
    fun hello(appVersion: String, label: String, features: List<String>): JSONObject = JSONObject()
        .put("type", "hello")
        .put("app", APP_ID)
        .put("version", appVersion)
        .put("label", label)
        .put("protocol", VERSION)
        .put("features", JSONArray(features))

    fun subscribe(topics: List<String>): JSONObject =
        JSONObject().put("type", "subscribe").put("topics", JSONArray(topics))

    fun request(id: Int, method: String, params: JSONObject): JSONObject =
        JSONObject().put("type", "request").put("id", id).put("method", method).put("params", params)

    fun emit(id: Int, topic: String, data: JSONObject): JSONObject =
        request(id, "bus.emit", JSONObject().put("topic", topic).put("data", data))

    /** A line from OpenHU, null when it is not a JSON object. */
    fun parse(line: String): Message? {
        val json = try { JSONObject(line) } catch (_: Exception) { return null }
        return when (json.optString("type")) {
            "event" -> Message.Event(json.optString("topic"), json.optJSONObject("data") ?: JSONObject())
            "response" -> Message.Response(json.optInt("id"), json.opt("result"), json.optString("error").ifEmpty { null })
            "hello" -> Message.Hello(json)
            else -> Message.Other(json)
        }
    }

    sealed interface Message {
        data class Event(val topic: String, val data: JSONObject) : Message
        data class Response(val id: Int, val result: Any?, val error: String?) : Message
        data class Hello(val json: JSONObject) : Message
        data class Other(val json: JSONObject) : Message
    }
}
