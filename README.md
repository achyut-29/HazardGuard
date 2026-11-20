# HazardGuard

> **Autonomous Edge-Agent Sentinel & Multi-Modal Perception System for Child Safety**

[![Latest Release](https://img.shields.io/github/v/release/achyut-29/HazardGuard?color=blue&label=Latest%20Release)](https://github.com/achyut-29/HazardGuard/releases/tag/v1.0.0)
[![Download APK](https://img.shields.io/badge/Download-HazardGuard%20v1.0.0.apk-success?logo=android)](https://github.com/achyut-29/HazardGuard/releases/download/v1.0.0/HazardGuard-v1.0.0.apk)

📥 **[Download Pre-Compiled Android APK (v1.0.0)](https://github.com/achyut-29/HazardGuard/releases/download/v1.0.0/HazardGuard-v1.0.0.apk)**

HazardGuard is an intelligent edge-agent and multi-modal perception system designed to run on resource-constrained mobile hardware (calibrated on Motorola Edge 40 Neo). It continuously monitors micro-environmental safety, detects infant physical and acoustic distress, executes autonomous local reflexes, and interfaces with a cloud-based 6-agent deliberative reasoning hierarchy.

---

## 🧠 System Architecture: Dual-Loop Agentic Paradigm

HazardGuard employs a **Dual-Loop Agentic Architecture** separating fast reactive reflexes from deep deliberative reasoning:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        HAZARDGUARD SYSTEM TOPOLOGY                     │
└────────────────────────────────────────────────────────────────────────┘

    [ Fast Reactive Loop (Edge Mobile Agent) ]
    ┌──────────────────────────────────────────────────────────────┐
    │  • Multi-Modal Physical Telemetry Ingest                     │
    │  • Real-Time Edge Reflexes (<5ms response time)              │
    │  • Dual-Tone Haptic & Acoustic Annunciation                  │
    │  • Dynamic Human-in-the-Loop (HITL) Supervisory Override     │
    └──────────────────────────────┬───────────────────────────────┘
                                   │ Event-Driven JSON Stream
                                   ▼
    [ Deliberative Multi-Agent Hierarchy (Cloud AI Crew) ]
    ┌──────────────────────────────────────────────────────────────┐
    │  • 6-Agent Collaborative CrewAI Pipeline (Google Gemini):    │
    │    1. Physical Perception Agent (Lux, Tilt, Motion, Temp)    │
    │    2. Child Observation & Acoustic Agent (FFT Cry, Camera)   │
    │    3. Risk Predictor Agent (10-Min Trajectory, Derivatives)  │
    │    4. Safety Knowledge Agent (AAP/WHO Pediatric Guidelines)  │
    │    5. Parent Action Planner Agent (Synthesized Advice)       │
    │    6. Emergency Dispatcher & Siren Agent (Siren, Auto-Call)  │
    │  • Multi-Agent Consensus Risk Synthesis                      │
    │  • Autonomous Actuation & Telephony Dispatch                 │
    └──────────────────────────────────────────────────────────────┘
```

---

## 🌟 Key Capabilities

### 1. Multi-Modal Edge Sensing & Feature Extraction
- **Acoustic Spectral Analysis**: Real-time PCM 16-bit audio sampling with FFT pitch tracking to detect pediatric acoustic distress ($350\text{–}800\text{ Hz}$) at high sound pressure levels ($\ge 75\text{ dB}$).
- **Kinematic Spatial Orientation**: Continuous 3-axis accelerometer vector analysis tracking spatial tilt angles ($0^\circ\text{–}180^\circ$). Calibrated for upright and angled cradle-monitoring positions ($60^\circ\text{–}110^\circ$), with instant topple/drop reflexes triggered at $>115^\circ$.
- **Photometric Environment Classification**: Context-aware ambient lux categorization distinguishing sleep-promoting darkness ($<5\text{ lx}$), normal nursery lighting ($10\text{–}300\text{ lx}$), and intense glare ($>1200\text{ lx}$).
- **Visual Line-of-Sight Supervision**: Real-time camera stream analysis detecting lens obstruction or darkness blanketing to ensure an unbroken monitoring line-of-sight.
- **Hardware Telemetry & Zero-Sleep Sentry**: Continuous battery thermistor tracking and `FLAG_KEEP_SCREEN_ON` enforcement to prevent Android Doze mode or sensor pausing during untethered monitoring.

### 2. Dual Alert Actuation & Autonomous Dispatch
- **Tiered Audio Separation**:
  - *Caution / Advisory*: Notification chime (`TYPE_NOTIFICATION`) and 250ms haptic pulse for non-critical physical events (cradle tilt, infant whimper, lens obstruction).
  - *Critical Hazard*: Continuous emergency siren (`TYPE_ALARM`) for confirmed multi-sensor danger.
- **Autonomous Telephony Dispatch**:
  - Integrated `CALL_PHONE` actuator triggering direct phone calls via `Intent.ACTION_CALL` upon critical hazard confirmation with single-fire loop suppression.
  - Automated pre-composed emergency SMS dispatch with real-time telemetry diagnostics.

### 3. Closed-Loop Human-in-the-Loop (HITL) De-escalation
- **Adult-Present State Machine**: Toggling the "Adult Present in Nursery" switch immediately executes a complete system de-escalation:
  - Instantly silences all active sirens and warning chimes.
  - Re-evaluates system status to `LOW RISK` / `SUPERVISED`.
  - Dispatches an updated telemetry packet with `adult_present: true` to the cloud multi-agent hierarchy.
  - Suppresses false alerts during active parental caregiving.

### 4. Resilient Multi-Gateway Transport Routing
Zero-downtime multi-tier communication layer ensuring unbroken edge-to-cloud telemetry:
1. **Tier 1 (Direct Wi-Fi LAN)**: `http://10.1.25.92:5678/webhook/sensor` (instant local subnet routing)
2. **Tier 2 (ADB Reverse Wireless/USB)**: `http://localhost:5678/webhook/sensor`
3. **Tier 3 (Mobile Hotspot Gateways)**: Auto-failover across `192.168.43.1`, `10.42.0.1`, `192.168.137.1`
4. **Tier 4 (Encrypted Public Cloud Tunnel)**: Real-time Pinggy HTTPS bridge (`https://*.free.pinggy.net`) and Cloudflare fallback

---

## 📡 Edge-to-Cloud Telemetry Contract

### Outbound Edge Telemetry
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

### Inbound Multi-Agent Assessment
```json
{
  "risk_level": "low",
  "risk_score": 0,
  "prediction": "All environmental and behavioral parameters remain completely stable with an indefinite time-to-danger over the next 10 minutes.",
  "action": "Continue regular nursery observation.",
  "alarm_sound": false,
  "emergency_call_recommended": false,
  "alert_title": "NURSERY SAFE"
}
```

---

## 🛠️ Tech Stack

- **Platform**: Android 14+ (API 34)
- **Language**: Kotlin 2.0+
- **UI Architecture**: Jetpack Compose & Material Design 3
- **Sensors**: SensorManager (Light, Accelerometer), AudioRecord PCM 16-bit FFT, BatteryManager, Camera2 / CameraX
- **Concurrency**: Kotlin Coroutines & StateFlow

---

## 🚀 Getting Started

```bash
# Clone the repository
git clone https://github.com/achyut-29/HazardGuard.git
cd HazardGuard

# Build debug APK
./gradlew assembleDebug

# Install on target device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch application
adb shell am start -n com.example.hazardguard/.MainActivity
```
