package org.openhu.companion.vehicle

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleValuesTest {
    private fun format(topic: String, json: String) = VehicleValues.format(topic, JSONObject(json), "on", "off")

    @Test
    fun values() {
        assertEquals("88 km/h", format("vehicle.speed", """{"kmh":87.6}"""))
        assertEquals("drive", format("vehicle.gear", """{"gear":"drive"}"""))
        assertEquals("reverse", format("vehicle.gear", """{"reverse":true}"""))
        assertEquals("57 % · 430 km", format("vehicle.fuel", """{"percent":57,"rangeKm":430}"""))
        assertEquals("430 km", format("vehicle.fuel", """{"rangeKm":430}"""))
        assertEquals("12346 km · trip 12.3 km", format("vehicle.odometer", """{"km":12345.6,"tripKm":12.34}"""))
        assertEquals("-3.5 °C", format("vehicle.climate", """{"outsideTemp":-3.5}"""))
        assertEquals("268° W", format("vehicle.compass", """{"heading":268}"""))
        assertEquals("on", format("vehicle.parkingBrake", """{"engaged":true}"""))
        assertNull(format("vehicle.speed", "{}"))
        assertNull(format("vehicle.unknown", """{"a":1}"""))
    }

    @Test
    fun directions() {
        assertEquals("N", VehicleValues.direction(359.0))
        assertEquals("NE", VehicleValues.direction(45.0))
        assertEquals("S", VehicleValues.direction(-180.0))
    }

    @Test
    fun fieldsAreSorted() {
        assertEquals(listOf("a" to "1", "b" to "x"), VehicleValues.fields(JSONObject("""{"b":"x","a":1}""")))
    }
}
