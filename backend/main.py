import concurrent.futures
from fastapi import FastAPI
from crew import run_crew, level_from_score
from features import analyse

app = FastAPI(title="Hazard analysis service")
executor = concurrent.futures.ThreadPoolExecutor(max_workers=2)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/analyse")
def analyse_endpoint(reading: dict):
    print("Received reading:", reading)
    try:
        future = executor.submit(run_crew, reading)
        return future.result(timeout=25.0)
    except Exception as e:
        feats = analyse(reading)
        score = feats["overall_risk"]
        err_msg = type(e).__name__ if not isinstance(e, concurrent.futures.TimeoutError) else "Timeout (>25s)"
        return {
            "risk_level": level_from_score(score),
            "risk_score": score,
            "prediction": "Automatic estimate from sensor numbers (AI crew fallback).",
            "reason": f"Computed directly from sensor trends and child context ({err_msg}).",
            "action": "Check the room and the child now." if score >= 0.3 else "No action needed.",
            "confidence": 0.5,
            "alarm_sound": score >= 0.7,
            "emergency_call_recommended": score >= 0.7,
            "alert_title": "🚨 URGENT: DANGER DETECTED" if score >= 0.7 else ("⚠️ WARNING: CHECK NURSERY" if score >= 0.3 else "✅ NURSERY SAFE"),
            "emergency_sms_content": "EMERGENCY: Child in Nursery at RISK! Please check immediately!" if score >= 0.7 else "",
        }
