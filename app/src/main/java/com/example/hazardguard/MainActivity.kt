package com.example.hazardguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.hazardguard.ui.theme.HazardGuardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

private const val DEFAULT_CLOUD_URL = "https://ewlsx-160-187-77-3.free.pinggy.net/webhook/sensor"
private const val LOCAL_URL = "http://localhost:5678/webhook/sensor"
private const val ROOM = "nursery"
private const val CHILD_AGE = 1
private const val SEND_EVERY_MS = 60000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HazardGuardTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SensorScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

data class Assessment(
    val level: String,
    val prediction: String,
    val action: String,
    val alarmSound: Boolean = false,
    val emergencyCallRecommended: Boolean = false,
    val alertTitle: String = "",
    val emergencySmsContent: String = ""
)

suspend fun postReading(customUrl: String, body: JSONObject): Assessment = withContext(Dispatchers.IO) {
    val urls = linkedSetOf<String>().apply {
        add(LOCAL_URL)
        val validCustom = customUrl.trim()
        if (validCustom.isNotBlank() && !validCustom.contains("trycloudflare") && !validCustom.contains("ftkkl") && !validCustom.contains("dddci") && !validCustom.contains("hbrul")) {
            add(validCustom)
        }
        add(DEFAULT_CLOUD_URL)
    }
    var lastException: Exception? = null
    for (urlString in urls) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 45000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("HTTP $code: ${text.take(200)}")
            val j = JSONObject(text)
            return@withContext Assessment(
                level = j.optString("risk_level", "?"),
                prediction = j.optString("prediction", ""),
                action = j.optString("action", ""),
                alarmSound = j.optBoolean("alarm_sound", false),
                emergencyCallRecommended = j.optBoolean("emergency_call_recommended", false),
                alertTitle = j.optString("alert_title", ""),
                emergencySmsContent = j.optString("emergency_sms_content", "")
            )
        } catch (e: Exception) {
            lastException = e
        } finally {
            conn?.disconnect()
        }
    }
    throw lastException ?: Exception("Connection failed")
}

@SuppressLint("MissingPermission")
suspend fun measureNoise(onLevel: (Float) -> Unit) = withContext(Dispatchers.IO) {
    val rate = 16000
    val minBuf = AudioRecord.getMinBufferSize(
        rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )
    val rec = AudioRecord(
        MediaRecorder.AudioSource.MIC, rate,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
    )
    val buf = ShortArray(rate / 2)
    rec.startRecording()
    try {
        while (isActive) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) {
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                val rms = sqrt(sum / n)
                onLevel((20 * log10(max(rms, 1.0))).toFloat())
            }
        }
    } finally {
        rec.stop()
        rec.release()
    }
}

