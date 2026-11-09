package org.openhu.companion.vehicle

import org.json.JSONObject
import java.util.Locale

/** The vehicle topics (OpenHU docs/api.md, "Vehicle data") as short texts. Pure: unit tested. */
object VehicleValues {
    /** The topics in the order the list shows them. */
    val TOPICS = listOf(
        "vehicle.speed", "vehicle.gear", "vehicle.fuel", "vehicle.engine", "vehicle.odometer", "vehicle.climate",
        "vehicle.compass", "vehicle.parkingBrake",
    )

    /** The value of a topic, null when the data has nothing to show. */
    fun format(topic: String, data: JSONObject, on: String, off: String): String? = when (topic) {
        "vehicle.speed" -> number(data, "kmh")?.let { "${whole(it)} km/h" }
        "vehicle.gear" -> data.opt("gear")?.toString()?.takeIf { it.isNotEmpty() }
            ?: if (data.has("reverse")) (if (data.optBoolean("reverse")) "reverse" else "drive") else null
        "vehicle.fuel" -> listOfNotNull(
            number(data, "percent")?.let { "${whole(it)} %" },
            number(data, "rangeKm")?.let { "${whole(it)} km" },
        ).joinToString(" · ").ifEmpty { null }
        "vehicle.engine" -> number(data, "rpm")?.let { "${whole(it)} rpm" }
        "vehicle.odometer" -> listOfNotNull(
            number(data, "km")?.let { "${whole(it)} km" },
            number(data, "tripKm")?.let { "trip ${String.format(Locale.ROOT, "%.1f", it)} km" },
        ).joinToString(" · ").ifEmpty { null }
        "vehicle.climate" -> number(data, "outsideTemp")?.let { "${String.format(Locale.ROOT, "%.1f", it)} °C" }
        "vehicle.compass" -> number(data, "heading")?.let { "${whole(it)}° ${direction(it)}" }
        "vehicle.parkingBrake" -> if (data.has("engaged")) (if (data.optBoolean("engaged")) on else off) else null
        else -> null
    }

    /** All fields of a topic, for its detail page. */
    fun fields(data: JSONObject): List<Pair<String, String>> =
        data.keys().asSequence().sorted().map { it to data.opt(it).toString() }.toList()

    fun direction(heading: Double): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((heading % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
    }

    private fun number(data: JSONObject, key: String): Double? =
        if (data.has(key)) data.optDouble(key).takeIf { !it.isNaN() } else null

    private fun whole(value: Double) = Math.round(value).toString()
}
