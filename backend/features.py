import json
from datetime import datetime

with open("config/thresholds.json") as f:
    LIMITS = json.load(f)

MIN_POINTS = 3  # readings needed before a trend counts
TREND_SENSORS = {"gas_ppm", "smoke_level", "co2_ppm", "temp_c"}


def risk_score(sensor, value):
    lim = LIMITS[sensor]
    score = (value - lim["safe"]) / (lim["danger"] - lim["safe"])
    return round(min(max(score, 0.0), 1.0), 2)


def get_points(history, sensor, current_time, current_value):
    pts = [(datetime.fromisoformat(h["timestamp"]), h[sensor])
           for h in history if sensor in h]
    pts.append((datetime.fromisoformat(current_time), current_value))
    return pts


def trend_per_min(points):
    if len(points) < 2:
        return 0.0
    (t0, v0), (t1, v1) = points[0], points[-1]
    minutes = (t1 - t0).total_seconds() / 60
    if minutes <= 0:
        return 0.0
    return round((v1 - v0) / minutes, 2)


def minutes_to_danger(sensor, value, slope):
    danger = LIMITS[sensor]["danger"]
    if value >= danger:
        return 0.0
    if slope <= 0:
        return None
    return round((danger - value) / slope, 1)


def child_weight(age, adult_present):
    weight = 1.5 if age <= 3 else 1.25 if age <= 6 else 1.0
    if not adult_present:
        weight *= 1.2
    return round(weight, 2)


def analyse(reading):
    history = reading.get("history", [])
    sensors = {}
    static = 0.0
    floor = 0.0

    src = dict(reading.get("sensors") or reading.get("simulated") or {})
    if "motion_ms2" in src:
        src["motion_dev"] = round(abs(src.pop("motion_ms2") - 9.81), 2)
    ts = reading.get("timestamp") or datetime.now().isoformat()
    for name, value in src.items():
        if name not in LIMITS:
            continue
        pts = get_points(history, name, ts, value)
        slope = trend_per_min(pts) if (len(pts) >= MIN_POINTS and name in TREND_SENSORS) else 0.0
        mtd = minutes_to_danger(name, value, slope)
        r = risk_score(name, value)
        sensors[name] = {"value": value, "risk_score": r,
                         "trend_per_min": slope, "minutes_to_danger": mtd}
        static = max(static, r)
        if mtd is not None and mtd > 0:
            if mtd <= 2:
                floor = max(floor, 0.6)
            elif mtd <= 5:
                floor = max(floor, 0.4)
            elif mtd <= 10:
                floor = max(floor, 0.25)

    age = reading.get("child_age", 1)
    adult = reading.get("adult_present", False)
    phone = reading.get("phone", {})
    if phone.get("camera_active", True) and phone.get("camera_blocked", False) and not adult:
        floor = max(floor, 0.35)  # unsupervised blind spot warning
    if src.get("child_cry_detected", False) and not adult:
        floor = max(floor, 0.65)  # acute pediatric cry distress detected
    if adult and age >= 7:
        floor *= 0.6          # supervised older child: soften trend warnings

    weight = child_weight(age, adult)
    overall = round(min(1.0, max(static, floor) * weight), 2)
    return {"sensors": sensors, "child_weight": weight, "overall_risk": overall}
