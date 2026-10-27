package com.example.sensorcollector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.sensorcollector.databinding.ActivityMainBinding
import com.example.sensorcollector.lifelogging.ActivityState
import com.example.sensorcollector.lifelogging.AndroidSamplingResources
import com.example.sensorcollector.lifelogging.AppPage
import com.example.sensorcollector.lifelogging.CaptureCadence
import com.example.sensorcollector.lifelogging.CoarseEventEstimator
import com.example.sensorcollector.lifelogging.CoarseEventType
import com.example.sensorcollector.lifelogging.ContextSnapshot
import com.example.sensorcollector.lifelogging.LifelogSnapshot
import com.example.sensorcollector.lifelogging.LocalEventEstimate
import com.example.sensorcollector.lifelogging.LscDatasetExporter
import com.example.sensorcollector.lifelogging.MotionSnapshot
import com.example.sensorcollector.lifelogging.MotionStateClassifier
import com.example.sensorcollector.lifelogging.PlaceNameResolver
import com.example.sensorcollector.lifelogging.SamplingConfig
import com.example.sensorcollector.lifelogging.SamplingDecision
import com.example.sensorcollector.lifelogging.SamplingMode
import com.example.sensorcollector.lifelogging.SamplingScheduler
import com.example.sensorcollector.lifelogging.SnapshotQueueStore
import com.example.sensorcollector.lifelogging.UploadClient
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    companion object {
        private const val AMAP_BASE_URL = "https://restapi.amap.com/v3/geocode/regeo"
        private const val PREFS_NAME = "sensorcollector_prefs"
        private const val KEY_LOCATION_PERMISSION_REQUESTED = "location_permission_requested"
        private const val SNAPSHOT_RETENTION_MS = 72L * 60L * 60L * 1000L
        private const val CONTEXT_REFRESH_MS = 1_000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private lateinit var geocoder: Geocoder
    private lateinit var locationManager: LocationManager
    private lateinit var placeNameResolver: PlaceNameResolver
    private lateinit var snapshotQueueStore: SnapshotQueueStore
    private lateinit var lscDatasetExporter: LscDatasetExporter
    private lateinit var uploadClient: UploadClient
    private lateinit var samplingResources: AndroidSamplingResources
    private var imageCapture: ImageCapture? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val motionStateClassifier = MotionStateClassifier()
    private val samplingConfig = SamplingConfig()
    private val samplingScheduler = SamplingScheduler(samplingConfig)
    private val captureCadence = CaptureCadence()
    private val coarseEventEstimator = CoarseEventEstimator()
    private var lastResolvedLocationKey: String? = null
    private var lastPlaceLookupMs: Long? = null
    private var placeLookupToken = 0L
    private var latestSpeedMps: Float? = null
    private var currentMotionSnapshot = MotionSnapshot(null, 0.0, ActivityState.UNKNOWN)
    private var currentSamplingDecision = SamplingDecision(
        SamplingMode.LOW_FREQUENCY,
        samplingConfig.lowFrequencyIntervalMs,
        listOf("steady_context")
    )
    private var currentEventEstimate = LocalEventEstimate(CoarseEventType.UNKNOWN, 0.0, false, false, listOf("context_stable"))
    private var activePage = AppPage.CAMERA
    private var latestPlaceName: String? = null
    private var latestLocation: Location? = null
    private var latestPlaceElapsedRealtimeMs: Long? = null
    private var hasFreshPhoto = false
    private var latestPhotoFile: File? = null
    private var autoCaptureEnabled = false
    private var isForeground = false
    private var cameraStarting = false
    private var cameraRequestGeneration = 0L
    private var lastExportSummary: String? = null
    private val captureRunnable = Runnable {
        if (!autoCaptureEnabled || !isForeground) {
            return@Runnable
        }
        captureCurrentFrame()
    }
    private val contextRefreshRunnable = object : Runnable {
        override fun run() {
            if (!isForeground) return
            recalculateLifeloggingState()
            mainHandler.postDelayed(this, CONTEXT_REFRESH_MS)
        }
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

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraPreview()
                setAutoCaptureEnabled(true)
            } else {
                binding.cameraStatusText.text = getString(R.string.camera_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        geocoder = Geocoder(this, Locale.getDefault())
        placeNameResolver = PlaceNameResolver(
            amapApiKey = BuildConfig.AMAP_API_KEY,
            geocoder = geocoder,
            unavailableText = getString(R.string.place_name_unavailable)
        )
        snapshotQueueStore = SnapshotQueueStore(this)
        lscDatasetExporter = LscDatasetExporter(this)
        uploadClient = UploadClient(BuildConfig.LIFELOG_UPLOAD_URL)
        samplingResources = AndroidSamplingResources(this)

        setupSensorAvailability()
        updateCurrentStateUi(
            currentMotionSnapshot.state,
            currentMotionSnapshot.speedMps,
            currentMotionSnapshot.motionEnergy
        )
        updateDecisionUi()
        updateLatestPhotoPreview()
        updateExportStatus()
        setupPageNavigation()
        binding.captureButton.setOnClickListener {
            toggleAutoCapture()
        }
        binding.refreshButton.setOnClickListener {
            handleLocationButtonClick()
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        motionStateClassifier.reset()
        samplingScheduler.reset()
        mainHandler.post(contextRefreshRunnable)
        registerSensor(Sensor.TYPE_ACCELEROMETER)
        registerSensor(Sensor.TYPE_GYROSCOPE)
        registerSensor(Sensor.TYPE_MAGNETIC_FIELD)
        syncLocationUi()
        renderActivePage()
        snapshotQueueStore.deleteExpired(SNAPSHOT_RETENTION_MS)
        updateLatestPhotoPreview()
        drainSnapshotQueue()
        ensureCameraReadyIfPossible()
    }

    override fun onPause() {
        isForeground = false
        cameraRequestGeneration += 1L
        placeLookupToken += 1L
        cameraStarting = false
        imageCapture = null
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        stopAutoCaptureLoop()
        mainHandler.removeCallbacks(contextRefreshRunnable)
        super.onPause()
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

    private fun setupPageNavigation() {
        binding.cameraTabButton.setOnClickListener {
            activePage = AppPage.CAMERA
            renderActivePage()
        }
        binding.dataTabButton.setOnClickListener {
            activePage = AppPage.DATA
            renderActivePage()
        }
        renderActivePage()
    }

    private fun renderActivePage() {
        val isCameraPage = activePage == AppPage.CAMERA
        binding.cameraPage.visibility = if (isCameraPage) android.view.View.VISIBLE else android.view.View.GONE
        binding.dataPage.visibility = if (isCameraPage) android.view.View.GONE else android.view.View.VISIBLE
        binding.cameraTabButton.isEnabled = !isCameraPage
        binding.dataTabButton.isEnabled = isCameraPage
        binding.cameraTabButton.alpha = if (isCameraPage) 1.0f else 0.75f
        binding.dataTabButton.alpha = if (isCameraPage) 0.75f else 1.0f
    }

    private fun handleCaptureClick() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            toggleAutoCapture()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleAutoCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        if (!autoCaptureEnabled) {
            startCameraPreview()
            setAutoCaptureEnabled(true)
        } else {
            setAutoCaptureEnabled(false)
        }
    }

    private fun setAutoCaptureEnabled(enabled: Boolean) {
        if (enabled && !autoCaptureEnabled && !captureCadence.isCaptureInFlight) {
            captureCadence.reset()
        }
        autoCaptureEnabled = enabled
        binding.captureButton.text = if (enabled) {
            getString(R.string.auto_capture_stop)
        } else {
            getString(R.string.auto_capture_start)
        }
        binding.cameraStatusText.text = if (enabled) {
            getString(R.string.camera_auto_capture_running)
        } else {
            getString(R.string.camera_waiting)
        }

        if (enabled) {
            scheduleNextCapture()
        } else {
            stopAutoCaptureLoop()
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
        try {
            locationManager.removeUpdates(this)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                this,
                Looper.getMainLooper()
            )
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::renderLocation)
        } catch (_: SecurityException) {
            // Permission can be revoked after the initial check.
            latestLocation = null
            latestSpeedMps = null
            updatePermissionUiAfterDenial()
            recalculateLifeloggingState()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                binding.accelerometerValue.text = formatVector(event.values)
                motionStateClassifier.updateAccelerometer(event.values, event.timestamp / 1_000_000L)
            }
            Sensor.TYPE_GYROSCOPE -> {
                binding.gyroscopeValue.text = formatVector(event.values)
                motionStateClassifier.updateGyroscope(event.values, event.timestamp / 1_000_000L)
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
        val nowMs = SystemClock.elapsedRealtime()
        val observedAtMs = location.elapsedRealtimeNanos / 1_000_000L
        if (nowMs - observedAtMs !in 0..samplingConfig.maxLocationAgeMs ||
            !location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0 ||
            latestLocation?.let { location.elapsedRealtimeNanos <= it.elapsedRealtimeNanos } == true
        ) return
        latestLocation = Location(location)
        binding.locationPermissionStatus.text = getString(R.string.location_listening)
        latestSpeedMps = location.speed.takeIf {
            location.hasSpeed() && it.isFinite() && it in 0f..samplingConfig.maxTravelSpeedMps.toFloat() &&
                location.hasAccuracy() && location.accuracy.isFinite() &&
                location.accuracy in 0f..samplingConfig.maxLocationAccuracyMeters &&
                (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 2f)
        }
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
        recalculateLifeloggingState()
        if (location.hasAccuracy() && location.accuracy in 0f..samplingConfig.maxLocationAccuracyMeters) {
            resolvePlaceName(location)
        }
    }

    private fun resolvePlaceName(location: Location) {
        val locationKey = "${formatDouble(location.latitude, 4)},${formatDouble(location.longitude, 4)}"
        val nowMs = SystemClock.elapsedRealtime()
        // Coordinate changes must not bypass the lookup rate limit.
        if (lastPlaceLookupMs?.let { nowMs - it < samplingConfig.placeLookupIntervalMs } == true) return
        if (lastResolvedLocationKey == locationKey &&
            latestPlaceElapsedRealtimeMs?.let { nowMs - it < samplingConfig.maxPlaceAgeMs } == true
        ) {
            return
        }

        lastResolvedLocationKey = locationKey
        lastPlaceLookupMs = nowMs
        val lookupToken = ++placeLookupToken
        binding.placeNameValue.text = getString(R.string.place_name_loading)
        thread {
            val localName = runCatching { placeNameResolver.fetchBestPlaceName(location) }
                .getOrDefault(getString(R.string.place_name_unavailable))
            runOnUiThread {
                if (lookupToken == placeLookupToken && isForeground) {
                    binding.placeNameValue.text = localName
                    latestPlaceName = localName.takeUnless {
                        it.isBlank() || it == getString(R.string.place_name_unavailable)
                    }
                    latestPlaceElapsedRealtimeMs = if (latestPlaceName != null) {
                        location.elapsedRealtimeNanos / 1_000_000L
                    } else {
                        null
                    }
                    recalculateLifeloggingState()
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
        val campusName = listOfNotNull(primaryPoi, primaryAoi).firstOrNull(::looksLikeCampusLevelNameZh)
        val buildingName = listOfNotNull(primaryPoi, building, primaryAoi).firstOrNull(::looksLikeBuildingLevelNameZh)
        val campusWithBuilding = combineCampusAndBuilding(campusName, buildingName)

        campusWithBuilding?.let(parts::add)
        buildingName?.let(parts::add)
        streetNumber?.let(parts::add)
        addressComponent?.optString("township")
            ?.takeIf { it.isNotBlank() }
            ?.let(parts::add)
        campusName?.let(parts::add)
        primaryPoi
            ?.takeUnless { it == campusName || it == buildingName || looksLikeCampusLevelNameZh(it) }
            ?.let(parts::add)
        primaryAoi
            ?.takeUnless { it == campusName || it == buildingName || looksLikeCampusLevelNameZh(it) }
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

    private fun looksLikeBuildingLevelNameZh(name: String): Boolean {
        val keywords = listOf("楼", "馆", "中心", "实验室", "教室", "教学", "大厦", "广场", "公寓")
        return keywords.any(name::contains)
    }

    private fun looksLikeCampusLevelNameZh(name: String): Boolean {
        val keywords = listOf("大学", "学院", "校区", "学校", "园区")
        return keywords.any(name::contains)
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

        binding.currentStateDetail.text = getString(
            R.string.current_state_detail,
            speedMps?.let { formatDouble(it.toDouble(), 2) } ?: getString(R.string.value_unavailable),
            motionEnergy?.let { formatDouble(it, 2) } ?: getString(R.string.value_unavailable)
        )
    }

    private fun recalculateLifeloggingState() {
        val nowMs = SystemClock.elapsedRealtime()
        val location = freshLocation(nowMs)
        currentMotionSnapshot = motionStateClassifier.classify(
            latestSpeedMps.takeIf { location != null },
            nowMs
        )
        val context = currentContextSnapshot(nowMs)
        val previousIntervalMs = currentSamplingDecision.intervalMs
        currentSamplingDecision = samplingScheduler.onContextUpdated(context, nowMs, samplingResources.read())
        currentEventEstimate = coarseEventEstimator.estimate(
            current = context,
            samplingDecision = currentSamplingDecision,
            hasFreshPhoto = hasFreshPhoto
        )
        updateCurrentStateUi(
            currentMotionSnapshot.state,
            currentMotionSnapshot.speedMps,
            currentMotionSnapshot.motionEnergy
        )
        updateDecisionUi()
        if (previousIntervalMs != currentSamplingDecision.intervalMs) scheduleNextCapture()
    }

    private fun updateDecisionUi() {
        binding.eventEstimateValue.text = getString(
            R.string.event_estimate_value,
            eventTypeLabel(currentEventEstimate.eventType),
            formatDouble(currentEventEstimate.confidence * 100.0, 0)
        )
        binding.uploadDecisionValue.text = getString(
            R.string.upload_decision_value,
            yesNo(currentEventEstimate.worthUploading),
            yesNo(currentEventEstimate.possibleNewEvent),
            samplingModeLabel(currentSamplingDecision)
        )
        binding.samplingStatusValue.text = getString(
            R.string.sampling_status_value,
            samplingModeLabel(currentSamplingDecision),
            currentSamplingDecision.reasons.joinToString(", ") { reason ->
                samplingReasonLabel(reason)
            }
        )
    }

    private fun samplingReasonLabel(reason: String): String {
        val resourceId = when (reason) {
            "place_changed" -> R.string.sampling_reason_place
            "state_changed" -> R.string.sampling_reason_motion
            "location_shifted" -> R.string.sampling_reason_location
            "boost_window_active" -> R.string.sampling_reason_boost
            "boost_cooldown" -> R.string.sampling_reason_cooldown
            "power_save" -> R.string.sampling_reason_power_save
            "low_battery" -> R.string.sampling_reason_battery
            "thermal_warm" -> R.string.sampling_reason_warm
            "thermal_hot" -> R.string.sampling_reason_hot
            else -> R.string.sampling_reason_stable
        }
        return getString(resourceId)
    }

    private fun eventTypeLabel(eventType: CoarseEventType): String {
        return when (eventType) {
            CoarseEventType.UNKNOWN -> getString(R.string.event_unknown)
            CoarseEventType.COMMUTING -> getString(R.string.event_commuting)
            CoarseEventType.STUDYING -> getString(R.string.event_studying)
            CoarseEventType.EATING -> getString(R.string.event_eating)
            CoarseEventType.SHOPPING -> getString(R.string.event_shopping)
            CoarseEventType.REPAIRING -> getString(R.string.event_repairing)
        }
    }

    private fun yesNo(value: Boolean): String {
        return if (value) getString(R.string.yes_value) else getString(R.string.no_value)
    }

    private fun samplingModeLabel(decision: SamplingDecision): String {
        val seconds = decision.intervalMs / 1000L
        return when (decision.mode) {
            SamplingMode.LOW_FREQUENCY -> getString(R.string.sampling_low_frequency, seconds)
            SamplingMode.HIGH_FREQUENCY -> getString(R.string.sampling_high_frequency, seconds)
        }
    }

    private fun ensureCameraReadyIfPossible() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreview()
        }
    }

    private fun startCameraPreview() {
        if (!isForeground || cameraStarting || imageCapture != null) return
        cameraStarting = true
        val generation = ++cameraRequestGeneration
        binding.cameraStatusText.text = getString(R.string.camera_preview_starting)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (!isForeground || generation != cameraRequestGeneration) return@addListener
            cameraStarting = false
            runCatching {
                val cameraProvider = cameraProviderFuture.get()
                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }
                val capture = ImageCapture.Builder().build()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                imageCapture = capture
                binding.cameraStatusText.text = getString(R.string.camera_ready)
                scheduleNextCapture()
            }.onFailure { error ->
                imageCapture = null
                setAutoCaptureEnabled(false)
                binding.cameraStatusText.text = getString(
                    R.string.camera_capture_failed_with_reason,
                    error.message ?: error.javaClass.simpleName
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scheduleNextCapture() {
        mainHandler.removeCallbacks(captureRunnable)
        if (!autoCaptureEnabled || !isForeground || imageCapture == null) return
        val delayMs = captureCadence.nextDelayMs(
            SystemClock.elapsedRealtime(),
            currentSamplingDecision.intervalMs
        ) ?: return
        mainHandler.postDelayed(captureRunnable, delayMs)
    }

    private fun stopAutoCaptureLoop() {
        mainHandler.removeCallbacks(captureRunnable)
    }

    private fun captureCurrentFrame() {
        if (!autoCaptureEnabled || !isForeground) return
        // Resource limits may have changed since this capture was scheduled.
        recalculateLifeloggingState()
        val delayMs = captureCadence.nextDelayMs(
            SystemClock.elapsedRealtime(),
            currentSamplingDecision.intervalMs
        ) ?: return
        if (delayMs > 0L) {
            scheduleNextCapture()
            return
        }
        val capture = imageCapture ?: return
        if (!captureCadence.onCaptureStarted(SystemClock.elapsedRealtime())) return
        mainHandler.removeCallbacks(captureRunnable)
        binding.cameraStatusText.text = getString(R.string.camera_capture_requested)
        // Freeze metadata before asynchronous capture; later sensor updates belong to the next image.
        val captureContext = currentContextSnapshot()
        val captureDecision = currentSamplingDecision.copy(reasons = currentSamplingDecision.reasons.toList())
        val captureEvent = coarseEventEstimator.estimate(captureContext, captureDecision, hasFreshPhoto = true)
        val timestampMs = System.currentTimeMillis()
        val outputFile = File(snapshotQueueStore.imagesDir(), "photo_${UUID.randomUUID()}.jpg")
        val snapshot = LifelogSnapshot(
            id = UUID.randomUUID().toString(),
            timestampMs = timestampMs,
            placeName = captureContext.placeName,
            latitude = captureContext.latitude,
            longitude = captureContext.longitude,
            altitude = latestLocation?.takeIf {
                captureContext.latitude != null && it.hasAltitude()
            }?.altitude,
            motion = captureContext.motion,
            samplingDecision = captureDecision,
            eventEstimate = captureEvent,
            imageFileName = outputFile.name
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                captureCadence.onCaptureFinished(SystemClock.elapsedRealtime(), success = true)
                latestPhotoFile = outputFile
                hasFreshPhoto = true
                if (isForeground) {
                    showPhotoPreview(outputFile)
                    recalculateLifeloggingState()
                    binding.cameraStatusText.text = getString(R.string.camera_capture_saved)
                }
                enqueueSnapshotIfNeeded(snapshot, outputFile)
                scheduleNextCapture()
            }

            override fun onError(exception: ImageCaptureException) {
                captureCadence.onCaptureFinished(SystemClock.elapsedRealtime(), success = false)
                outputFile.delete()
                if (isForeground) {
                    binding.cameraStatusText.text = getString(
                        R.string.camera_capture_failed_with_reason,
                        exception.message ?: exception.imageCaptureError.toString()
                    )
                }
                scheduleNextCapture()
            }
        }
        try {
            capture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), callback)
        } catch (error: RuntimeException) {
            captureCadence.onCaptureFinished(SystemClock.elapsedRealtime(), success = false)
            outputFile.delete()
            binding.cameraStatusText.text = getString(
                R.string.camera_capture_failed_with_reason,
                error.message ?: error.javaClass.simpleName
            )
            scheduleNextCapture()
        }
    }

    private fun freshLocation(nowMs: Long): Location? = latestLocation?.takeIf {
        nowMs - it.elapsedRealtimeNanos / 1_000_000L in 0..samplingConfig.maxLocationAgeMs
    }

    private fun currentContextSnapshot(nowMs: Long = SystemClock.elapsedRealtime()): ContextSnapshot {
        val location = freshLocation(nowMs)
        val placeTimestamp = latestPlaceElapsedRealtimeMs
        return ContextSnapshot(
            placeName = latestPlaceName.takeIf {
                location != null && placeTimestamp != null && nowMs - placeTimestamp in 0..samplingConfig.maxPlaceAgeMs
            },
            latitude = location?.latitude,
            longitude = location?.longitude,
            motion = currentMotionSnapshot,
            locationAccuracyMeters = location?.takeIf { it.hasAccuracy() }?.accuracy,
            locationElapsedRealtimeMs = location?.elapsedRealtimeNanos?.div(1_000_000L),
            placeElapsedRealtimeMs = placeTimestamp
        )
    }

    private fun savePreviewBitmap(bitmap: Bitmap): File? {
        val file = File(
            snapshotQueueStore.imagesDir(),
            "photo_${System.currentTimeMillis()}.jpg"
        )
        return runCatching {
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                output.flush()
            }
            file
        }.getOrNull()
    }

    private fun updateLatestPhotoPreview() {
        val latestImage = snapshotQueueStore.imagesDir()
            .listFiles()
            ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            ?.maxByOrNull(File::lastModified)

        if (latestImage == null) {
            binding.photoPreview.setImageDrawable(null)
            binding.photoPreviewLabel.text = getString(R.string.photo_preview_waiting)
            return
        }

        showPhotoPreview(latestImage)
    }

    private fun showPhotoPreview(photoFile: File) {
        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
        if (bitmap == null) {
            binding.photoPreview.setImageDrawable(null)
            binding.photoPreviewLabel.text = getString(R.string.photo_preview_missing)
            return
        }

        latestPhotoFile = photoFile
        binding.photoPreview.setImageBitmap(bitmap)
        binding.photoPreviewLabel.text = getString(
            R.string.photo_preview_saved,
            buildPhotoLabel(photoFile)
        )
    }

    private fun buildPhotoLabel(photoFile: File): String {
        val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(photoFile.lastModified()))
        return "${photoFile.name} ($formattedTime)"
    }

    private fun enqueueSnapshotIfNeeded(snapshot: LifelogSnapshot, photoFile: File) {
        exportSnapshotToPublicStorage(snapshot, photoFile)
        if (!snapshot.eventEstimate.worthUploading) {
            return
        }

        snapshotQueueStore.save(snapshot)
        snapshotQueueStore.deleteExpired(SNAPSHOT_RETENTION_MS)
    }

    private fun exportSnapshotToPublicStorage(snapshot: LifelogSnapshot, photoFile: File) {
        thread {
            runCatching {
                lscDatasetExporter.export(snapshot, photoFile)
            }.onSuccess { result ->
                lastExportSummary = getString(
                    R.string.export_status_saved,
                    result.metadataRelativePath,
                    result.imageRelativePath,
                    result.snapshotMetadataRelativePath
                )
            }.onFailure { error ->
                lastExportSummary = getString(
                    R.string.export_status_failed,
                    error.message ?: error.javaClass.simpleName
                )
            }
            runOnUiThread {
                updateExportStatus()
                drainSnapshotQueue()
            }
        }
    }

    private fun updateExportStatus() {
        val locations = lscDatasetExporter.exportLocations()
        val summary = lastExportSummary ?: getString(
            R.string.export_status_waiting,
            locations.datasetDirectory,
            locations.metadataFile,
            locations.snapshotMetadataFile
        )
        binding.exportStatusText.text = summary
    }

    private fun drainSnapshotQueue() {
        snapshotQueueStore.deleteExpired(SNAPSHOT_RETENTION_MS)
        if (!uploadClient.isConfigured()) {
            mainHandler.post {
                binding.uploadStatusText.text = getString(R.string.upload_status_not_configured)
            }
            return
        }

        thread {
            val snapshots = snapshotQueueStore.listSnapshots()
            if (snapshots.isEmpty()) {
                mainHandler.post {
                    binding.uploadStatusText.text = getString(R.string.upload_status_queue_empty)
                }
                return@thread
            }

            snapshots.forEach { snapshot ->
                val imageFile = File(snapshotQueueStore.imagesDir(), snapshot.imageFileName)
                val result = uploadClient.upload(snapshot, imageFile)
                mainHandler.post {
                    binding.uploadStatusText.text = getString(
                        if (result.success) {
                            R.string.upload_status_success
                        } else {
                            R.string.upload_status_failed
                        },
                        snapshot.imageFileName,
                        result.detail
                    )
                }
                if (result.success) {
                    snapshotQueueStore.delete(snapshot.id, snapshot.imageFileName)
                }
            }
        }
    }

    private fun formatDouble(value: Double, digits: Int): String {
        return String.format(Locale.US, "%.${digits}f", value)
    }
}
