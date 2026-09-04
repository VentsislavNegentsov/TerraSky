package com.terrasky

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        MapLibre.getInstance(applicationContext)
        setContent {
            TerraSkyApp()
        }
    }
}

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
    },
    "openmaptiles": {
      "type": "vector",
      "tiles": [
        "https://demotiles.maplibre.org/tiles/tiles.json"
      ]
    }
  },
  "layers": [
    {
      "id": "background-sky",
      "type": "background",
      "paint": {
        "background-color": "#0b101d"
      }
    },
    {
      "id": "satellite-layer",
      "type": "raster",
      "source": "esri-satellite"
    },
    {
      "id": "3d-buildings",
      "type": "fill-extrusion",
      "source": "openmaptiles",
      "source-layer": "building",
      "minzoom": 12,
      "paint": {
        "fill-extrusion-color": "#222222",
        "fill-extrusion-height": ["get", "render_height"],
        "fill-extrusion-base": ["get", "render_min_height"],
        "fill-extrusion-opacity": 0.85
      }
    }
  ]
}
"""

const val METERS_PER_FOOT = 0.3048
const val STARTING_ALTITUDE_FEET = 1000.0

data class FlightState(
    val latitude: Double = 42.6977,
    val longitude: Double = 23.3219,
    val altitudeMeters: Double = STARTING_ALTITUDE_FEET * METERS_PER_FOOT,
    val elevatorAlpha: Float = -2.0f,
    val pitch: Float = 2.0f,
    val roll: Float = 0f,
    val heading: Float = 225f,
    val speedKnots: Double = 280.0,
    val verticalSpeedMps: Double = 0.0,
    val alphaDeg: Double = 2.0,
    val flightPathAngleDeg: Double = 0.0
)

@Composable
fun TerraSkyApp() {
    val context = LocalContext.current
    var flightState by remember { mutableStateOf(FlightState()) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var gpsLocked by remember { mutableStateOf(false) }

    var cameraYawOffset by remember { mutableFloatStateOf(0f) }
    var cameraPitchOffset by remember { mutableFloatStateOf(0f) }

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

    // AERODYNAMIC FLIGHT ENGINE
    LaunchedEffect(Unit) {
        val g = 9.81
        var lastTimeNanos = System.nanoTime()

        while (true) {
            delay(16)

            val currentTimeNanos = System.nanoTime()
            val dt = ((currentTimeNanos - lastTimeNanos) / 1_000_000_000.0).coerceIn(0.005, 0.05)
            lastTimeNanos = currentTimeNanos

            val rollRad = Math.toRadians(flightState.roll.toDouble())
            val currentSpeedMps = max(20.0, flightState.speedKnots / 1.94384)

            // 1. INVERTED ELEVATOR CONTROL
            val targetAlpha = -flightState.elevatorAlpha.toDouble()
            val alpha = flightState.alphaDeg + (targetAlpha - flightState.alphaDeg) * (dt * 6.0).coerceAtMost(1.0)

            // 2. LIFT COEFFICIENT GENERATION
            val cl = if (abs(alpha) < 16.0) {
                0.2 + alpha * 0.085
            } else {
                (0.2 + 16.0 * 0.085) - (abs(alpha) - 16.0) * 0.12
            }

            val designSpeedMps = 144.0
            val dynamicPressureRatio = (currentSpeedMps / designSpeedMps).pow(2)
            val totalLiftAccel = g * (cl / 0.37) * dynamicPressureRatio
            val verticalLiftAccel = totalLiftAccel * cos(rollRad)

            // 3. VERTICAL VELOCITY & ALTITUDE
            val netVerticalAccel = verticalLiftAccel - g
            val newVerticalSpeedMps = (flightState.verticalSpeedMps + netVerticalAccel * dt).coerceIn(-100.0, 100.0)
            val newAltitudeMeters = (flightState.altitudeMeters + (newVerticalSpeedMps * dt)).coerceAtLeast(10.0)

            // 4. FLIGHT PATH ANGLE & NOSE PITCH
            val sinGamma = (newVerticalSpeedMps / currentSpeedMps).coerceIn(-0.95, 0.95)
            val gammaRad = asin(sinGamma)
            val gammaDeg = Math.toDegrees(gammaRad)
            val computedNosePitch = (gammaDeg + alpha).toFloat()

            // 5. THRUST & DRAG
            val parasiticDragCoeff = 0.00028
            val inducedDragCoeff = 0.00018 * (alpha * alpha / 10.0)
            val totalDragForce = (parasiticDragCoeff + inducedDragCoeff) * currentSpeedMps * currentSpeedMps
            val cruiseThrust = parasiticDragCoeff * (144.0 * 144.0)
            val gravityComponentAccel = -g * sinGamma
            val netForwardAccel = cruiseThrust - totalDragForce + gravityComponentAccel

            val updatedSpeedMps = (currentSpeedMps + netForwardAccel * dt).coerceIn(25.0, 450.0)
            val updatedKnots = updatedSpeedMps * 1.94384

            // 6. BANK TURN DYNAMICS
            val groundSpeedMps = max(10.0, updatedSpeedMps * cos(gammaRad))
            val turnRateRad = (g * tan(rollRad)) / groundSpeedMps
            val deltaHeadingDeg = Math.toDegrees(turnRateRad * dt)
            val newHeading = (flightState.heading + deltaHeadingDeg + 360) % 360

            // 7. GEOGRAPHIC DISPLACEMENT
            val distanceKm = (groundSpeedMps * dt) / 1000.0
            val headingRad = Math.toRadians(newHeading.toDouble())

            val deltaLat = (distanceKm / 6371.0) * (180.0 / Math.PI)
            val deltaLon = (distanceKm / (6371.0 * cos(Math.toRadians(flightState.latitude)))) * (180.0 / Math.PI) * sin(headingRad)

            val newLat = flightState.latitude + (deltaLat * cos(headingRad))
            val newLon = flightState.longitude + deltaLon

            flightState = flightState.copy(
                latitude = newLat,
                longitude = newLon,
                altitudeMeters = newAltitudeMeters,
                pitch = computedNosePitch,
                speedKnots = updatedKnots,
                verticalSpeedMps = newVerticalSpeedMps,
                alphaDeg = alpha,
                flightPathAngleDeg = gammaDeg,
                heading = newHeading.toFloat()
            )

            // 8. CAMERA & WIDE HORIZON DISTANCE ENGINE
            mapLibreMap?.let { map ->
                try {
                    val viewBearing = (newHeading + cameraYawOffset + 360f) % 360f
                    val viewBearingRad = Math.toRadians(viewBearing.toDouble())

                    val totalViewPitch = computedNosePitch + cameraPitchOffset
                    val mapTilt = (85.0 + totalViewPitch.toDouble()).coerceIn(0.0, 85.0)

                    val baseAltitudeZoom = 17.0 - log2(newAltitudeMeters / 15.0)
                    val tiltZoomAdjustment = (mapTilt / 85.0).pow(1.8) * 3.2
                    val dynamicZoom = (baseAltitudeZoom - tiltZoomAdjustment).coerceIn(9.5, 16.5)

                    val targetDistanceKm = 0.15 + (mapTilt / 85.0) * 8.0
                    val targetLat = newLat + ((targetDistanceKm / 6371.0) * (180.0 / Math.PI) * cos(viewBearingRad))
                    val targetLon = newLon + ((targetDistanceKm / (6371.0 * cos(Math.toRadians(newLat)))) * (180.0 / Math.PI) * sin(viewBearingRad))

                    val cameraPosition = CameraPosition.Builder()
                        .target(LatLng(targetLat, targetLon))
                        .bearing(viewBearing.toDouble())
                        .tilt(mapTilt)
                        .zoom(dynamicZoom)
                        .build()

                    map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    cameraYawOffset = (cameraYawOffset + dragAmount.x * 0.35f) % 360f
                    cameraPitchOffset = (cameraPitchOffset - dragAmount.y * 0.35f).coerceIn(-85f, 15f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        flightState = flightState.copy(elevatorAlpha = -2.0f, roll = 0f)
                        cameraYawOffset = 0f
                        cameraPitchOffset = 0f
                    }
                )
            }
    ) {
        MapLibreViewContainer(
            onMapReady = { map ->
                map.setMaxPitchPreference(85.0)
                map.setPrefetchZoomDelta(4)
                mapLibreMap = map
            },
            modifier = Modifier.fillMaxSize()
        )

        GoogleEarthProHUD(
            flightState = flightState,
            modifier = Modifier.fillMaxSize()
        )

        // ROLL CONTROL
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 20.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "ROLL: ${flightState.roll.roundToInt()}°",
                color = Color(0xFF00FF00),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = flightState.roll,
                onValueChange = { flightState = flightState.copy(roll = it) },
                valueRange = -60f..60f,
                modifier = Modifier.width(180.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF00FF00),
                    activeTrackColor = Color(0xFF00FF00),
                    inactiveTrackColor = Color(0xFF005500)
                )
            )
        }

        // ELEVATOR CONTROL
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .padding(horizontal = 6.dp, vertical = 10.dp)
        ) {
            Text(
                text = "ELEVATOR\nAoA ${flightState.alphaDeg.roundToInt()}°",
                color = Color(0xFF00FF00),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 2.dp)
            )
            Slider(
                value = flightState.elevatorAlpha,
                onValueChange = { flightState = flightState.copy(elevatorAlpha = it) },
                valueRange = -22f..10f,
                modifier = Modifier
                    .width(140.dp)
                    .graphicsLayer { rotationZ = -90f },
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF00FF00),
                    activeTrackColor = Color(0xFF00FF00),
                    inactiveTrackColor = Color(0xFF005500)
                )
            )
        }

        Text(
            text = "Drag screen to look around • Double-tap to reset view",
            color = Color(0xFF00FF00).copy(alpha = 0.65f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
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
fun GoogleEarthProHUD(
    flightState: FlightState,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val hudGreen = Color(0xFF00FF00)
    val strokeWidth = 2.2f
    val stroke = Stroke(width = strokeWidth)
    val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)

    val altitudeFeet = (flightState.altitudeMeters / METERS_PER_FOOT).roundToInt()
    val speedKnots = flightState.speedKnots.roundToInt()
    val headingDeg = flightState.heading.roundToInt()
    val vsFpm = ((flightState.verticalSpeedMps / METERS_PER_FOOT) * 60.0).roundToInt()

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        // 1. TOP HEADING TAPE
        val headingY = 110f
        val headingWidth = 380f
        drawLine(hudGreen, Offset(cx - headingWidth / 2, headingY), Offset(cx + headingWidth / 2, headingY), strokeWidth = strokeWidth)

        val topPointerPath = Path().apply {
            moveTo(cx, headingY)
            lineTo(cx - 6f, headingY - 10f)
            lineTo(cx + 6f, headingY - 10f)
            close()
        }
        drawPath(topPointerPath, hudGreen)

        for (h in (headingDeg - 25)..(headingDeg + 25)) {
            val normH = (h + 360) % 360
            val x = cx + ((h - headingDeg) * 11f)
            if (x in (cx - headingWidth / 2)..(cx + headingWidth / 2)) {
                if (normH % 5 == 0) {
                    val isMajor = normH % 10 == 0
                    val tickLen = if (isMajor) 14f else 7f
                    drawLine(hudGreen, Offset(x, headingY), Offset(x, headingY + tickLen), strokeWidth = strokeWidth)

                    if (isMajor) {
                        val label = when (normH) {
                            0 -> "N"
                            90 -> "E"
                            180 -> "S"
                            270 -> "W"
                            else -> "${normH / 10}"
                        }
                        drawText(
                            textMeasurer = textMeasurer,
                            text = label,
                            style = TextStyle(color = hudGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                            topLeft = Offset(x - 6f, headingY + 16f)
                        )
                    }
                }
            }
        }

        // 2. BANK ROLL ARC
        val arcRadius = 180f
        val arcCenterY = cy - 40f
        drawArc(
            color = hudGreen,
            startAngle = 210f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(cx - arcRadius, arcCenterY - arcRadius),
            size = Size(arcRadius * 2, arcRadius * 2),
            style = stroke
        )

        for (angle in listOf(-45, -30, -20, -10, 0, 10, 20, 30, 45)) {
            val rad = Math.toRadians((angle - 90).toDouble())
            val innerX = cx + arcRadius * cos(rad).toFloat()
            val innerY = arcCenterY + arcRadius * sin(rad).toFloat()
            val tickLen = if (angle == 0 || abs(angle) == 30 || abs(angle) == 45) 14f else 8f
            val outerX = cx + (arcRadius + tickLen) * cos(rad).toFloat()
            val outerY = arcCenterY + (arcRadius + tickLen) * sin(rad).toFloat()
            drawLine(hudGreen, Offset(innerX, innerY), Offset(outerX, outerY), strokeWidth = strokeWidth)
        }

        // 3. CENTER BORESIGHT & PITCH LADDER
        rotate(degrees = -flightState.roll, pivot = Offset(cx, cy)) {
            val pitchOffset = flightState.pitch * 11.5f

            val chevronPath = Path().apply {
                moveTo(cx - 16f, cy - 8f + pitchOffset)
                lineTo(cx - 8f, cy + pitchOffset)
                lineTo(cx, cy - 6f + pitchOffset)
                lineTo(cx + 8f, cy + pitchOffset)
                lineTo(cx + 16f, cy - 8f + pitchOffset)
            }
            drawPath(chevronPath, hudGreen, style = stroke)

            val velocityVectorOffsetY = pitchOffset - (flightState.alphaDeg.toFloat() * 11.5f)
            drawCircle(hudGreen, radius = 8f, center = Offset(cx, cy + velocityVectorOffsetY), style = stroke)
            drawLine(hudGreen, Offset(cx - 22f, cy + velocityVectorOffsetY), Offset(cx - 8f, cy + velocityVectorOffsetY), strokeWidth = strokeWidth)
            drawLine(hudGreen, Offset(cx + 8f, cy + velocityVectorOffsetY), Offset(cx + 22f, cy + velocityVectorOffsetY), strokeWidth = strokeWidth)
            drawLine(hudGreen, Offset(cx, cy - 8f + velocityVectorOffsetY), Offset(cx, cy - 18f + velocityVectorOffsetY), strokeWidth = strokeWidth)

            for (p in -60..60 step 5) {
                if (p == 0) {
                    drawLine(hudGreen, Offset(cx - 140f, cy + pitchOffset), Offset(cx - 40f, cy + pitchOffset), strokeWidth = strokeWidth)
                    drawLine(hudGreen, Offset(cx + 40f, cy + pitchOffset), Offset(cx + 140f, cy + pitchOffset), strokeWidth = strokeWidth)
                    continue
                }

                val lineY = cy - (p * 11.5f) + pitchOffset
                if (lineY in (cy - 220f)..(cy + 220f)) {
                    val barLen = 50f
                    val isNegative = p < 0

                    if (isNegative) {
                        val leftPath = Path().apply {
                            moveTo(cx - barLen - 20f, lineY)
                            lineTo(cx - 20f, lineY)
                        }
                        val rightPath = Path().apply {
                            moveTo(cx + 20f, lineY)
                            lineTo(cx + barLen + 20f, lineY)
                        }
                        drawPath(leftPath, hudGreen, style = Stroke(width = strokeWidth, pathEffect = dashedEffect))
                        drawPath(rightPath, hudGreen, style = Stroke(width = strokeWidth, pathEffect = dashedEffect))

                        drawLine(hudGreen, Offset(cx - barLen - 20f, lineY), Offset(cx - barLen - 20f, lineY - 8f), strokeWidth = strokeWidth)
                        drawLine(hudGreen, Offset(cx + barLen + 20f, lineY), Offset(cx + barLen + 20f, lineY - 8f), strokeWidth = strokeWidth)
                    } else {
                        drawLine(hudGreen, Offset(cx - barLen - 20f, lineY), Offset(cx - 20f, lineY), strokeWidth = strokeWidth)
                        drawLine(hudGreen, Offset(cx + 20f, lineY), Offset(cx + barLen + 20f, lineY), strokeWidth = strokeWidth)

                        drawLine(hudGreen, Offset(cx - barLen - 20f, lineY), Offset(cx - barLen - 20f, lineY + 8f), strokeWidth = strokeWidth)
                        drawLine(hudGreen, Offset(cx + barLen + 20f, lineY), Offset(cx + barLen + 20f, lineY + 8f), strokeWidth = strokeWidth)
                    }

                    drawText(
                        textMeasurer = textMeasurer,
                        text = "${abs(p)}",
                        style = TextStyle(color = hudGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                        topLeft = Offset(cx - barLen - 45f, lineY - 8f)
                    )
                    drawText(
                        textMeasurer = textMeasurer,
                        text = "${abs(p)}",
                        style = TextStyle(color = hudGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                        topLeft = Offset(cx + barLen + 25f, lineY - 8f)
                    )
                }
            }
        }

        // 4. AIRSPEED TAPE
        val speedX = cx - 280f
        val tapeHeight = 320f
        drawLine(hudGreen, Offset(speedX, cy - tapeHeight / 2), Offset(speedX, cy + tapeHeight / 2), strokeWidth = strokeWidth)
        drawLine(hudGreen, Offset(speedX, cy), Offset(speedX + 12f, cy), strokeWidth = 3f)

        for (s in (speedKnots - 60)..(speedKnots + 60) step 10) {
            if (s < 0) continue
            val y = cy - ((s - speedKnots) * 3.5f)
            if (y in (cy - tapeHeight / 2)..(cy + tapeHeight / 2)) {
                drawLine(hudGreen, Offset(speedX, y), Offset(speedX + 10f, y), strokeWidth = strokeWidth)
                drawText(
                    textMeasurer = textMeasurer,
                    text = "$s",
                    style = TextStyle(color = hudGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                    topLeft = Offset(speedX - 42f, y - 9f)
                )
            }
        }

        // 5. ALTITUDE TAPE & VSI
        val altX = cx + 280f
        drawLine(hudGreen, Offset(altX, cy - tapeHeight / 2), Offset(altX, cy + tapeHeight / 2), strokeWidth = strokeWidth)

        drawText(
            textMeasurer = textMeasurer,
            text = "$vsFpm",
            style = TextStyle(color = hudGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            topLeft = Offset(altX - 12f, cy - tapeHeight / 2 - 28f)
        )

        drawLine(hudGreen, Offset(altX - 12f, cy), Offset(altX, cy), strokeWidth = 3f)

        for (a in (altitudeFeet - 600)..(altitudeFeet + 600) step 100) {
            if (a < 0) continue
            val y = cy - ((a - altitudeFeet) * 0.32f)
            if (y in (cy - tapeHeight / 2)..(cy + tapeHeight / 2)) {
                drawLine(hudGreen, Offset(altX - 10f, y), Offset(altX, y), strokeWidth = strokeWidth)
                drawText(
                    textMeasurer = textMeasurer,
                    text = "$a",
                    style = TextStyle(color = hudGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                    topLeft = Offset(altX + 16f, y - 9f)
                )
            }
        }

        // 6. BOTTOM STATUS
        drawText(
            textMeasurer = textMeasurer,
            text = "AOA ${flightState.alphaDeg.roundToInt()}°",
            style = TextStyle(color = hudGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            topLeft = Offset(speedX - 42f, cy + tapeHeight / 2 + 25f)
        )
        drawText(
            textMeasurer = textMeasurer,
            text = "GEAR UP",
            style = TextStyle(color = hudGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            topLeft = Offset(speedX - 42f, cy + tapeHeight / 2 + 50f)
        )
    }
}