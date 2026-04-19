import json

REQUIRED_HAZARD_FIELDS = {"id", "type", "name", "location", "confidence"}
VALID_HAZARD_TYPES = {"chemical", "radiological", "structural", "biological", "electrical"}
VALID_CONFIDENCE = {"high", "medium", "low"}


def parse_gemini_response(response) -> dict:
    """Parse and validate the two-artifact Gemini response."""
    text = response.text
    result = json.loads(text)

    if "pre_plan_summary" not in result:
        raise ValueError("Gemini response missing 'pre_plan_summary'")
    if "known_hazards" not in result:
        raise ValueError("Gemini response missing 'known_hazards'")
    if not isinstance(result["pre_plan_summary"], str):
        raise ValueError("pre_plan_summary must be a string")
    if len(result["pre_plan_summary"]) < 50:
        raise ValueError(
            f"pre_plan_summary suspiciously short ({len(result['pre_plan_summary'])} chars)"
        )

    return result


def validate_hazards(hazards_obj: dict):
    """Validate the known_hazards JSON structure."""
    if not isinstance(hazards_obj, dict):
        raise ValueError(f"known_hazards must be an object, got {type(hazards_obj)}")
    if "hazards" not in hazards_obj:
        raise ValueError("known_hazards missing 'hazards' array")
    if "summary" not in hazards_obj:
        raise ValueError("known_hazards missing 'summary'")

    for i, h in enumerate(hazards_obj["hazards"]):
        missing = REQUIRED_HAZARD_FIELDS - set(h.keys())
        if missing:
            raise ValueError(f"Hazard {i} missing required fields: {missing}")
        if h["type"] not in VALID_HAZARD_TYPES:
            raise ValueError(
                f"Hazard {i} has invalid type '{h['type']}'. Valid: {VALID_HAZARD_TYPES}"
            )
        if h["confidence"] not in VALID_CONFIDENCE:
            raise ValueError(
                f"Hazard {i} has invalid confidence '{h['confidence']}'. Valid: {VALID_CONFIDENCE}"
            )
