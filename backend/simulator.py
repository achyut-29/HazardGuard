import sys, time, random, requests
from datetime import datetime, timedelta

URL = "http://localhost:5678/webhook/sensor"
START = datetime(2026, 10, 28, 18, 0, 0)


def noise(x, spread):
    return round(x + random.uniform(-spread, spread), 2)


def normal(m):
    return dict(room="living_room", child_age=8, adult_present=True,
                phone={"audio_class": "quiet", "image_note": "child reading on sofa"},
                gas=noise(190, 4), smoke=0.02, co2=noise(700, 20), temp=noise(24, 0.3))


def gas_leak(m):
    return dict(room="kitchen", child_age=3, adult_present=(m < 4),
                phone={"audio_class": "baby_crying" if m >= 5 else "quiet",
                       "image_note": "toddler near stove" if m >= 4 else "child playing"},
                gas=noise(150 + 70 * m, 4), smoke=0.03, co2=noise(750, 20), temp=noise(26, 0.3))


def cooking_spike(m):
    spike = m in (3, 4, 5)
    return dict(room="kitchen", child_age=8, adult_present=True,
                phone={"audio_class": "quiet", "image_note": "adult cooking, child at table"},
                gas=noise(200, 5), smoke=0.15 if spike else 0.03,
                co2=noise(900 if spike else 750, 20), temp=noise(29 if spike else 26, 0.3))


def poor_air(m):
    return dict(room="bedroom", child_age=2, adult_present=False,
                phone={"audio_class": "quiet", "image_note": "toddler sleeping, window closed"},
                gas=noise(180, 4), smoke=0.02, co2=noise(800 + 170 * m, 20), temp=noise(27, 0.3))


SCENARIOS = {"normal": normal, "gas_leak": gas_leak,
             "cooking_spike": cooking_spike, "poor_air": poor_air}


def run(name, minutes=8, delay=15):
    make = SCENARIOS[name]
    history = []
    for m in range(minutes):
        s = make(m)
        ts = (START + timedelta(minutes=m)).isoformat()
        reading = {
            "timestamp": ts, "room": s["room"], "child_age": s["child_age"],
            "adult_present": s["adult_present"], "phone": s["phone"],
            "simulated": {"gas_ppm": s["gas"], "smoke_level": s["smoke"],
                          "co2_ppm": s["co2"], "temp_c": s["temp"]},
            "history": history[-5:],
        }
        try:
            r = requests.post(URL, json=reading, timeout=150)
            out = r.json()
            print(f"min {m}: gas={s['gas']:>7} co2={s['co2']:>7} -> "
                  f"{out.get('risk_level', '?'):6s} | {out.get('prediction', out)}")
        except Exception as e:
            print(f"min {m}: failed ({type(e).__name__})")
        history.append({"timestamp": ts, "gas_ppm": s["gas"], "co2_ppm": s["co2"],
                        "smoke_level": s["smoke"], "temp_c": s["temp"]})
        time.sleep(delay)


if __name__ == "__main__":
    name = sys.argv[1] if len(sys.argv) > 1 else "normal"
    if name not in SCENARIOS:
        print("Choose one of:", ", ".join(SCENARIOS))
        sys.exit(1)
    run(name)
