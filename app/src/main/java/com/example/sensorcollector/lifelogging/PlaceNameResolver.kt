package com.example.sensorcollector.lifelogging

import android.location.Geocoder
import android.location.Location
import android.os.Build
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class PlaceNameResolver(
    private val amapApiKey: String,
    private val geocoder: Geocoder,
    private val unavailableText: String
) {
    companion object {
        private const val AMAP_BASE_URL = "https://restapi.amap.com/v3/geocode/regeo"
    }

    data class AmapPlaceResult(
        val displayName: String?
    )

    fun fetchBestPlaceName(location: Location): String {
        val amapResult = fetchAmapPlaceName(location)
        if (amapResult?.displayName != null) {
            return amapResult.displayName
        }
        return resolvePlaceNameWithGeocoder(location)
    }

    private fun fetchAmapPlaceName(location: Location): AmapPlaceResult? {
        return try {
            val locationParam = URLEncoder.encode(
                "${location.longitude},${location.latitude}",
                Charsets.UTF_8.name()
            )
            val extensions = URLEncoder.encode("all", Charsets.UTF_8.name())
            val url = URL(
                "$AMAP_BASE_URL?key=$amapApiKey&location=$locationParam&extensions=$extensions&radius=20"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }

            connection.inputStream.bufferedReader().use(BufferedReader::readText)
                .let(::parseAmapPlaceName)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseAmapPlaceName(response: String): AmapPlaceResult? {
        val json = JSONObject(response)
        if (json.optString("status") != "1") {
            return null
        }

        val regeocode = json.optJSONObject("regeocode") ?: return null
        val addressComponent = regeocode.optJSONObject("addressComponent")
        val aois = regeocode.optJSONArray("aois")
        val pois = regeocode.optJSONArray("pois")

        val parts = linkedSetOf<String>()
        val building = addressComponent?.optString("building")
            ?.takeIf { it.isNotBlank() && it != "[]" }
        val streetNumber = addressComponent?.optString("streetNumber")
            ?.takeIf { it.isNotBlank() && it != "[]" }
            ?.let { raw ->
                val streetJson = runCatching { JSONObject(raw) }.getOrNull()
                val street = streetJson?.optString("street")?.takeIf { it.isNotBlank() }
                val number = streetJson?.optString("number")?.takeIf { it.isNotBlank() }
                listOfNotNull(street, number).joinToString("").takeIf { it.isNotBlank() }
            }
        val primaryPoi = pois?.optJSONObject(0)?.optString("name")
            ?.takeIf { it.isNotBlank() }
        val primaryAoi = aois?.optJSONObject(0)?.optString("name")
            ?.takeIf { it.isNotBlank() }
        val campusName = listOfNotNull(primaryPoi, primaryAoi).firstOrNull(::looksLikeCampusLevelName)
        val buildingName = listOfNotNull(primaryPoi, building, primaryAoi).firstOrNull(::looksLikeBuildingLevelName)
        val campusWithBuilding = combineCampusAndBuilding(campusName, buildingName)

        campusWithBuilding?.let(parts::add)
        buildingName?.let(parts::add)
        streetNumber?.let(parts::add)
        addressComponent?.optString("township")
            ?.takeIf { it.isNotBlank() }
            ?.let(parts::add)
        campusName?.let(parts::add)
        primaryPoi
            ?.takeUnless { it == campusName || it == buildingName || looksLikeCampusLevelName(it) }
            ?.let(parts::add)
        primaryAoi
            ?.takeUnless { it == campusName || it == buildingName || looksLikeCampusLevelName(it) }
            ?.let(parts::add)
        regeocode.optString("formatted_address")
            .takeIf { it.isNotBlank() }
            ?.let(parts::add)

        return AmapPlaceResult(
            parts.firstOrNull()
                ?: regeocode.optString("formatted_address").takeIf { it.isNotBlank() }
        )
    }

    private fun looksLikeBuildingLevelName(name: String): Boolean {
        val keywords = listOf("building", "center", "lab", "room", "tower", "plaza", "apartment")
        return keywords.any(name.lowercase()::contains)
    }

    private fun looksLikeCampusLevelName(name: String): Boolean {
        val keywords = listOf("university", "college", "campus", "school", "park")
        return keywords.any(name.lowercase()::contains)
    }

    private fun combineCampusAndBuilding(campusName: String?, buildingName: String?): String? {
        if (campusName.isNullOrBlank() || buildingName.isNullOrBlank()) {
            return null
        }
        if (buildingName.contains(campusName)) {
            return buildingName
        }
        return "$campusName, $buildingName"
    }

    private fun resolvePlaceNameWithGeocoder(location: Location): String {
        if (!Geocoder.isPresent()) {
            return unavailableText
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return unavailableText
        }

        @Suppress("DEPRECATION")
        return try {
            val address = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
            address?.toDisplayName() ?: unavailableText
        } catch (_: IOException) {
            unavailableText
        } catch (_: IllegalArgumentException) {
            unavailableText
        }
    }

    private fun android.location.Address.toDisplayName(): String {
        val primaryParts = listOfNotNull(
            premises,
            subThoroughfare,
            thoroughfare,
            featureName,
            subLocality,
            locality,
            subAdminArea,
            adminArea,
            countryName
        )
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (primaryParts.isNotEmpty()) {
            return primaryParts.joinToString(", ")
        }

        val addressLines = buildList {
            for (index in 0..maxAddressLineIndex) {
                getAddressLine(index)?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            }
        }.distinct()

        return addressLines.firstOrNull() ?: unavailableText
    }
}
