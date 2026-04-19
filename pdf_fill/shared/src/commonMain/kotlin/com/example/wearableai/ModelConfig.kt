package com.example.wearableai.shared

object ModelConfig {
    // Cactus model directory name — download from huggingface.co/cactus-compute
    const val GEMMA4_DIR = "gemma-4-E2B-it"
    const val GEMMA4_HF_REPO = "cactus-compute/google--gemma-4-E2B-it"

    // Gemma 4 E4B supports native audio input — pass audio path in message content.
    // System prompt for the voice agent persona.
    // Transcription directive: emitting <heard>...</heard> lets us persist a textual
    // record of each user turn, since replaying audio in history corrupts Cactus's
    // prefix KV cache (see VoiceAgent.processUtterance).
    const val SYSTEM_PROMPT = """You are a helpful voice assistant running on a mobile device.
The user is speaking to you through Meta AI glasses. Be concise — responses will be spoken aloud.
Keep answers under 3 sentences unless the user asks for detail.

For every user audio turn, begin your reply with a single line of the form
<heard>verbatim transcription of what the user said</heard>
then continue with your spoken response on the next line. If the audio is silent or unintelligible,
output <heard>SILENCE</heard> and do not respond further."""

    // Gemini 2.5 Flash
    const val GEMINI_MODEL = "gemini-2.5-flash"

    // cactusComplete options for voice responses: low temperature, capped tokens
    const val COMPLETION_OPTIONS = """{"max_tokens":256,"temperature":0.5}"""
}
