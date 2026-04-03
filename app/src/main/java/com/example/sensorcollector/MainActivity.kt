package com.example.sensorcollector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.sensorcollector.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    companion object {
        private const val AMAP_BASE_URL = "https://restapi.amap.com/v3/geocode/regeo"
        private const val PREFS_NAME = "sensorcollector_prefs"
        private const val KEY_LOCATION_PERMISSION_REQUESTED = "location_permission_requested"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private lateinit var geocoder: Geocoder
    private lateinit var locationManager: LocationManager
    private var lastResolvedLocationKey: String? = null
    private var placeLookupToken = 0L
    private var latestSpeedMps: Float? = null
    private var latestMotionEnergy = 0.0
    private var lastAccelerationMagnitude = SensorManager.GRAVITY_EARTH.toDouble()

    private enum class ActivityState {
        UNKNOWN,
        STATIONARY,
        WALKING,
        RUNNING,
        IN_VEHICLE
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (fineGranted) {
                binding.refreshButton.setText(R.string.request_permission)
                startLocationUpdates()
            } else {
                updatePermissionUiAfterDenial()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        geocoder = Geocoder(this, Locale.getDefault())

        setupSensorAvailability()
        updateCurrentStateUi(ActivityState.UNKNOWN, null, null)
        binding.refreshButton.setOnClickListener {
            handleLocationButtonClick()
        }
    }

    override fun onResume() {
        super.onResume()
        registerSensor(Sensor.TYPE_ACCELEROMETER)
        registerSensor(Sensor.TYPE_GYROSCOPE)
        registerSensor(Sensor.TYPE_MAGNETIC_FIELD)
        syncLocationUi()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    private fun setupSensorAvailability() {
        binding.accelerometerStatus.text = buildAvailabilityText(Sensor.TYPE_ACCELEROMETER)
        binding.gyroscopeStatus.text = buildAvailabilityText(Sensor.TYPE_GYROSCOPE)
        binding.magnetometerStatus.text = buildAvailabilityText(Sensor.TYPE_MAGNETIC_FIELD)
        binding.gpsStatus.text = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            getString(R.string.sensor_available)
        } else {
            getString(R.string.sensor_unavailable)
        }
    }

    private fun buildAvailabilityText(sensorType: Int): String {
        return if (sensorManager.getDefaultSensor(sensorType) != null) {
            getString(R.string.sensor_available)
        } else {
            getString(R.string.sensor_unavailable)
        }
    }

    private fun registerSensor(sensorType: Int) {
        sensorManager.getDefaultSensor(sensorType)?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        if (hasPreciseLocationPermission()) {
            binding.locationPermissionStatus.text = getString(R.string.location_permission_granted)
            binding.refreshButton.setText(R.string.request_permission)
            startLocationUpdates()
            return
        }

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOCATION_PERMISSION_REQUESTED, true)
            .apply()

        binding.locationPermissionStatus.text = getString(R.string.location_permission_requesting)
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun handleLocationButtonClick() {
        if (hasPreciseLocationPermission()) {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                openLocationSettings()
                return
            }
            startLocationUpdates()
            return
        }

        if (hasApproximateOnlyPermission() || isPrecisePermissionPermanentlyDenied()) {
            openAppSettings()
            return
        }

        requestLocationPermissionIfNeeded()
    }

    private fun syncLocationUi() {
        setupSensorAvailability()
        if (hasPreciseLocationPermission()) {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                binding.locationPermissionStatus.text =
                    getString(R.string.location_services_disabled)
                binding.refreshButton.setText(R.string.open_location_settings)
                return
            }
            binding.locationPermissionStatus.text = getString(R.string.location_permission_granted)
            binding.refreshButton.setText(R.string.request_permission)
            startLocationUpdates()
            return
        }

        if (hasApproximateOnlyPermission()) {
            binding.locationPermissionStatus.text =
                getString(R.string.location_permission_need_precise)
            binding.refreshButton.setText(R.string.open_app_settings)
            return
        }

        if (isPrecisePermissionPermanentlyDenied()) {
            binding.locationPermissionStatus.text =
                getString(R.string.location_permission_open_precise_settings)
            binding.refreshButton.setText(R.string.open_app_settings)
            return
        }

        binding.locationPermissionStatus.text = getString(R.string.location_permission_waiting)
        binding.refreshButton.setText(R.string.request_permission)
    }

    private fun updatePermissionUiAfterDenial() {
        if (hasApproximateOnlyPermission()) {
            binding.locationPermissionStatus.text =
                getString(R.string.location_permission_need_precise)
            binding.refreshButton.setText(R.string.open_app_settings)
        } else if (isPrecisePermissionPermanentlyDenied()) {
            binding.locationPermissionStatus.text =
                getString(R.string.location_permission_open_precise_settings)
            binding.refreshButton.setText(R.string.open_app_settings)
        } else {
            binding.locationPermissionStatus.text = getString(R.string.location_permission_denied)
            binding.refreshButton.setText(R.string.request_permission)
        }
    }

    private fun hasPreciseLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasApproximateOnlyPermission(): Boolean {
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return coarseGranted && !hasPreciseLocationPermission()
    }

    private fun isPrecisePermissionPermanentlyDenied(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val requestedBefore = prefs.getBoolean(KEY_LOCATION_PERMISSION_REQUESTED, false)
        if (!requestedBefore || hasPreciseLocationPermission()) {
            return false
        }

        val fineRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        return !fineRationale
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun startLocationUpdates() {
        if (!hasPreciseLocationPermission()) {
            return
        }

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            binding.locationPermissionStatus.text = getString(R.string.location_services_disabled)
            binding.refreshButton.setText(R.string.open_location_settings)
            return
        }

        binding.refreshButton.setText(R.string.request_permission)
        binding.locationPermissionStatus.text = getString(R.string.location_waiting_for_fix)
        locationManager.removeUpdates(this)
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            0f,
            this,
            Looper.getMainLooper()
        )
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::renderLocation)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                binding.accelerometerValue.text = formatVector(event.values)
                latestMotionEnergy = updateMotionEnergy(event.values)
                updateCurrentStateUi(classifyActivityState(), latestSpeedMps, latestMotionEnergy)
            }
            Sensor.TYPE_GYROSCOPE -> {
                binding.gyroscopeValue.text = formatVector(event.values)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                binding.magnetometerValue.text = formatVector(event.values)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        if (location.provider != LocationManager.GPS_PROVIDER) {
            return
        }
        renderLocation(location)
    }

    private fun renderLocation(location: Location) {
        binding.locationPermissionStatus.text = getString(R.string.location_listening)
        latestSpeedMps = location.speed.takeIf { location.hasSpeed() }
        binding.latitudeValue.text = formatDouble(location.latitude, 6)
        binding.longitudeValue.text = formatDouble(location.longitude, 6)
        binding.altitudeValue.text = if (location.hasAltitude()) {
            getString(R.string.meter_value, formatDouble(location.altitude, 2))
        } else {
            getString(R.string.value_unavailable)
        }
        binding.speedValue.text = if (location.hasSpeed()) {
            getString(R.string.speed_value, formatDouble(location.speed.toDouble(), 2))
        } else {
            getString(R.string.value_unavailable)
        }
        binding.bearingValue.text = if (location.hasBearing()) {
            getString(R.string.degree_value, formatDouble(location.bearing.toDouble(), 1))
        } else {
            getString(R.string.value_unavailable)
        }
        binding.accuracyValue.text = if (location.hasAccuracy()) {
            getString(R.string.meter_value, formatDouble(location.accuracy.toDouble(), 2))
        } else {
            getString(R.string.value_unavailable)
        }
        binding.providerValue.text = location.provider ?: getString(R.string.value_unavailable)
        updateCurrentStateUi(classifyActivityState(), latestSpeedMps, latestMotionEnergy)
        resolvePlaceName(location)
    }

    private fun resolvePlaceName(location: Location) {
        val locationKey = "${formatDouble(location.latitude, 4)},${formatDouble(location.longitude, 4)}"
        if (lastResolvedLocationKey == locationKey) {
            return
        }

        lastResolvedLocationKey = locationKey
        val lookupToken = ++placeLookupToken
        binding.placeNameValue.text = getString(R.string.place_name_loading)
        thread {
            val amapResult = fetchAmapPlaceName(location)
            if (amapResult != null) {
                runOnUiThread {
                    if (lookupToken == placeLookupToken) {
                        binding.placeNameValue.text = amapResult.displayName
                            ?: getString(R.string.place_name_unavailable)
                    }
                }
                return@thread
            }

            val localName = resolvePlaceNameWithGeocoder(location)
            runOnUiThread {
                if (lookupToken == placeLookupToken) {
                    binding.placeNameValue.text = localName
                }
            }
        }
    }

    private data class AmapPlaceResult(
        val displayName: String?
    )

    private fun fetchAmapPlaceName(location: Location): AmapPlaceResult? {
        return try {
            val locationParam = URLEncoder.encode(
                "${location.longitude},${location.latitude}",
                Charsets.UTF_8.name()
            )
            val extensions = URLEncoder.encode("all", Charsets.UTF_8.name())
            val url = URL(
                "$AMAP_BASE_URL?key=${BuildConfig.AMAP_API_KEY}&location=$locationParam&extensions=$extensions&radius=20"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }

            connection.inputStream.bufferedReader().use(BufferedReader::readText)
                .let(::parseAmapPlaceName)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
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
        val keywords = listOf("楼", "馆", "中心", "实验室", "教室", "教学", "大厦", "广场", "公寓")
        return keywords.any(name::contains)
    }

    private fun looksLikeCampusLevelName(name: String): Boolean {
        val keywords = listOf("大学", "学院", "校区", "学校", "园区")
        return keywords.any(name::contains)
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
            return getString(R.string.place_name_unavailable)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return getString(R.string.place_name_unavailable)
        }

        @Suppress("DEPRECATION")
        return try {
            val address = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
            address?.toDisplayName() ?: getString(R.string.place_name_unavailable)
        } catch (_: IOException) {
            getString(R.string.place_name_unavailable)
        } catch (_: IllegalArgumentException) {
            getString(R.string.place_name_unavailable)
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

        return addressLines.firstOrNull() ?: getString(R.string.place_name_unavailable)
    }

    private fun formatVector(values: FloatArray): String {
        return getString(
            R.string.vector_value,
            formatDouble(values.getOrNull(0)?.toDouble() ?: 0.0, 3),
            formatDouble(values.getOrNull(1)?.toDouble() ?: 0.0, 3),
            formatDouble(values.getOrNull(2)?.toDouble() ?: 0.0, 3)
        )
    }

    private fun updateMotionEnergy(values: FloatArray): Double {
        val x = values.getOrNull(0)?.toDouble() ?: 0.0
        val y = values.getOrNull(1)?.toDouble() ?: 0.0
        val z = values.getOrNull(2)?.toDouble() ?: 0.0
        val magnitude = sqrt(x * x + y * y + z * z)
        val delta = abs(magnitude - lastAccelerationMagnitude)
        lastAccelerationMagnitude = magnitude
        latestMotionEnergy = latestMotionEnergy * 0.8 + delta * 0.2
        return latestMotionEnergy
    }

    private fun classifyActivityState(): ActivityState {
        val speed = latestSpeedMps ?: return ActivityState.UNKNOWN
        val motionEnergy = latestMotionEnergy

        return when {
            speed >= 6.0f -> ActivityState.IN_VEHICLE
            speed >= 2.4f -> ActivityState.RUNNING
            speed >= 0.8f -> ActivityState.WALKING
            motionEnergy >= 1.2 -> ActivityState.WALKING
            else -> ActivityState.STATIONARY
        }
    }

    private fun updateCurrentStateUi(
        state: ActivityState,
        speedMps: Float?,
        motionEnergy: Double?
    ) {
        binding.currentStateValue.text = when (state) {
            ActivityState.UNKNOWN -> getString(R.string.activity_state_unknown)
            ActivityState.STATIONARY -> getString(R.string.activity_state_stationary)
            ActivityState.WALKING -> getString(R.string.activity_state_walking)
            ActivityState.RUNNING -> getString(R.string.activity_state_running)
            ActivityState.IN_VEHICLE -> getString(R.string.activity_state_in_vehicle)
        }

        binding.currentStateDetail.text = if (speedMps == null || motionEnergy == null) {
            getString(R.string.current_state_waiting)
        } else {
            getString(
                R.string.current_state_detail,
                formatDouble(speedMps.toDouble(), 2),
                formatDouble(motionEnergy, 2)
            )
        }
    }

    private fun formatDouble(value: Double, digits: Int): String {
        return String.format(Locale.US, "%.${digits}f", value)
    }
}
