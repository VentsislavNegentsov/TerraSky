package com.terrasky

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(applicationContext)
        setContent {
            TerraSkyApp()
        }
    }
}

// 100% Free High-Res Global Satellite Style JSON
private const val SATELLITE_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "esri-satellite": {
      "type": "raster",
      "tiles": [
        "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
      ],
      "tileSize": 256,
      "maxzoom": 19
    }
  },
  "layers": [
    {
      "id": "satellite-layer",
      "type": "raster",
      "source": "esri-satellite"
    }
  ]
}
"""

const val DEFAULT_ALTITUDE_FEET = 3000.0
const val METERS_PER_FOOT = 0.3048

data class FlightState(
    val latitude: Double = 37.7749,
    val longitude: Double = -122.4194,
    val altitudeMeters: Double = DEFAULT_ALTITUDE_FEET * METERS_PER_FOOT,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val heading: Float = 0f,
    val speedMps: Double = 90.0
)

@Composable
fun TerraSkyApp() {
    val context = LocalContext.current
    var flightState by remember { mutableStateOf(FlightState()) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var gpsLocked by remember { mutableStateOf(false) }

    // 1. Location Request
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchInitialGpsLocation(context) { location ->
                if (!gpsLocked) {
                    flightState = flightState.copy(
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                    gpsLocked = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted) {
            fetchInitialGpsLocation(context) { location ->
                if (!gpsLocked) {
                    flightState = flightState.copy(
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                    gpsLocked = true
                }
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // 2. Sensors (Yoke)
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientationAngles = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)

                    val rawPitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                    val rawRoll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

                    flightState = flightState.copy(
                        pitch = rawPitch.coerceIn(-30f, 30f),
                        roll = rawRoll.coerceIn(-60f, 60f)
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        rotationSensor?.let { sensor ->
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    // 3. Flight Camera Loop (~30 FPS Horizon View)
    LaunchedEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect

        while (true) {
            delay(33)

            val deltaHeading = flightState.roll * 0.04f
            val newHeading = (flightState.heading + deltaHeading + 360) % 360

            val deltaAltitude = flightState.speedMps * sin(Math.toRadians(flightState.pitch.toDouble())) * 0.033
            val newAltitude = (flightState.altitudeMeters + deltaAltitude).coerceAtLeast(30.0)

            val distanceKm = (flightState.speedMps * 0.033) / 1000.0
            val headingRad = Math.toRadians(newHeading.toDouble())

            val deltaLat = (distanceKm / 6371.0) * (180.0 / Math.PI)
            val deltaLon = (distanceKm / (6371.0 * cos(Math.toRadians(flightState.latitude)))) * (180.0 / Math.PI) * sin(headingRad)

            val newLat = flightState.latitude + (deltaLat * cos(headingRad))
            val newLon = flightState.longitude + deltaLon

            flightState = flightState.copy(
                latitude = newLat,
                longitude = newLon,
                altitudeMeters = newAltitude,
                heading = newHeading
            )

            // Dynamic camera positioning for Flight Simulator perspective
            try {
                val calculatedZoom = (17.5 - log2(newAltitude / 10.0)).coerceIn(12.0, 16.5)
                val tiltAngle = (75.0 - flightState.pitch).coerceIn(45.0, 80.0)

                val cameraPosition = CameraPosition.Builder()
                    .target(LatLng(newLat, newLon))
                    .bearing(newHeading.toDouble())
                    .tilt(tiltAngle)
                    .zoom(calculatedZoom)
                    .build()

                map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 4. Main HUD Screen Container
    Box(modifier = Modifier.fillMaxSize()) {
        MapLibreViewContainer(
            onMapReady = { map ->
                mapLibreMap = map
            },
            modifier = Modifier.fillMaxSize()
        )

        // Google Earth Green Vector Flight HUD
        GoogleEarthHUD(
            flightState = flightState,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun fetchInitialGpsLocation(context: Context, onLocationFound: (Location) -> Unit) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    try {
        val lastKnownGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val lastKnownNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        val bestLocation = lastKnownGps ?: lastKnownNetwork
        if (bestLocation != null) {
            onLocationFound(bestLocation)
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onLocationFound(location)
                    locationManager.removeUpdates(this)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null)
        }
    } catch (e: SecurityException) {
        e.printStackTrace()
    }
}

@Composable
fun MapLibreViewContainer(
    onMapReady: (MapLibreMap) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    map.setStyle(Style.Builder().fromJson(SATELLITE_STYLE_JSON)) {
                        map.uiSettings.isAttributionEnabled = false
                        map.uiSettings.isLogoEnabled = false
                        map.uiSettings.isCompassEnabled = false
                        onMapReady(map)
                    }
                }
            }
        },
        modifier = modifier
    )
}

@Composable
fun GoogleEarthHUD(
    flightState: FlightState,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val hudColor = Color(0xFF00FF00) // Neon Green
    val strokeStyle = Stroke(width = 3f)

    val altitudeFeet = (flightState.altitudeMeters / METERS_PER_FOOT).toInt()
    val speedKnots = (flightState.speedMps * 1.94384).toInt()
    val headingDeg = flightState.heading.toInt()

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        // A. Flight Path Boresight (Center Aircraft Symbol)
        drawCircle(color = hudColor, radius = 12f, center = Offset(cx, cy), style = strokeStyle)
        drawLine(hudColor, Offset(cx - 30f, cy), Offset(cx - 12f, cy), strokeWidth = 3f)
        drawLine(hudColor, Offset(cx + 12f, cy), Offset(cx + 30f, cy), strokeWidth = 3f)
        drawLine(hudColor, Offset(cx, cy - 30f), Offset(cx, cy - 12f), strokeWidth = 3f)

        // B. Pitch Ladder & Bank Arc (Rotates with Roll)
        rotate(degrees = -flightState.roll, pivot = Offset(cx, cy)) {
            val pitchOffset = flightState.pitch * 12f

            // Pitch Lines (-20 to +20 degrees)
            for (p in -20..20 step 10) {
                if (p == 0) continue
                val lineY = cy - (p * 12f) + pitchOffset
                if (lineY in (cy - 200f)..(cy + 200f)) {
                    drawLine(hudColor, Offset(cx - 80f, lineY), Offset(cx - 30f, lineY), strokeWidth = 3f)
                    drawLine(hudColor, Offset(cx + 30f, lineY), Offset(cx + 80f, lineY), strokeWidth = 3f)
                    drawText(
                        textMeasurer = textMeasurer,
                        text = "$p",
                        style = TextStyle(color = hudColor, fontSize = 12.sp),
                        topLeft = Offset(cx + 90f, lineY - 18f)
                    )
                }
            }

            // Roll / Pitch Arc
            drawArc(
                color = hudColor,
                startAngle = 220f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(cx - 180f, cy - 180f + pitchOffset),
                size = androidx.compose.ui.geometry.Size(360f, 360f),
                style = strokeStyle
            )
        }

        // C. Heading Tape (Top Center)
        val headingY = 80f
        drawLine(hudColor, Offset(cx - 200f, headingY), Offset(cx + 200f, headingY), strokeWidth = 3f)
        for (h in (headingDeg - 30)..(headingDeg + 30) step 5) {
            val normH = (h + 360) % 360
            val x = cx + ((h - headingDeg) * 8f)
            val tickHeight = if (normH % 10 == 0) 20f else 10f
            drawLine(hudColor, Offset(x, headingY), Offset(x, headingY + tickHeight), strokeWidth = 2f)

            if (normH % 10 == 0) {
                val label = when (normH) {
                    0 -> "N"
                    90 -> "E"
                    180 -> "S"
                    270 -> "W"
                    else -> "$normH"
                }
                drawText(
                    textMeasurer = textMeasurer,
                    text = label,
                    style = TextStyle(color = hudColor, fontSize = 12.sp),
                    topLeft = Offset(x - 10f, headingY - 30f)
                )
            }
        }

        // D. Airspeed Tape (Left Side)
        val speedX = 120f
        drawLine(hudColor, Offset(speedX, cy - 200f), Offset(speedX, cy + 200f), strokeWidth = 3f)
        drawText(
            textMeasurer = textMeasurer,
            text = "$speedKnots KTS",
            style = TextStyle(color = hudColor, fontSize = 16.sp),
            topLeft = Offset(speedX - 90f, cy - 15f)
        )
        for (s in (speedKnots - 40)..(speedKnots + 40) step 10) {
            if (s < 0) continue
            val y = cy - ((s - speedKnots) * 4f)
            drawLine(hudColor, Offset(speedX - 15f, y), Offset(speedX, y), strokeWidth = 2f)
            drawText(
                textMeasurer = textMeasurer,
                text = "$s",
                style = TextStyle(color = hudColor, fontSize = 11.sp),
                topLeft = Offset(speedX - 50f, y - 12f)
            )
        }

        // E. Altitude Tape (Right Side)
        val altX = size.width - 120f
        drawLine(hudColor, Offset(altX, cy - 200f), Offset(altX, cy + 200f), strokeWidth = 3f)
        drawText(
            textMeasurer = textMeasurer,
            text = "$altitudeFeet FT",
            style = TextStyle(color = hudColor, fontSize = 16.sp),
            topLeft = Offset(altX + 25f, cy - 15f)
        )
        for (a in (altitudeFeet - 400)..(altitudeFeet + 400) step 100) {
            if (a < 0) continue
            val y = cy - ((a - altitudeFeet) * 0.4f)
            drawLine(hudColor, Offset(altX, y), Offset(altX + 15f, y), strokeWidth = 2f)
            drawText(
                textMeasurer = textMeasurer,
                text = "$a",
                style = TextStyle(color = hudColor, fontSize = 11.sp),
                topLeft = Offset(altX + 20f, y - 12f)
            )
        }
    }
}