import time, json, os
from crew import level_from_score, GUIDELINES, Decision
from features import analyse
from crewai import Agent, Task, Crew, LLM, Process

reading = {
    "timestamp": "2026-10-28T18:30:00",
    "room": "kitchen",
    "child_age": 3,
    "adult_present": False,
    "phone": {"audio_class": "baby_crying", "light_lux": 120,
              "motion_level": 0.2, "image_note": "toddler near stove"},
    "simulated": {"gas_ppm": 380, "smoke_level": 0.05, "co2_ppm": 900, "temp_c": 26},
    "history": [
        {"timestamp": "2026-10-28T18:27:00", "gas_ppm": 200},
        {"timestamp": "2026-10-28T18:29:00", "gas_ppm": 290},
    ],
}

llm = LLM(model="gemini/" + os.getenv("GEMINI_MODEL"))
feats = analyse(reading)
hint = level_from_score(feats["overall_risk"])
phone = reading.get("phone", {})

perception = Agent(
    role="Perception agent",
    goal="Describe what is happening around the child from phone observations",
    backstory="You interpret sounds, camera notes, light and motion from a phone.",
    llm=llm, allow_delegation=False,
)
predictor = Agent(
    role="Risk predictor agent",
    goal="Predict what will happen in the next 10 minutes",
    backstory="You read computed risk scores and trends and forecast danger.",
    llm=llm, allow_delegation=False,
)
safety = Agent(
    role="Safety knowledge agent",
    goal="Judge severity using child safety guidelines for the child's age",
    backstory="You know indoor child safety rules.",
    llm=llm, allow_delegation=False,
)
planner = Agent(
    role="Action planner agent",
    goal="Write the final decision and clear steps for a parent",
    backstory="You turn analysis into short, calm, actionable advice.",
    llm=llm, allow_delegation=False,
)

t1 = Task(
    description=(
        f"Child age: {reading['child_age']}. Adult present: {reading['adult_present']}. "
        f"Room: {reading['room']}. Phone observations: {json.dumps(phone)}. "
        "In 2 sentences, describe what is happening around the child and any hazard."
    ),
    expected_output="Two short sentences.",
    agent=perception,
    async_execution=True,
)
t2 = Task(
    description=(
        "Computed analysis (trust these numbers): "
        f"{json.dumps(feats)}. Predict what will happen in the next 10 minutes. "
        "Mention minutes_to_danger for any rising sensor."
    ),
    expected_output="A short forecast of 2 to 3 sentences.",
    agent=predictor,
    async_execution=True,
)
t3 = Task(
    description=(
        f"Guidelines:\n{GUIDELINES}\n\nJudge how serious this is for a child aged "
        f"{reading['child_age']} with adult present = {reading['adult_present']}."
    ),
    expected_output="A short severity judgement of 2 sentences.",
    agent=safety,
    async_execution=True,
)
t4 = Task(
    description=(
        f"Write the final decision. risk_score must equal {feats['overall_risk']}. "
        f"risk_level should be '{hint}' unless there is a strong reason to differ. "
        "prediction, reason and action must each be one short sentence a parent understands. "
        "The prediction must state minutes_to_danger of the fastest-rising sensor if there is one. "
        "Only state facts present in the data. "
        "Keep confidence at 0.9 or below. "
        "confidence is a number from 0 to 1."
    ),
    expected_output="A structured decision.",
    agent=planner,
    context=[t1, t2, t3],
    output_pydantic=Decision,
)

t0 = time.time()
crew = Crew(
    agents=[perception, predictor, safety, planner],
    tasks=[t1, t2, t3, t4],
    process=Process.sequential,
    verbose=False,
)
result = crew.kickoff()
print("Elapsed:", round(time.time() - t0, 2), "s")
print(result.pydantic.model_dump())
