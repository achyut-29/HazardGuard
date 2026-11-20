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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.ContactsContract
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import kotlin.math.min
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
suspend fun measureNoise(onAudioAnalyzed: (db: Float, pitchHz: Float, isCry: Boolean) -> Unit) = withContext(Dispatchers.IO) {
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
            if (n > 500) {
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                val rms = sqrt(sum / n)
                val db = (20 * log10(max(rms, 1.0))).toFloat()

                // Pediatric Pitch & Frequency Estimation (Autocorrelation on downsampled window)
                var estimatedPitch = 0f
                var isChildCry = false

                if (db > 60f) {
                    val windowSize = min(n, 1024)
                    val minLag = 18   // 16000 / 900 Hz ~ 18 samples (highest infant cry pitch)
                    val maxLag = 160  // 16000 / 100 Hz ~ 160 samples (lowest adult speech pitch)
                    var bestLag = -1
                    var bestCorr = 0.0
                    var energy0 = 0.0

                    for (i in 0 until windowSize - maxLag) {
                        energy0 += buf[i].toDouble() * buf[i].toDouble()
                    }

                    if (energy0 > 1e4) {
                        for (lag in minLag..maxLag) {
                            var corr = 0.0
                            for (i in 0 until windowSize - maxLag) {
                                corr += buf[i].toDouble() * buf[i + lag].toDouble()
                            }
                            if (corr > bestCorr) {
                                bestCorr = corr
                                bestLag = lag
                            }
                        }
                        if (bestLag > 0 && bestCorr > energy0 * 0.35) {
                            estimatedPitch = rate.toFloat() / bestLag
                        }
                    }

                    // Pediatric Clinical Criteria (AAP/WHO Research):
                    // Infant / Toddler Cry: High pitch (350 Hz to 850 Hz) and volume >= 74 dB
                    // Adult Speech: Low pitch (85 Hz to 260 Hz) - non-critical
                    // Random noise: Low harmonic periodicity or non-cry frequency
                    isChildCry = (estimatedPitch in 350f..850f) && (db >= 74f)
                }

                onAudioAnalyzed(db, estimatedPitch, isChildCry)
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
                            if (isCameraEnabled && isBlocked) Color(0xFFFF5252)
                            else if (isCameraEnabled && hasCameraPermission) Color(0xFF00E676)
                            else Color.Gray,
                            RoundedCornerShape(4.dp)
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (isCameraEnabled && isBlocked) "⚠️ LENS OBSTRUCTED"
                    else if (isCameraEnabled && hasCameraPermission) "LIVE CHILD OBSERVATION"
                    else "CAMERA OFF",
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

fun triggerWarningAlert(context: Context) {
    try {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val r = RingtoneManager.getRingtone(context.applicationContext, uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            r?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
        r?.play()
    } catch (_: Exception) {}

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(250)
            }
        }
    } catch (_: Exception) {}
}

