package com.example.wearableai.shared

object ModelConfig {
    // Active local-inference model: Gemma 3 1B instruction-tuned (~724 MB int4 mmap).
    // The larger Gemma 3n E2B (~4.4 GB) fit on-device but triggered system-wide
    // memory thrashing — lmkd reclaimed a dozen unrelated apps and Cactus inference
    // stalled indefinitely under mmap pressure. Gemma 3 1B has no native function-
    // call template either, but combined with a <tool_call> XML prompt contract and
    // a regex fallback in VoiceAgent it produces parseable tool calls reliably and
    // leaves the phone usable.
    const val GEMMA3_1B_DIR = "gemma-3-1b-it"
    const val GEMMA3_1B_HF_REPO = "Cactus-Compute/gemma-3-1b-it"

    // Legacy: Gemma 3n E2B - multimodal matformer, tagged 'tools' but no template
    // support and too big to run practically on this device.
    const val GEMMA3N_E2B_DIR = "gemma-3n-e2b-it"
    const val GEMMA3N_E2B_HF_REPO = "Cactus-Compute/gemma-3n-E2B-it"

    // Legacy: FunctionGemma 270M was tried after Gemma 4 E2B OOM'd. Too small —
    // hit safety refusals on fire-inspection language and failed to obey the
    // multi-job prompt. Constants kept for historical reference; not used.
    const val FUNCTIONGEMMA_DIR = "functiongemma-270m-it"
    const val FUNCTIONGEMMA_HF_REPO = "Cactus-Compute/functiongemma-270m-it"

    // Legacy: Gemma 4 E2B was the original local model (multimodal). Kept on disk and as
    // a constant for the debug toggle / fallback. Not loaded by default — it OOMs the device.
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

    // Local-path options. `tool_rag_top_k:32` effectively disables Cactus's
    // embedding-based tool-RAG filter (32 >> our 5 tools) — without this it
    // prunes down to top-2 by query similarity and reliably drops `add_note`,
    // leaving the model unable to record observations. Lower max_tokens keeps a
    // 270M model from rambling past its tool call into paraphrased prose.
    const val COMPLETION_OPTIONS_LOCAL = """{"max_tokens":128,"temperature":0.2,"tool_rag_top_k":32}"""
}
