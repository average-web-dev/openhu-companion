package org.openhu.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionsTest {
    private val vehicle = Extensions.Declared(
        "org.openhu.vehicle", "org.openhu.companion.vehicle", "Vehicle", listOf("vehicle.*"), listOf("vehicle.control.set"),
    )

    @Test
    fun parsesTheDeclarations() {
        val data = JSONObject("""{"extensions":[
            {"plugin":"org.openhu.vehicle","package":"org.openhu.companion.vehicle","name":"Vehicle",
             "topics":["vehicle.*"],"commands":["vehicle.control.set"]},
            {"plugin":"broken"}]}""")
        val declared = Extensions.parseDeclared(data)
        assertEquals(listOf(vehicle), declared)
        assertEquals(vehicle, Extensions.find(declared, "org.openhu.vehicle", "org.openhu.companion.vehicle"))
        // another app claiming the plugin gets nothing
        assertNull(Extensions.find(declared, "org.openhu.vehicle", "com.example.other"))
        assertTrue(Extensions.parseDeclared(JSONObject()).isEmpty())
    }

    @Test
    fun patterns() {
        assertTrue(Extensions.matches("vehicle.*", "vehicle.speed"))
        assertFalse(Extensions.matches("vehicle.*", "vehicles.speed"))
        assertFalse(Extensions.matches("vehicle.speed", "vehicle.gear"))
        assertTrue(Extensions.matches("*", "system.update"))
        assertTrue(Extensions.covers(listOf("vehicle.*"), "vehicle.speed"))
        assertTrue(Extensions.covers(listOf("vehicle.*"), "vehicle.control.*"))
        assertFalse(Extensions.covers(listOf("vehicle.*"), "*"))
        assertFalse(Extensions.covers(listOf("vehicle.speed"), "vehicle.*"))
    }

    @Test
    fun onlyThePluginsTopicsAndCommands() {
        val subscribe = Extensions.decide(JSONObject("""{"type":"subscribe","topics":["vehicle.*","store.state"]}"""), vehicle)
        assertEquals(Extensions.Action.Subscribe(listOf("vehicle.*")), subscribe)
        assertTrue(Extensions.decide(JSONObject("""{"type":"subscribe","topics":["*"]}"""), vehicle) is Extensions.Action.Reject)

        val get = Extensions.decide(JSONObject("""{"type":"request","id":2,"method":"bus.get","params":{"topic":"vehicle.speed"}}"""), vehicle)
        assertEquals(Extensions.Action.Get(2, "vehicle.speed"), get)
        val token = Extensions.decide(JSONObject("""{"type":"request","id":3,"method":"settings.get","params":{}}"""), vehicle)
        assertEquals(Extensions.Action.Reject(3, "method not allowed: settings.get"), token)
        val other = Extensions.decide(JSONObject("""{"type":"request","id":4,"method":"bus.get","params":{"topic":"media.current"}}"""), vehicle)
        assertTrue(other is Extensions.Action.Reject)

        val emit = Extensions.decide(JSONObject("""{"type":"emit","topic":"vehicle.control.set","data":{"id":"ac"}}"""), vehicle)
        assertTrue(emit is Extensions.Action.Emit)
        assertTrue(Extensions.decide(JSONObject("""{"type":"emit","topic":"store.install","data":{}}"""), vehicle) is Extensions.Action.Reject)
    }

    @Test
    fun answers() {
        assertEquals("allowed", Extensions.state(true, Extensions.Access.ALLOWED, "x").getString("access"))
        assertEquals("nope", Extensions.response(1, null, "nope").getString("error"))
        assertFalse(Extensions.response(1, JSONObject()).has("error"))
    }
}
