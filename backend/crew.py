import os
import json
from typing import Literal
from dotenv import load_dotenv
from pydantic import BaseModel
from crewai import Agent, Task, Crew, LLM, Process
from features import analyse

load_dotenv()

with open("config/safety_guidelines.md") as f:
    GUIDELINES = f.read()


class Decision(BaseModel):
    risk_level: Literal["low", "medium", "high"]
    risk_score: float
    prediction: str
    reason: str
    action: str
    confidence: float
    alarm_sound: bool = False
    emergency_call_recommended: bool = False
    alert_title: str = "Status Update"
    emergency_sms_content: str = ""


def level_from_score(score):
    if score >= 0.7:
        return "high"
    if score >= 0.3:
        return "medium"
    return "low"


def get_llm():
    gemini_key = os.getenv("GEMINI_API_KEY")
    if gemini_key and gemini_key.strip():
        model_name = os.getenv("GEMINI_MODEL", "gemini-3.5-flash-lite")
        if not model_name.startswith("gemini/"):
            model_name = "gemini/" + model_name
        return LLM(model=model_name, api_key=gemini_key.strip())
    groq_key = os.getenv("GROQ_API_KEY")
    if groq_key and groq_key.strip():
        return LLM(
            model="openai/openai/gpt-oss-20b",
            base_url="https://api.groq.com/openai/v1",
            api_key=groq_key.strip(),
        )
    return LLM(model="gemini/gemini-3.5-flash-lite")


