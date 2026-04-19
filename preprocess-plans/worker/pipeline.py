import asyncio
import logging
import time
from uuid import uuid4

from gemini_client import call_gemini
from parser import parse_gemini_response, validate_hazards
from prompt import PROMPT_VERSION, build_prompt

logger = logging.getLogger(__name__)

MAX_RETRIES = 1
RETRY_DELAY_S = 3
MAX_TOTAL_BYTES = 100 * 1024 * 1024  # 100MB MVP limit

# TODO: Production — when total document size exceeds single Gemini call limits
# (~20MB inline bytes or ~1M tokens), implement chunked processing:
#   1. Sort documents by relevance (pre-plans first, then floor plans, then photos)
#   2. Split into chunks that fit within Gemini's context window
#   3. Call Gemini once per chunk with a partial-extraction prompt
#   4. Merge partial results into a unified pre_plan_summary and known_hazards
#   5. Run a final Gemini call to reconcile and deduplicate merged hazards
# Do NOT build this now. The MVP cap of 20 files / 100MB fits in one call.


async def process_building(supabase, building_id: str):
    run_id = str(uuid4())
    started_at = time.time()

    try:
        # 1. Mark processing
        await supabase.table("buildings").update(
            {"status": "processing", "error_message": None}
        ).eq("building_id", building_id).execute()

        # 2. Log run start
        await supabase.table("preprocessing_runs").insert(
            {
                "id": run_id,
                "building_id": building_id,
                "status": "started",
                "prompt_version": PROMPT_VERSION,
            }
        ).execute()

        # 3. Fetch document records
        result = await (
            supabase.table("site_documents")
            .select("*")
            .eq("building_id", building_id)
            .execute()
        )
        docs = result.data

        if not docs:
            raise ValueError("No documents uploaded for this building")

        # 4. Download document bytes from storage
        doc_payloads = []
        total_size = 0
        for doc in docs:
            file_bytes = await supabase.storage.from_("site-documents").download(
                doc["storage_path"]
            )
            total_size += len(file_bytes)
            if total_size > MAX_TOTAL_BYTES:
                raise ValueError(
                    f"Total document size ({total_size // (1024 * 1024)}MB) exceeds "
                    f"MVP limit of {MAX_TOTAL_BYTES // (1024 * 1024)}MB"
                )
            doc_payloads.append(
                {
                    "bytes": file_bytes,
                    "mime_type": doc["file_type"],
                    "name": doc["file_name"],
                    "id": doc["id"],
                }
            )

        # 5. Build prompt and call Gemini (with retry)
        building_result = await (
            supabase.table("buildings")
            .select("address")
            .eq("building_id", building_id)
            .single()
            .execute()
        )
        building_row = building_result.data

        prompt = build_prompt(building_row["address"])

        response = None
        last_error = None
        for attempt in range(MAX_RETRIES + 1):
            try:
                response = await call_gemini(prompt, doc_payloads)
                break
            except Exception as e:
                last_error = e
                logger.warning(
                    f"Gemini attempt {attempt + 1} failed for {building_id}: {e}"
                )
                if attempt < MAX_RETRIES:
                    await asyncio.sleep(RETRY_DELAY_S)

        if response is None:
            raise last_error  # type: ignore[misc]

        # 6. Parse and validate
        result = parse_gemini_response(response)
        validate_hazards(result["known_hazards"])

        # 7. Build floor_plans URL array from site_documents
        floor_plan_urls = []
        for doc in docs:
            signed = await supabase.storage.from_("site-documents").create_signed_url(
                doc["storage_path"], 60 * 60 * 24 * 365  # 1 year
            )
            floor_plan_urls.append(signed["signedURL"])

        # 8. Write results — status goes to 'pending_review', NOT 'ready'
        duration_ms = int((time.time() - started_at) * 1000)

        await supabase.table("buildings").update(
            {
                "status": "pending_review",
                "reviewed_status": "pending_review",
                "pre_plan_summary": result["pre_plan_summary"],
                "known_hazards": result["known_hazards"],
                "floor_plans": floor_plan_urls,
                "error_message": None,
            }
        ).eq("building_id", building_id).execute()

        # 9. Log run completion
        usage = getattr(response, "usage_metadata", None)
        await supabase.table("preprocessing_runs").update(
            {
                "status": "completed",
                "completed_at": "now()",
                "duration_ms": duration_ms,
                "model_used": "gemini-2.5-flash",
                "input_tokens": getattr(usage, "prompt_token_count", None),
                "output_tokens": getattr(usage, "candidates_token_count", None),
                "raw_response": response.text,
            }
        ).eq("id", run_id).execute()

        logger.info(f"Building {building_id} processed in {duration_ms}ms -> pending_review")

    except Exception as e:
        duration_ms = int((time.time() - started_at) * 1000)
        error_msg = str(e)[:500]

        await supabase.table("buildings").update(
            {"status": "failed", "error_message": error_msg}
        ).eq("building_id", building_id).execute()

        await supabase.table("preprocessing_runs").update(
            {
                "status": "failed",
                "completed_at": "now()",
                "duration_ms": duration_ms,
                "error_type": type(e).__name__,
                "error_message": error_msg,
            }
        ).eq("id", run_id).execute()

        logger.error(f"Building {building_id} failed: {e}")
