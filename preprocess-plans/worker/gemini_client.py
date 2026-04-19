from google import genai
from google.genai import types

from config import GEMINI_API_KEY

client = genai.Client(api_key=GEMINI_API_KEY)


async def call_gemini(
    prompt: str, doc_payloads: list[dict]
) -> types.GenerateContentResponse:
    """Send all documents + prompt to Gemini in a single multimodal call."""
    parts = []

    for doc in doc_payloads:
        parts.append(types.Part.from_bytes(data=doc["bytes"], mime_type=doc["mime_type"]))
        parts.append(types.Part.from_text(text=f"[Document: {doc['name']}]"))

    parts.append(types.Part.from_text(text=prompt))

    response = await client.aio.models.generate_content(
        model="gemini-2.5-flash",
        contents=types.Content(parts=parts),
        config=types.GenerateContentConfig(
            temperature=0.1,
            max_output_tokens=8192,
            response_mime_type="application/json",
        ),
    )

    return response