def run_crew(reading):
    llm = get_llm()
    feats = analyse(reading)
    hint = level_from_score(feats["overall_risk"])
    phone = reading.get("phone", {})
    sensors = reading.get("sensors", {})
    noise_db = sensors.get("noise_db", 0)

    # Agent 1: Environmental & Physical Perception
    perception = Agent(
        role="Physical Perception Agent",
        goal="Describe physical surroundings, light levels, device tilt, and thermal state",
        backstory="You interpret physical sensor measurements including ambient lux, tilt angle, device acceleration, and battery temperature.",
        llm=llm, allow_delegation=False,
    )

    # Agent 2: Child Observation & Acoustic Specialist
    child_monitor = Agent(
        role="Child Observation & Acoustic Agent",
        goal="Analyze child state, acoustic distress/crying levels, adult presence, and camera observation",
        backstory="You are a pediatric acoustic and surveillance specialist. You analyze background audio decibels for sudden crying or distress, verify whether a responsible adult is supervising the infant, and evaluate observation status.",
        llm=llm, allow_delegation=False,
    )

    # Agent 3: Risk Predictor
    predictor = Agent(
        role="Risk Predictor Agent",
        goal="Forecast trajectory and calculate time-to-danger over the next 10 minutes",
        backstory="You read computed risk scores, mathematical derivatives, and rising sensor trends to project environmental escalation.",
        llm=llm, allow_delegation=False,
    )

    # Agent 4: Pediatric Safety Knowledge
    safety = Agent(
        role="Safety Knowledge Agent",
        goal="Judge severity using pediatric safety guidelines based on child age and adult presence",
        backstory="You know indoor child safety rules and medical thresholds for infants and toddlers.",
        llm=llm, allow_delegation=False,
    )

    # Agent 5: Parent Action Planner
    planner = Agent(
        role="Parent Action Planner Agent",
        goal="Write the synthesized assessment and clear, immediate steps for the caregiver",
        backstory="You turn multi-agent analyses into short, calm, actionable parental advice.",
        llm=llm, allow_delegation=False,
    )

    # Agent 6: Caregiver Emergency Dispatcher & Siren Specialist
    dispatcher = Agent(
        role="Emergency Dispatcher & Siren Agent",
        goal="Determine siren alarms, notify family/caregiver emergency contacts, and draft urgent SMS dispatch alerts",
        backstory="You are an automated child-safety coordinator. You decide if an audible phone siren alarm must sound on the monitoring phone, whether the family/caregiver emergency contact should be called, and compose pre-formatted emergency SMS alerts to inform parents or guardians.",
        llm=llm, allow_delegation=False,
    )

    t1 = Task(
        description=(
            f"Physical telemetry: Light: {phone.get('light_lux', 0)} lux (pediatric standards: <5 lux indicates peaceful bedtime/nap darkness which is completely safe for infant sleep; 10-300 lux is normal daytime nursery; >1200 lux is intense direct glare), "
            f"Tilt: {sensors.get('tilt_deg', 0)} deg (normal straight upright or angled nursery stand monitoring is 60-110 deg; only >115 deg indicates a fallen or toppled device), Motion: {sensors.get('motion_ms2', 0)} m/s2, "
            f"Battery temp: {phone.get('battery_temp_c', 0)} C, Charging: {phone.get('charging', False)}. "
            "In 2 sentences, describe the physical state of the room and device."
        ),
        expected_output="Two short sentences about physical room conditions.",
        agent=perception,
        async_execution=True,
    )

    camera_active = phone.get("camera_active", True)
    camera_blocked = phone.get("camera_blocked", False) if camera_active else False
    child_cry = sensors.get("child_cry_detected", False)
    pitch_hz = sensors.get("pitch_hz", 0)
    cam_desc = (
        "Camera observation is ACTIVE and clear." if (camera_active and not camera_blocked)
        else ("Camera observation was intentionally DISABLED by parent (this is normal, NOT an obstruction)." if not camera_active
        else "Camera lens is physically OBSTRUCTED/BLOCKED.")
    )
    t2 = Task(
        description=(
            f"Child Age: {reading.get('child_age', 1)} years old. Room: {reading.get('room', 'nursery')}. "
            f"Adult Present: {reading.get('adult_present', False)}. Acoustic Volume: {noise_db} dB, Pitch: {pitch_hz} Hz. "
            f"Infant Cry Frequency Classifier: {child_cry}. "
            f"Camera Observation State: {cam_desc}. "
            "Pediatric acoustic standards (AAP/WHO): Vigorous infant distress crying is characterized by high pitch (350-800 Hz) with volume >75 dB. "
            "Adult conversation (100-250 Hz) and ambient room noise (<68 dB) are normal background sounds and MUST NOT trigger distress alarms. "
            "IMPORTANT: If camera observation is disabled by the parent, do NOT claim the lens is dirty or obstructed, and do NOT tell the parent to clear lens obstruction. "
            "Evaluate child acoustic distress, supervision status, and camera visibility. Summarize in 2 sentences."
        ),
        expected_output="Two sentences assessing child distress, supervision status, and camera visibility.",
        agent=child_monitor,
        async_execution=True,
    )

    t3 = Task(
        description=(
            "Computed numerical analysis (trust these numbers): "
            f"{json.dumps(feats)}. Predict what will happen in the next 10 minutes. "
            "Explicitly mention minutes_to_danger for any rising sensor."
        ),
        expected_output="A short forecast of 2 to 3 sentences.",
        agent=predictor,
        async_execution=True,
    )

    t4 = Task(
        description=(
            f"Guidelines:\n{GUIDELINES}\n\nJudge how serious this situation is for a child aged "
            f"{reading.get('child_age', 1)} with adult present = {reading.get('adult_present', False)}."
        ),
        expected_output="A short severity judgement of 2 sentences.",
        agent=safety,
        async_execution=True,
    )

    t5 = Task(
        description=(
            f"Synthesize findings from perception, child monitor, predictor, and safety agents. "
            f"risk_score must equal {feats['overall_risk']}. "
            f"risk_level MUST be strictly '{hint}'. When overall_risk is 0.0, risk_level is 'low'. "
            "Draft one concise, plain sentence for prediction, one for reason, and one for parent action. "
            "Never tell parents to remove a child from a safe nursery when no hazards exist. "
            "The prediction must state minutes_to_danger of the fastest-rising sensor if there is one."
        ),
        expected_output="A concise summary of prediction, reason, and recommended action.",
        agent=planner,
        context=[t1, t2, t3, t4],
    )

    t6 = Task(
        description=(
            f"Review the full hazard assessment and determine emergency response escalation. "
            f"Set risk_score={feats['overall_risk']} and risk_level='{hint}'. "
            "CRITICAL RULE FOR ALARM: alarm_sound MUST be strictly False unless risk_level is 'high'. "
            "If risk_level is 'low' or 'medium', alarm_sound MUST BE False. "
            "Only set alarm_sound=True when risk_level is 'high'. "
            "Set emergency_call_recommended=True ONLY if risk_level is 'high'; otherwise False. "
            "Set alert_title to '🚨 URGENT: DANGER DETECTED' for high risk, '⚠️ CAUTION: NURSERY ATTENTION' for medium, or '✅ NURSERY SAFE' for low. "
            f"If risk is high, write a concise emergency_sms_content (under 160 chars) suitable for SMS to family emergency contacts (e.g., 'EMERGENCY: High hazard detected near child in nursery! Sound: {noise_db}dB. Please check immediately!'). If low or medium, set emergency_sms_content to empty string. "
            "Include prediction, reason, action, confidence (<= 0.9), alarm_sound, emergency_call_recommended, alert_title, emergency_sms_content."
        ),
        expected_output="Final structured emergency decision with alarm, call, and SMS dispatch flags.",
        agent=dispatcher,
        context=[t2, t5],
        output_pydantic=Decision,
    )

    crew = Crew(
        agents=[perception, child_monitor, predictor, safety, planner, dispatcher],
        tasks=[t1, t2, t3, t4, t5, t6],
        process=Process.sequential,
        verbose=False,
    )
    result = crew.kickoff()
    return result.pydantic.model_dump()
