# HazardGuard (Android Client)

> **Agentic Child-Safety Environmental Hazard Prediction System**  
> *Final Year B.Tech Electronics & Communication Engineering (ECE) Capstone Project*

HazardGuard is an intelligent edge-sensing Android application designed to transform a nursery-stationed smartphone (tested and calibrated on Motorola Edge 40 Neo) into an autonomous, proactive child safety sentinel. It monitors physical nursery parameters, detects infant distress, classifies room hazards, and communicates seamlessly with a 6-agent CrewAI reasoning engine.

---

## 🌟 Key Features

- **Real-Time Edge Sensor Fusion**:
  - **Acoustic Noise & FFT Pitch Extraction**: Continuous audio sampling detecting pediatric distress crying signatures ($400\text{–}600\text{ Hz}$) at $\ge 80\text{ dB}$.
  - **3-Axis Accelerometer & Tilt Geometry**: Instant detection of cradle or stand topple ($>115^\circ$) while maintaining safe monitoring margins for upright stands ($60^\circ\text{–}110^\circ$).
  - **Pediatric Ambient Lighting Standards**: Intelligent classification recognizing deep darkness ($<5\text{ lx}$) as peaceful infant bedtime sleep mode (`🌙 Night`), standard daytime illumination ($10\text{–}300\text{ lx}$), and intense direct glare ($>1200\text{ lx}$).
  - **Computer Vision Line-of-Sight Check**: Monitors camera obstruction or lens blanketing to safeguard child line-of-sight.
  - **Battery & Thermal Safety**: Monitors battery temperature and charging states.

- **Dual Alert Separation**:
  - **Caution / Warning**: Notification chime (`TYPE_NOTIFICATION`) and a distinct 250ms haptic vibration for non-catastrophic physical events (stand toppled, infant cry, camera blocked).
  - **Critical Hazard**: Continuous high-priority emergency siren (`TYPE_ALARM`) reserved strictly for confirmed multi-sensor danger.

- **Autonomous Caregiver Phone Dispatch**:
  - Integrated direct call trigger (`Intent.ACTION_CALL` via `CALL_PHONE` permission) automatically dialing the designated caregiver (e.g., `Nishant Ece`) upon critical hazard detection with single-fire loop suppression.
  - One-tap Emergency SMS composition with pre-filled telemetry data.

- **4-Tier Resilient Transport Routing**:
  1. **Tier 1 (USB ADB Reverse)**: `http://localhost:5678/webhook/sensor`
  2. **Tier 2 (Wi-Fi LAN)**: `http://10.63.3.116:5678/webhook/sensor`
  3. **Tier 3 (Cloudflare Zero-Trust Tunnel)**: High-speed public HTTPS edge route (`*.trycloudflare.com`)
  4. **Tier 4 (Backup Tunnel)**: Secondary remote fallback

- **Event-Driven Instant UI Synchronization**:
  - Middle "Nursery Status" card updates instantaneously on physical sensor events without waiting for periodic cloud rounds.

---

## 📱 Hardware & Target Environment

- **Target Device**: Motorola Edge 40 Neo (`ZD222HXMRK`)
- **Android Version**: Android 14+ (API Level 34)
- **UI Framework**: Jetpack Compose with Material Design 3
- **Sensors Used**: Hardware Accelerometer, Ambient Light Sensor, AudioRecord PCM 16-bit FFT, BatteryManager API, Camera2/CameraX Preview

---

## 🚀 Building & Running

### Prerequisites
- Android Studio Ladybug | 2024.2+
- Android SDK 34 / Build-Tools 34.0.0
- JDK 17+

### Build from CLI
```bash
# Clone the repository
git clone https://github.com/achyut-29/HazardGuard.git
cd HazardGuard

# Compile debug APK
./gradlew assembleDebug

# Install on connected device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch Application
adb shell am start -n com.example.hazardguard/.MainActivity
```

---

## ⚙️ Architecture & Data Contract

HazardGuard packages live physical measurements into a structured JSON payload:

```json
{
  "room": "nursery",
  "child_age": 1,
  "phone": {
    "battery_temp_c": 31.0,
    "charging": false,
    "light_lux": 2.0,
    "tilt_deg": 88.0,
    "motion_ms2": 9.81,
    "sound_db": 42.0,
    "pitch_hz": 0.0,
    "is_child_cry": false,
    "camera_active": true,
    "camera_blocked": false,
    "adult_present": false
  },
  "env": {}
}
```

The app receives structured guidance from the backend:

```json
{
  "risk_level": "low",
  "risk_score": 0,
  "prediction": "All environmental and behavioral parameters remain completely stable.",
  "action": "Continue regular nursery observation.",
  "alarm_sound": false,
  "emergency_call_recommended": false,
  "alert_title": "NURSERY SAFE"
}
```

---

## 📄 License
Academic Capstone Project — Department of Electronics & Communication Engineering.