fun placeEmergencyCall(context: Context, number: String) {
    if (number.isBlank()) return
    try {
        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = if (hasCallPerm) {
            Intent(Intent.ACTION_CALL, Uri.parse("tel:${number.trim()}"))
        } else {
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.trim()}"))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.trim()}"))
            dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(dialIntent)
        } catch (_: Exception) {}
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
    var pitchHz by remember { mutableStateOf(0f) }
    var isChildCry by remember { mutableStateOf(false) }
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

    var callPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { callPermissionGranted = it }

    var autoCallEnabled by remember {
        mutableStateOf(prefs.getBoolean("auto_call_enabled", true))
    }
    var hasAutoCalled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!micGranted) launcher.launch(Manifest.permission.RECORD_AUDIO)
        if (!callPermissionGranted) callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
    }
    LaunchedEffect(micGranted) {
        if (micGranted) measureNoise { db, pitch, cry ->
            noise = db
            pitchHz = pitch
            isChildCry = cry
        }
    }

    var lastAlertLevel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(assessment) {
        val currentLevel = assessment?.level?.lowercase()
        val isHighDanger = currentLevel == "high" && assessment?.alarmSound == true
        val isWarning = currentLevel == "medium" || currentLevel == "warning"

        if (isHighDanger && !isAlarmSilenced) {
            if (!ringtone.isPlaying) {
                ringtone.play()
            }
            if (autoCallEnabled && emergencyContactNumber.isNotBlank() && !hasAutoCalled) {
                hasAutoCalled = true
                placeEmergencyCall(context, emergencyContactNumber)
            }
        } else {
            if (ringtone.isPlaying) {
                ringtone.stop()
            }
            if (!isHighDanger) {
                isAlarmSilenced = false
                hasAutoCalled = false
            }
            if (isWarning && lastAlertLevel != "medium" && lastAlertLevel != "warning") {
                triggerWarningAlert(context)
            }
        }
        lastAlertLevel = currentLevel
    }

    var lastCameraBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(cameraActive, isCameraBlocked) {
        val blockedNow = cameraActive && isCameraBlocked
        if (blockedNow && !lastCameraBlocked) {
            if (!ringtone.isPlaying) {
                triggerWarningAlert(context)
            }
        }
        lastCameraBlocked = blockedNow
    }

    var lastChildCry by remember { mutableStateOf(false) }
    LaunchedEffect(isChildCry) {
        if (isChildCry && !lastChildCry) {
            if (!ringtone.isPlaying) {
                triggerWarningAlert(context)
            }
        }
        lastChildCry = isChildCry
    }

    var lastTiltWarning by remember { mutableStateOf(false) }
    LaunchedEffect(tilt) {
        val isTiltedNow = (tilt ?: 0f) > 75f
        if (isTiltedNow && !lastTiltWarning) {
            if (!ringtone.isPlaying) {
                triggerWarningAlert(context)
            }
        }
        lastTiltWarning = isTiltedNow
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
                put("camera_blocked", if (simulateHazard) false else (cameraActive && isCameraBlocked))
            })
            put("sensors", JSONObject().apply {
                put("noise_db", if (simulateHazard) 88.0 else (noise ?: 0f).toDouble())
                put("pitch_hz", if (simulateHazard) 580.0 else pitchHz.toDouble())
                put("child_cry_detected", if (simulateHazard) true else isChildCry)
                put("motion_ms2", if (simulateHazard) 15.0 else (accel ?: 9.81f).toDouble())
                put("tilt_deg", if (simulateHazard) 110.0 else (tilt ?: 0f).toDouble())
                if (simulateHazard) {
                    put("smoke_level", 0.70)
                    put("gas_ppm", 650.0)
                }
            })
            put("history", JSONArray())
        }
        if (simulateHazard) {
            // Immediate zero-latency demo trigger: Instant red alert, siren, and dispatch buttons
            assessment = Assessment(
                level = "high",
                prediction = "CRITICAL: Fast-rising environmental surge with infant acoustic distress (>85 dB).",
                action = "Trigger emergency siren immediately! Physical parental intervention required.",
                alarmSound = true,
                emergencyCallRecommended = true,
                alertTitle = "🚨 URGENT: DANGER DETECTED",
                emergencySmsContent = "EMERGENCY: High hazard near child in nursery! Severe crying & fallen device detected. Check immediately!"
            )
            isAlarmSilenced = false
            status = "⚡ Hazard Active (Instant Response)"
        }
        scope.launch {
            if (!simulateHazard) {
                status = "Sending to AI crew..."
            }
            val t0 = System.currentTimeMillis()
            try {
                val remoteAssessment = postReading(endpointUrl, body)
                assessment = remoteAssessment
                status = if (simulateHazard) "OK - AI verified in ${(System.currentTimeMillis() - t0) / 1000} s" else "OK - answered in ${(System.currentTimeMillis() - t0) / 1000} s"
            } catch (e: Exception) {
                if (!simulateHazard) {
                    status = "Failed: ${e.javaClass.simpleName}: ${e.message}"
                }
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
                    val silenceInteractionSource = remember { MutableInteractionSource() }
                    val isSilencePressed by silenceInteractionSource.collectIsPressedAsState()
                    val silenceColor by animateColorAsState(
                        if (isSilencePressed) Color(0xFFEF5350) else Color(0xFFB71C1C),
                        label = "silenceColor"
                    )
                    Button(
                        onClick = {
                            ringtone.stop()
                            isAlarmSilenced = true
                        },
                        interactionSource = silenceInteractionSource,
                        colors = ButtonDefaults.buttonColors(containerColor = silenceColor)
                    ) {
                        Text("Silence", color = Color.White)
                    }
                }
            }
        }

        val isWarningActive = !isAlarmActive && (assessment?.level == "medium" || (cameraActive && isCameraBlocked) || isChildCry || (tilt ?: 0f) > 75f)
        if (isWarningActive) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9C4)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚠️", modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.titleMedium)
                    Column(modifier = Modifier.weight(1f)) {
                        val warningTitle = when {
                            cameraActive && isCameraBlocked -> "CAUTION: Camera Lens Obstructed"
                            isChildCry -> "CAUTION: Infant Distress Cry Detected"
                            (tilt ?: 0f) > 75f -> "CAUTION: Stand Toppled (>75°)"
                            else -> "CAUTION: Nursery Warning (${assessment?.alertTitle.takeIf { !it.isNullOrBlank() } ?: "Elevated Risk"})"
                        }
                        val warningDetail = when {
                            cameraActive && isCameraBlocked -> "Line-of-sight to child is lost. Clear camera obstruction."
                            isChildCry -> "High-pitch acoustic cry frequency (%.0f Hz) detected.".format(pitchHz)
                            (tilt ?: 0f) > 75f -> "Device is tilted at %.0f° — reposition stand.".format(tilt ?: 0f)
                            else -> assessment?.prediction ?: "Environmental parameters require attention."
                        }
                        Text(
                            warningTitle,
                            color = Color(0xFFE65100),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            warningDetail,
                            color = Color(0xFF5D4037),
                            style = MaterialTheme.typography.bodySmall
                        )
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
                            Switch(checked = cameraActive, onCheckedChange = {
                                cameraActive = it
                                if (!it) isCameraBlocked = false
                            })
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
                        QuickSensorChip("🔊", noise?.let { "%.0fdB".format(it) } ?: "-", if (isChildCry) "CRYING!" else "Noise", isChildCry || (noise ?: 0f) > 80f)
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
                            if (cameraActive && isCameraBlocked) {
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
                        val callInteractionSource = remember { MutableInteractionSource() }
                        val isCallPressed by callInteractionSource.collectIsPressedAsState()
                        val isEmergencyCall = assessment?.emergencyCallRecommended == true || assessment?.level == "high"
                        val callButtonTargetColor = when {
                            isCallPressed -> Color(0xFFFF8A80)
                            isEmergencyCall -> Color(0xFFC62828)
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }
                        val callButtonColor by animateColorAsState(callButtonTargetColor, label = "callColor")

                        Button(
                            onClick = {
                                if (emergencyContactNumber.isNotBlank()) {
                                    placeEmergencyCall(context, emergencyContactNumber)
                                }
                            },
                            enabled = emergencyContactNumber.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            interactionSource = callInteractionSource,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = callButtonColor,
                                contentColor = if (isEmergencyCall) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Text(
                                if (isEmergencyCall) "🚨 Call $contactLabel"
                                else "📞 Call $contactLabel"
                            )
                        }

                        if (!assessment?.emergencySmsContent.isNullOrBlank() && emergencyContactNumber.isNotBlank()) {
                            val smsInteractionSource = remember { MutableInteractionSource() }
                            val isSmsPressed by smsInteractionSource.collectIsPressedAsState()
                            val smsButtonTargetColor = if (isSmsPressed) Color(0xFFFFAB91) else Color(0xFFD84315)
                            val smsButtonColor by animateColorAsState(smsButtonTargetColor, label = "smsColor")

                            Button(
                                onClick = {
                                    val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("smsto:${emergencyContactNumber.trim()}")
                                        putExtra("sms_body", assessment?.emergencySmsContent)
                                    }
                                    context.startActivity(smsIntent)
                                },
                                modifier = Modifier.weight(1f),
                                interactionSource = smsInteractionSource,
                                colors = ButtonDefaults.buttonColors(containerColor = smsButtonColor)
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

                    val sendInteractionSource = remember { MutableInteractionSource() }
                    val isSendPressed by sendInteractionSource.collectIsPressedAsState()
                    val sendButtonTargetColor = when {
                        isSendPressed -> Color(0xFF64B5F6)
                        busy -> Color(0xFF42A5F5)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val sendButtonColor by animateColorAsState(sendButtonTargetColor, label = "sendColor")

                    val hazardInteractionSource = remember { MutableInteractionSource() }
                    val isHazardPressed by hazardInteractionSource.collectIsPressedAsState()
                    val isHazardActive = assessment?.level == "high"
                    val hazardButtonTargetColor = when {
                        isHazardPressed -> Color(0xFFFF8A80)
                        isHazardActive -> Color(0xFFE53935)
                        else -> Color(0xFFC62828)
                    }
                    val hazardButtonColor by animateColorAsState(hazardButtonTargetColor, label = "hazardColor")

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { sendNow(simulateHazard = false) },
                            modifier = Modifier.weight(1f),
                            interactionSource = sendInteractionSource,
                            colors = ButtonDefaults.buttonColors(containerColor = sendButtonColor)
                        ) {
                            Text(if (busy) "Transmitting..." else "Send Real Data")
                        }
                        Button(
                            onClick = { sendNow(simulateHazard = true) },
                            modifier = Modifier.weight(1f),
                            interactionSource = hazardInteractionSource,
                            colors = ButtonDefaults.buttonColors(containerColor = hazardButtonColor)
                        ) {
                            Text(if (isHazardActive) "⚡ Hazard Active" else "⚡ Test Hazard")
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
                        title = "Acoustic Noise & Child Cry",
                        value = noise?.let { "%.0f dB".format(it) } ?: "Waiting",
                        subtext = if (!micGranted) "Mic permission required"
                                  else if (isChildCry) "🚨 High-pitch infant cry / scream detected (%.0f Hz)".format(pitchHz)
                                  else if ((noise ?: 0f) > 68f && pitchHz in 85f..280f) "Adult speech (low pitch %.0f Hz - normal)".format(pitchHz)
                                  else if ((noise ?: 0f) > 68f) "Environmental room sound (%.0f dB)".format(noise ?: 0f)
                                  else "Quiet nursery environment (<68 dB safe)",
                        icon = "🔊",
                        isWarning = isChildCry || (noise ?: 0f) > 80f
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
                        subtext = if (!cameraActive) "Camera observation turned off by parent" else if (isCameraBlocked) "⚠️ Nursery view obstructed" else "Live child line-of-sight active",
                        icon = "📹",
                        isWarning = cameraActive && isCameraBlocked
                    )

                    val tab1SendInteractionSource = remember { MutableInteractionSource() }
                    val isTab1SendPressed by tab1SendInteractionSource.collectIsPressedAsState()
                    val tab1SendTargetColor = when {
                        isTab1SendPressed -> Color(0xFF64B5F6)
                        busy -> Color(0xFF42A5F5)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val tab1SendColor by animateColorAsState(tab1SendTargetColor, label = "tab1SendColor")

                    Button(
                        onClick = { sendNow(simulateHazard = false) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 20.dp),
                        interactionSource = tab1SendInteractionSource,
                        colors = ButtonDefaults.buttonColors(containerColor = tab1SendColor)
                    ) {
                        Text(if (busy) "Transmitting Telemetry..." else "Transmit Telemetry to AI Crew")
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

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("⚡ Auto-Call on Critical Hazard", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("Automatically dial designated contact when danger is confirmed.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(
                            checked = autoCallEnabled,
                            onCheckedChange = {
                                autoCallEnabled = it
                                prefs.edit().putBoolean("auto_call_enabled", it).apply()
                                if (it && !callPermissionGranted) {
                                    callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                                }
                            }
                        )
                    }

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