@Composable
fun CameraObservationPreview(
    modifier: Modifier = Modifier,
    isCameraEnabled: Boolean = true,
    isBlocked: Boolean = false,
    onObstructionChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasCameraPermission = it }

    LaunchedEffect(isCameraEnabled) {
        if (isCameraEnabled && !hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (isCameraEnabled && hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                var cameraDevice: CameraDevice? = null
                                var captureSession: CameraCaptureSession? = null
                                var lastCheckTime = 0L

                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                                    val cameraId = manager.cameraIdList.firstOrNull { id ->
                                        manager.getCameraCharacteristics(id)
                                            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                                    } ?: manager.cameraIdList.firstOrNull() ?: return

                                    try {
                                        @SuppressLint("MissingPermission")
                                        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                                            override fun onOpened(camera: CameraDevice) {
                                                cameraDevice = camera
                                                val surface = Surface(st)
                                                val reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                    addTarget(surface)
                                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                }
                                                camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                                                    override fun onConfigured(session: CameraCaptureSession) {
                                                        captureSession = session
                                                        try {
                                                            session.setRepeatingRequest(reqBuilder.build(), null, null)
                                                        } catch (_: Exception) {}
                                                    }
                                                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                                                }, null)
                                            }
                                            override fun onDisconnected(camera: CameraDevice) {
                                                camera.close()
                                                cameraDevice = null
                                            }
                                            override fun onError(camera: CameraDevice, error: Int) {
                                                camera.close()
                                                cameraDevice = null
                                            }
                                        }, null)
                                    } catch (_: SecurityException) {}
                                }

                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    captureSession?.close()
                                    cameraDevice?.close()
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastCheckTime > 1000) {
                                        lastCheckTime = now
                                        val bmp = getBitmap(16, 16)
                                        if (bmp != null) {
                                            var sum = 0L
                                            for (x in 0 until 16) {
                                                for (y in 0 until 16) {
                                                    val p = bmp.getPixel(x, y)
                                                    val r = (p shr 16) and 0xff
                                                    val g = (p shr 8) and 0xff
                                                    val b = p and 0xff
                                                    sum += (r * 299 + g * 587 + b * 114) / 1000
                                                }
                                            }
                                            val luma = sum / 256
                                            onObstructionChanged(luma < 15)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (!isCameraEnabled) "Camera Observation Paused" else "Camera Permission Required",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (isBlocked && isCameraEnabled && hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xB3B71C1C)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⚠️ CAMERA BLOCKED / OBSTRUCTED", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("Child visibility lost — clear lens obstruction", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (isBlocked) Color(0xFFFF5252)
                            else if (isCameraEnabled && hasCameraPermission) Color(0xFF00E676)
                            else Color.Gray,
                            RoundedCornerShape(4.dp)
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (isBlocked) "⚠️ LENS OBSTRUCTED"
                    else if (isCameraEnabled && hasCameraPermission) "LIVE CHILD OBSERVATION"
                    else "CAMERA STANDBY",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun QuickSensorChip(icon: String, value: String, label: String, isWarning: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isWarning) Color(0xFFFFCDD2) else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "$icon $value",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isWarning) Color(0xFFB71C1C) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

@Composable
fun SensorDetailCard(
    title: String,
    value: String,
    subtext: String,
    icon: String,
    isWarning: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isWarning) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(end = 12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtext, style = MaterialTheme.typography.bodySmall, color = if (isWarning) Color(0xFFC62828) else Color.Gray)
                }
            }
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (isWarning) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun Reading(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SensorScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sm = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val prefs = remember { context.getSharedPreferences("hazardguard_prefs", Context.MODE_PRIVATE) }

    var endpointUrl by remember {
        val saved = prefs.getString("webhook_url", null)
        mutableStateOf(if (saved.isNullOrBlank() || saved.contains("trycloudflare") || saved.contains("ftkkl") || saved.contains("dddci") || saved.contains("hbrul")) DEFAULT_CLOUD_URL else saved)
    }
    var emergencyContactNumber by remember {
        mutableStateOf(prefs.getString("emergency_contact_number", "") ?: "")
    }
    var emergencyContactName by remember {
        mutableStateOf(prefs.getString("emergency_contact_name", "Family / Caregiver") ?: "Family / Caregiver")
    }
    var isAlarmSilenced by remember { mutableStateOf(false) }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val contactUri = result.data?.data ?: return@rememberLauncherForActivityResult
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            try {
                context.contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val number = if (numIdx != -1) cursor.getString(numIdx) ?: "" else ""
                        val name = if (nameIdx != -1) cursor.getString(nameIdx) ?: "" else ""
                        if (number.isNotBlank()) {
                            emergencyContactNumber = number
                            emergencyContactName = if (name.isNotBlank()) name else "Contact"
                            prefs.edit()
                                .putString("emergency_contact_number", emergencyContactNumber)
                                .putString("emergency_contact_name", emergencyContactName)
                                .apply()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    val ringtone = remember {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        RingtoneManager.getRingtone(context, alarmUri)
    }

    var lux by remember { mutableStateOf<Float?>(null) }
    var accel by remember { mutableStateOf<Float?>(null) }
    var tilt by remember { mutableStateOf<Float?>(null) }
    var batteryTemp by remember { mutableStateOf<Float?>(null) }
    var charging by remember { mutableStateOf(false) }
    var noise by remember { mutableStateOf<Float?>(null) }
    var adultPresent by remember { mutableStateOf(false) }
    var cameraActive by remember { mutableStateOf(true) }
    var isCameraBlocked by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Waiting to send") }
    var assessment by remember { mutableStateOf<Assessment?>(null) }
    var busy by remember { mutableStateOf(false) }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { micGranted = it }

    LaunchedEffect(Unit) {
        if (!micGranted) launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(micGranted) {
        if (micGranted) measureNoise { noise = it }
    }

    LaunchedEffect(assessment) {
        val isHighDanger = assessment?.level == "high" && assessment?.alarmSound == true
        if (isHighDanger && !isAlarmSilenced) {
            if (!ringtone.isPlaying) {
                ringtone.play()
            }
        } else {
            if (ringtone.isPlaying) {
                ringtone.stop()
            }
            if (!isHighDanger) {
                isAlarmSilenced = false
            }
        }
    }

    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_LIGHT -> lux = e.values[0]
                    Sensor.TYPE_ACCELEROMETER -> {
                        val x = e.values[0]
                        val y = e.values[1]
                        val z = e.values[2]
                        val mag = sqrt(x * x + y * y + z * z)
                        accel = mag
                        if (mag > 0f) {
                            val c = (z / mag).toDouble().coerceIn(-1.0, 1.0)
                            tilt = Math.toDegrees(acos(c)).toFloat()
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        onDispose {
            sm.unregisterListener(listener)
            if (ringtone.isPlaying) ringtone.stop()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (t != null && t != Int.MIN_VALUE) batteryTemp = t / 10f
            charging = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            delay(5000)
        }
    }

    fun sendNow(simulateHazard: Boolean = false) {
        if (busy) return
        busy = true
        val body = JSONObject().apply {
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            put("room", ROOM)
            put("child_age", CHILD_AGE)
            put("adult_present", if (simulateHazard) false else adultPresent)
            put("phone", JSONObject().apply {
                put("light_lux", if (simulateHazard) 5.0 else (lux ?: 0f).toDouble())
                put("battery_temp_c", if (simulateHazard) 37.0 else (batteryTemp ?: 0f).toDouble())
                put("charging", charging)
                put("camera_active", cameraActive)
                put("camera_blocked", if (simulateHazard) false else isCameraBlocked)
            })
            put("sensors", JSONObject().apply {
                put("noise_db", if (simulateHazard) 88.0 else (noise ?: 0f).toDouble())
                put("motion_ms2", if (simulateHazard) 15.0 else (accel ?: 9.81f).toDouble())
                put("tilt_deg", if (simulateHazard) 110.0 else (tilt ?: 0f).toDouble())
                if (simulateHazard) {
                    put("smoke_level", 0.70)
                    put("gas_ppm", 650.0)
                }
            })
            put("history", JSONArray())
        }
        scope.launch {
            status = if (simulateHazard) "Simulating hazard spike..." else "Sending to AI crew..."
            val t0 = System.currentTimeMillis()
            try {
                assessment = postReading(endpointUrl, body)
                status = "OK - answered in ${(System.currentTimeMillis() - t0) / 1000} s"
            } catch (e: Exception) {
                status = "Failed: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(3000)
        while (true) {
            sendNow()
            delay(SEND_EVERY_MS)
        }
    }

    val levelColor = when (assessment?.level) {
        "low" -> Color(0xFF2E7D32)
        "medium" -> Color(0xFFF9A825)
        "high" -> Color(0xFFC62828)
        else -> Color.Gray
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("HazardGuard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(status.take(24), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }

        TabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("🛡️ Monitor", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("📊 Sensors", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("⚙️ Settings", fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) }
            )
        }

        val isAlarmActive = ringtone.isPlaying || (assessment?.level == "high" && assessment?.alarmSound == true && !isAlarmSilenced)
        if (isAlarmActive) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🚨 SIREN ALARM RINGING",
                        color = Color(0xFFB71C1C),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            ringtone.stop()
                            isAlarmSilenced = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                    ) {
                        Text("Silence", color = Color.White)
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📹 Nursery Cam", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (cameraActive) "ON" else "OFF", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 4.dp))
                            Switch(checked = cameraActive, onCheckedChange = { cameraActive = it })
                        }
                    }

                    if (cameraActive) {
                        CameraObservationPreview(
                            modifier = Modifier.padding(bottom = 6.dp),
                            isCameraEnabled = cameraActive,
                            isBlocked = isCameraBlocked,
                            onObstructionChanged = { isCameraBlocked = it }
                        )
                    }

                    // QUICK SENSOR GLANCE CHIPS - ZERO SCROLLING NEEDED!
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        QuickSensorChip("🔊", noise?.let { "%.0fdB".format(it) } ?: "-", "Noise", (noise ?: 0f) > 65f)
                        QuickSensorChip("💡", lux?.let { "%.0flx".format(it) } ?: "-", "Light")
                        QuickSensorChip("🏃", accel?.let { "%.1f".format(it) } ?: "-", "Motion")
                        QuickSensorChip("📐", tilt?.let { "%.0f°".format(it) } ?: "-", "Tilt", (tilt ?: 0f) > 75f)
                        QuickSensorChip("🔋", batteryTemp?.let { "%.0f°C".format(it) } ?: "-", "Temp")
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    assessment?.alertTitle.takeIf { !it.isNullOrBlank() } ?: "Nursery Status",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = levelColor
                                )
                                Text(
                                    "Risk: ${assessment?.level?.uppercase() ?: "SAFE"}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = levelColor
                                )
                            }
                            if (!assessment?.prediction.isNullOrBlank()) {
                                Text(assessment?.prediction ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            }
                            if (!assessment?.action.isNullOrBlank()) {
                                Text(assessment?.action ?: "", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                            }
                            if (isCameraBlocked) {
                                Text(
                                    "⚠️ Vision Obstructed: Camera is covered or dark. Line-of-sight to child is lost.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFC62828),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    val contactLabel = if (emergencyContactName.isNotBlank() && emergencyContactName != "Family / Caregiver")
                        emergencyContactName.take(12)
                    else "Caregiver"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (emergencyContactNumber.isNotBlank()) {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${emergencyContactNumber.trim()}"))
                                    context.startActivity(intent)
                                }
                            },
                            enabled = emergencyContactNumber.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            colors = if (assessment?.emergencyCallRecommended == true || assessment?.level == "high")
                                ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                            else ButtonDefaults.buttonColors()
                        ) {
                            Text(
                                if (assessment?.emergencyCallRecommended == true || assessment?.level == "high")
                                    "🚨 Call $contactLabel"
                                else "📞 Call $contactLabel"
                            )
                        }

                        if (!assessment?.emergencySmsContent.isNullOrBlank() && emergencyContactNumber.isNotBlank()) {
                            Button(
                                onClick = {
                                    val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("smsto:${emergencyContactNumber.trim()}")
                                        putExtra("sms_body", assessment?.emergencySmsContent)
                                    }
                                    context.startActivity(smsIntent)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD84315))
                            ) {
                                Text("💬 SMS $contactLabel")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Adult Present in Nursery", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = adultPresent, onCheckedChange = { adultPresent = it })
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { sendNow(simulateHazard = false) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Send Real Data")
                        }
                        Button(
                            onClick = { sendNow(simulateHazard = true) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                        ) {
                            Text("⚡ Test Hazard")
                        }
                    }
                }
            }
            1 -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Live Sensor Readings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))

                    SensorDetailCard(
                        title = "Acoustic Noise",
                        value = noise?.let { "%.0f dB".format(it) } ?: "Waiting",
                        subtext = if (!micGranted) "Mic permission required" else if ((noise ?: 0f) > 65f) "⚠️ High sound / infant crying" else "Quiet nursery (<55 dB safe)",
                        icon = "🔊",
                        isWarning = (noise ?: 0f) > 65f
                    )

                    SensorDetailCard(
                        title = "Ambient Lighting",
                        value = lux?.let { "%.0f lux".format(it) } ?: "No data",
                        subtext = if ((lux ?: 100f) < 5f) "Dim room / night nursery" else "Normal nursery light",
                        icon = "💡"
                    )

                    SensorDetailCard(
                        title = "Motion & Accel",
                        value = accel?.let { "%.2f m/s²".format(it) } ?: "No data",
                        subtext = if (abs((accel ?: 9.81f) - 9.81f) > 3f) "⚠️ High motion detected" else "Device stationary (1G gravity)",
                        icon = "🏃",
                        isWarning = abs((accel ?: 9.81f) - 9.81f) > 3f
                    )

                    SensorDetailCard(
                        title = "Tilt Angle",
                        value = tilt?.let { "%.0f°".format(it) } ?: "No data",
                        subtext = if ((tilt ?: 0f) > 75f) "⚠️ Device toppled or fallen (>75°)" else "Normal stable stand position (<65° safe)",
                        icon = "📐",
                        isWarning = (tilt ?: 0f) > 75f
                    )

                    SensorDetailCard(
                        title = "Battery Temperature",
                        value = batteryTemp?.let { "%.1f °C".format(it) } ?: "No data",
                        subtext = if (charging) "Charging plugged in" else "Normal battery state",
                        icon = "🔋",
                        isWarning = (batteryTemp ?: 0f) > 36f
                    )

                    SensorDetailCard(
                        title = "Camera Vision",
                        value = if (!cameraActive) "Disabled" else if (isCameraBlocked) "Obstructed" else "Active",
                        subtext = if (isCameraBlocked) "⚠️ Nursery view obstructed" else "Live child line-of-sight active",
                        icon = "📹",
                        isWarning = isCameraBlocked
                    )

                    Button(
                        onClick = { sendNow(simulateHazard = false) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 20.dp)
                    ) {
                        Text("Transmit Telemetry to AI Crew")
                    }
                }
            }
            2 -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Designated Caregiver Contact", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                            contactPickerLauncher.launch(intent)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Text("👤 Pick From Phone Contacts")
                    }

                    OutlinedTextField(
                        value = emergencyContactNumber,
                        onValueChange = {
                            emergencyContactNumber = it
                            prefs.edit().putString("emergency_contact_number", it).apply()
                        },
                        label = { Text("Contact Phone ($emergencyContactName)") },
                        placeholder = { Text("Enter phone number or select above") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    Text("Network Webhook Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = endpointUrl,
                        onValueChange = {
                            endpointUrl = it
                            prefs.edit().putString("webhook_url", it).apply()
                        },
                        label = { Text("Webhook URL") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall
                    )

                    Button(
                        onClick = {
                            endpointUrl = DEFAULT_CLOUD_URL
                            prefs.edit().putString("webhook_url", DEFAULT_CLOUD_URL).apply()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text("Reset to Default Cloud URL")
                    }
                }
            }
        }
    }
}