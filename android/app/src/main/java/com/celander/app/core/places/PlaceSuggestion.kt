package com.celander.app.core.places

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** A place offered while typing a location: [name] on top, the address in [detail]; [text] goes into the field. */
data class PlaceSuggestion(val name: String, val detail: String) {
    val text: String get() = if (detail.isEmpty()) name else "$name, $detail"
}

/** Reads the GeoJSON that Photon (photon.komoot.io, OpenStreetMap data) answers with. */
object PhotonJson {
    fun suggestions(body: String): List<PlaceSuggestion> {
        val features = Json.parseToJsonElement(body).jsonObject["features"]?.jsonArray ?: return emptyList()
        return features.mapNotNull { feature -> (feature.jsonObject["properties"] as? JsonObject)?.let(::suggestion) }.distinctBy { it.text }
    }

    /** A named place keeps its name and gets street, town and country below; a bare address is its street. */
    private fun suggestion(properties: JsonObject): PlaceSuggestion? {
        fun field(key: String) = (properties[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val street = listOfNotNull(field("street"), field("housenumber")).joinToString(" ").ifEmpty { null }
        val town = listOfNotNull(field("postcode"), field("city") ?: field("town") ?: field("village") ?: field("district")).joinToString(" ").ifEmpty { null }
        val name = field("name") ?: street ?: return null
        val detail = listOfNotNull(street, town ?: field("state"), field("country")).filter { it != name }.joinToString(", ")
        return PlaceSuggestion(name, detail)
    }
}
