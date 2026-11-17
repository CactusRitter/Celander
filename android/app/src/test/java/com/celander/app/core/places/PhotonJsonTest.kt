package com.celander.app.core.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonJsonTest {
    private val answer = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[11.57,48.13]},
           "properties":{"osm_key":"place","name":"Marienplatz","postcode":"80331","city":"München","country":"Deutschland"}},
          {"type":"Feature","properties":{"osm_key":"building","street":"Kaufingerstraße","housenumber":"1","postcode":"80331","city":"München","country":"Deutschland"}},
          {"type":"Feature","properties":{"osm_key":"place","name":"München","state":"Bayern","country":"Deutschland"}},
          {"type":"Feature","properties":{"osm_key":"place","name":"Marienplatz","postcode":"80331","city":"München","country":"Deutschland"}},
          {"type":"Feature","properties":{"osm_key":"amenity","country":"Deutschland"}}
        ]}
    """.trimIndent()

    @Test
    fun namedPlacesKeepTheirNameAndGetTheAddressBelow() {
        val places = PhotonJson.suggestions(answer)
        assertEquals(PlaceSuggestion("Marienplatz", "80331 München, Deutschland"), places[0])
        assertEquals("Marienplatz, 80331 München, Deutschland", places[0].text)
    }

    @Test
    fun aBareAddressIsNamedByItsStreet() {
        assertEquals(PlaceSuggestion("Kaufingerstraße 1", "80331 München, Deutschland"), PhotonJson.suggestions(answer)[1])
    }

    @Test
    fun townsFallBackToTheirStateDuplicatesAndNamelessPlacesAreLeftOut() {
        val places = PhotonJson.suggestions(answer)
        assertEquals(PlaceSuggestion("München", "Bayern, Deutschland"), places[2])
        assertEquals(3, places.size)
    }

    @Test
    fun anAnswerWithoutFeaturesHasNoPlaces() {
        assertTrue(PhotonJson.suggestions("""{"type":"FeatureCollection"}""").isEmpty())
    }
}
