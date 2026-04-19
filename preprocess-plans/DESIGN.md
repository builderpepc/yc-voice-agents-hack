# FireSight Preprocessing — System Design v1 (Locked)

All five clarifying questions have been answered. Schema, review gate, document limits, deployment target, and auth model are locked.

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          ADMIN WEB UI (Next.js)                             │
│                                                                             │
│  ┌──────────────┐  ┌──────────────────┐  ┌──────────────────────────────┐  │
│  │ Upload Form   │  │ Building List     │  │ Review Screen               │  │
│  │ (address +    │  │ (status, brief   │  │ (brief + hazards preview,   │  │
│  │  documents)   │  │  preview, docs)  │  │  approve / reject buttons)  │  │
│  └──────┬───────┘  └────────▲─────────┘  └────────▲─────────────────────┘  │
│         │                   │                      │                         │
└─────────┼───────────────────┼──────────────────────┼─────────────────────────┘
          │ upload docs +     │ Realtime             │ Realtime
          │ insert row        │ subscription         │ subscription
          │                   │                      │
┌─────────▼───────────────────┴──────────────────────┴─────────────────────────┐
│                            SUPABASE                                          │
│                                                                              │
│  ┌──────────────┐  ┌───────────────┐  ┌───────────┐  ┌────────────────────┐ │
│  │   Storage     │  │   buildings   │  │   site_   │  │ preprocessing_runs │ │
│  │  (site-docs   │  │   (PRD        │  │ documents │  │ (audit log)        │ │
│  │   bucket)     │  │   contract    │  │           │  │                    │ │
│  │               │  │   + ops cols) │  │           │  │                    │ │
│  └──────▲───────┘  └──────▲────────┘  └─────▲─────┘  └───────▲────────────┘ │
│         │                 │                 │                  │              │
│         │            Realtime               │                  │              │
│         │            (INSERT/UPDATE          │                  │              │
│         │             status='pending')      │                  │              │
└─────────┼─────────────────┼─────────────────┼──────────────────┼──────────────┘
          │                 │                 │                  │
┌─────────┼─────────────────▼─────────────────┼──────────────────┼──────────────┐
│         │     PYTHON WORKER (Cloud Run)     │                  │              │
│         │     min=1, max=3, 2GB/1vCPU       │                  │              │
│  ┌──────┴───────────────────────────────────┴──────────────────┴──────────┐   │
│  │  1. Receive Realtime event (new pending building)                      │   │
│  │  2. Set status → 'processing'                                          │   │
│  │  3. Fetch site_documents rows for building_id                          │   │
│  │  4. Download docs from Storage (max 20 files, 100MB total)             │   │
│  │  5. Send ALL docs to Gemini in ONE call ──────────────────────┐        │   │
│  │  6. Parse response → pre_plan_summary (text) + known_hazards (JSON)    │   │
│  │  7. Write summary + hazards + floor_plan URLs to buildings row │        │   │
│  │  8. Insert preprocessing_run record                            │        │   │
│  │  9. Set status → 'pending_review'                              │        │   │
│  └────────────────────────────────────────────────────────────────┘        │   │
│                                               │                             │
└───────────────────────────────────────────────┼─────────────────────────────┘
                                                │
                                     ┌──────────▼──────────┐
                                     │   GEMINI 2.5 FLASH  │
                                     │   (google-genai)     │
                                     │                      │
                                     │  Input: all docs as  │
                                     │  multimodal parts +  │
                                     │  structured prompt   │
                                     │                      │
                                     │  Output:             │
                                     │  - pre_plan_summary  │
                                     │  - known_hazards     │
                                     └──────────────────────┘

                    ┌─────────────────────────────────────┐
                    │       ADMIN REVIEW GATE              │
                    │                                      │
                    │  Admin reads brief + hazards in UI   │
                    │  ┌─────────┐    ┌──────────┐        │
                    │  │ Approve │    │ Reject   │        │
                    │  └────┬────┘    └────┬─────┘        │
                    │       │              │               │
                    │  status→'ready'  status→'rejected'  │
                    │  reviewed_at=now  reviewed_at=now    │
                    │  reviewed_by=uid  reviewed_by=uid    │
                    └───────┼──────────────┼───────────────┘
                            │              │
                   ─ ─ ─ ─ ─▼─ DOWNSTREAM (out of scope) ─ ─ ─

┌──────────────────────────────────────────────────────────────────────────────┐
│  ON-SITE WALKTHROUGH AGENT  /  INCIDENT COMMANDER AGENT                     │
│                                                                              │
│  Query: SELECT pre_plan_summary, known_hazards, floor_plans                  │
│         FROM buildings                                                       │
│         WHERE building_id = $1 AND status = 'ready'                          │
│                                                                              │
│  Only 'ready' buildings (admin-approved) serve as Static Knowledge.          │
│  Joins on buildings.building_id for on_site_logs, post_incident_debriefs.   │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Status lifecycle

```
pending → processing → pending_review → ready       (happy path)
                     → failed                        (Gemini/parse error)
                        pending_review → rejected    (admin rejects)
failed → pending                                     (admin retries)
rejected → pending                                   (admin retries with new docs)
```

Only `ready` buildings are visible to downstream consumers. This is the life-safety gate.

### Why this shape

**Gemini handles preprocessing end-to-end** because:
1. Gemini 2.5 Flash natively understands PDFs, images, and mixed document layouts — no OCR/extraction layer needed.
2. One call = one failure surface. Multi-step local processing (PyMuPDF → chunk → embed → synthesize) adds complexity, latency, and failure modes with zero quality upside when budget is unconstrained.
3. The summary is generated *here* rather than in the on-site pipeline because it represents **static knowledge** that doesn't change between incidents. Generating it once at upload time and caching it avoids redundant API calls and ensures every consumer gets the same canonical brief.

**Supabase Realtime over polling** because:
1. Sub-second latency from upload to worker pickup.
2. No cron job to manage. The worker is a long-running process that reacts to events.
3. The same Realtime channel powers the UI's status updates — one mechanism, two consumers.

**Python worker on Cloud Run** (not a Next.js API route or Edge Function) because:
1. Gemini calls with large document payloads can take 30-120s. Supabase Edge Functions have timeout limits that cannot accommodate this. Cloud Run supports up to 60-minute request timeouts.
2. Python has first-class `google-genai` SDK support with streaming, retry, and multimodal upload helpers.
3. Clean separation: Next.js owns the UI, Python owns the intelligence pipeline. No shared runtime to debug.
4. Cloud Run with min_instances=1 keeps the Realtime connection alive without cold-start delays. max_instances=3 handles burst uploads.

---

## 2. Data Model

### 2.1 `buildings` table

The **PRD contract columns** are: `building_id`, `address`, `floor_plans`, `pre_plan_summary`, `known_hazards`, `created_at`, `updated_at`. These are the columns downstream pipelines join against. No additional data columns.

Operational columns (`status`, `error_message`, review fields) support the preprocessing workflow and are not part of the downstream contract.

```sql
CREATE TABLE buildings (
  -- ===== PRD CONTRACT (do not add data columns outside this set) =====
  building_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  address           TEXT NOT NULL,
  floor_plans       TEXT[] DEFAULT '{}',          -- array of signed/public storage URLs
  pre_plan_summary  TEXT,                         -- concise TTS-optimized operational brief
  known_hazards     JSONB,                        -- structured hazard records (see §2.4)
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- ===== OPERATIONAL (preprocessing workflow, not part of downstream contract) =====
  status            TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'processing', 'pending_review', 'ready', 'failed', 'rejected')),
  error_message     TEXT,                         -- populated on failure, shown in admin UI

  -- review gate (life safety)
  reviewed_status   TEXT DEFAULT 'pending_review'
                    CHECK (reviewed_status IN ('pending_review', 'approved', 'rejected')),
  reviewed_at       TIMESTAMPTZ,                  -- set when admin approves or rejects
  reviewed_by       UUID REFERENCES auth.users(id),

  -- ownership
  created_by        UUID REFERENCES auth.users(id)
);

-- Auto-update updated_at on every write
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER buildings_updated_at
  BEFORE UPDATE ON buildings
  FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- Enable Realtime for worker subscription and UI status updates
ALTER PUBLICATION supabase_realtime ADD TABLE buildings;
```

### 2.2 `site_documents` table

Tracks every uploaded source document. Separate from `buildings.floor_plans` (which holds the URL array for the contract) — this table is the internal record of what was uploaded and processed.

```sql
CREATE TABLE site_documents (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  building_id     UUID NOT NULL REFERENCES buildings(building_id) ON DELETE CASCADE,

  file_name       TEXT NOT NULL,                -- original filename
  file_type       TEXT NOT NULL,                -- MIME type: application/pdf, image/jpeg, etc.
  file_size_bytes INTEGER,
  storage_path    TEXT NOT NULL,                -- path within the site-documents bucket

  -- audit
  uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  uploaded_by     UUID REFERENCES auth.users(id)
);

CREATE INDEX idx_site_documents_building ON site_documents(building_id);
```

### 2.3 `preprocessing_runs` table

Audit log of every Gemini processing attempt. Supports retry visibility and debugging.

```sql
CREATE TABLE preprocessing_runs (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  building_id     UUID NOT NULL REFERENCES buildings(building_id) ON DELETE CASCADE,

  status          TEXT NOT NULL CHECK (status IN ('started', 'completed', 'failed')),
  started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at    TIMESTAMPTZ,
  duration_ms     INTEGER,

  -- Gemini call metadata
  model_used      TEXT,                         -- e.g. "gemini-2.5-flash"
  prompt_version  TEXT,                         -- version tag for the prompt template
  input_tokens    INTEGER,
  output_tokens   INTEGER,

  -- failure info
  error_type      TEXT,                         -- e.g. "gemini_timeout", "parse_error", "validation_error"
  error_message   TEXT,

  -- raw response for debugging (optional, can be large)
  raw_response    TEXT,

  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_preprocessing_runs_building ON preprocessing_runs(building_id);
```

### 2.4 `known_hazards` JSON structure

Stored in `buildings.known_hazards`. The `pre_plan_summary` is free-form text that covers everything (Knox boxes, sprinklers, hydrants, access points, utilities, contacts, hazard summary). The `known_hazards` column is the structured, machine-readable hazard data for programmatic agent reasoning.

```jsonc
{
  "hazards": [
    {
      "id": "haz_001",
      "type": "chemical",               // chemical | radiological | structural | biological | electrical
      "name": "Anhydrous ammonia",
      "location": "Basement mechanical room, NW corner",
      "quantity": "500 lbs",
      "nfpa_704": {
        "health": 3,
        "flammability": 1,
        "instability": 0,
        "special": ""
      },
      "mitigation_notes": "Requires SCBA. Evacuate downwind. Do not apply water directly.",
      "source_document": "prior_preplan_2024.pdf",  // traceability to uploaded filename
      "confidence": "high"                           // high | medium | low — Gemini's self-assessment
    },
    {
      "id": "haz_002",
      "type": "structural",
      "name": "Lightweight truss roof",
      "location": "Entire roof assembly",
      "quantity": null,
      "nfpa_704": null,
      "mitigation_notes": "Collapse risk under fire load. Defensive operations only after 10 minutes of uncontrolled fire.",
      "source_document": null,
      "confidence": "medium"
    }
  ],
  "summary": "2 hazards: 1 chemical (anhydrous ammonia, basement), 1 structural (lightweight truss roof). See individual records for mitigation.",
  "extracted_at": "2026-04-18T14:30:00Z"
}
```

### 2.5 Downstream join design

```sql
-- Future: on-site walkthrough logs
CREATE TABLE on_site_logs (
  id           UUID PRIMARY KEY,
  building_id  UUID NOT NULL REFERENCES buildings(building_id),
  incident_id  UUID,
  ...
);

-- Future: post-incident debriefs
CREATE TABLE post_incident_debriefs (
  id           UUID PRIMARY KEY,
  building_id  UUID NOT NULL REFERENCES buildings(building_id),
  incident_id  UUID NOT NULL,
  ...
);
```

`buildings.building_id` is the stable anchor. `pre_plan_summary` and `known_hazards` are denormalized onto the buildings row — the agent reads one row to get all static knowledge, no joins needed.

Downstream consumers query `WHERE status = 'ready'` to ensure they only get admin-approved data.

If versioning is needed later, a `building_versions` table can snapshot the row before overwrite, keyed by `(building_id, version, created_at)`.

### 2.6 Auth schema (flexible for future RBAC)

MVP: single shared admin account via Supabase Auth email/password. The `created_by` and `reviewed_by` columns reference `auth.users(id)` — for MVP they'll point to the same user.

For production RBAC, add:

```sql
-- Future: roles table (do not build for MVP)
-- CREATE TABLE user_roles (
--   user_id  UUID REFERENCES auth.users(id),
--   role     TEXT CHECK (role IN ('admin', 'reviewer', 'read_only')),
--   PRIMARY KEY (user_id, role)
-- );
```

The current schema already references `auth.users(id)` in all the right places, so adding roles later requires only the `user_roles` table + RLS policy changes — no schema migration on `buildings`.

---

## 3. Supabase Storage

### Bucket structure

```
Bucket: site-documents (private)
  └── {building_id}/
      ├── {document_id}.pdf
      ├── {document_id}.jpg
      └── {document_id}.png
```

### Limits

- MVP: max 20 files, 100MB total per building
- Production target: 100 files, 1GB per building
- Enforce in the Next.js upload form (client-side) and validate in the upload API route (server-side)

### Access policies

```sql
-- Authenticated users can upload
CREATE POLICY "Authenticated users can upload site documents"
ON storage.objects FOR INSERT
TO authenticated
WITH CHECK (bucket_id = 'site-documents');

-- Authenticated users can read
CREATE POLICY "Authenticated users can read site documents"
ON storage.objects FOR SELECT
TO authenticated
USING (bucket_id = 'site-documents');
```

The Python worker uses the **service role key** (full access by default). The Next.js UI generates **signed URLs** (1-hour expiry) for document preview and for downstream consumers.

### `floor_plans` URL population

After upload, the worker generates signed URLs for all documents in `site_documents` for the building and writes them into `buildings.floor_plans` (the TEXT[] contract column). These URLs need periodic refresh — for MVP, regenerate on each read via the UI. For production, use public URLs with a CDN or long-lived signed URLs.

---

## 4. Python Worker Structure

### 4.1 Project layout

```
worker/
├── main.py                  # Entry point: Realtime subscription loop
├── pipeline.py              # Core preprocessing pipeline
├── gemini_client.py         # Gemini API wrapper with retry
├── prompt.py                # Prompt template and version management
├── parser.py                # Response parsing and validation
├── supabase_client.py       # Supabase client (DB + Storage)
├── config.py                # Environment config
├── Dockerfile               # Cloud Run container
├── requirements.txt
└── tests/
    ├── test_parser.py
    └── test_prompt.py
```

### 4.2 Cloud Run deployment

```dockerfile
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
CMD ["python", "main.py"]
```

Cloud Run config:
- **min_instances:** 1 (keeps Realtime connection alive)
- **max_instances:** 3 (handles burst uploads)
- **memory:** 2GB (documents loaded in memory for Gemini call)
- **cpu:** 1 vCPU
- **timeout:** 300s (accommodates large Gemini calls)

### 4.3 Realtime subscription pattern

```python
# main.py
import asyncio
import logging
from supabase import create_async_client

from config import SUPABASE_URL, SUPABASE_SERVICE_KEY
from pipeline import process_building

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

async def main():
    supabase = await create_async_client(SUPABASE_URL, SUPABASE_SERVICE_KEY)

    # Recover orphaned rows from prior crashes
    orphaned = supabase.table("buildings") \
        .select("building_id") \
        .eq("status", "processing") \
        .lt("updated_at", "now() - interval '10 minutes'") \
        .execute()
    for row in orphaned.data:
        logger.info(f"Recovering orphaned building {row['building_id']}")
        supabase.table("buildings").update({"status": "pending"}) \
            .eq("building_id", row["building_id"]).execute()

    def handle_pending(payload):
        record = payload["new"]
        if record["status"] != "pending":
            return
        building_id = record["building_id"]
        logger.info(f"Picked up building {building_id}")
        asyncio.create_task(process_building(supabase, building_id))

    # Listen for new buildings (INSERT with status='pending')
    channel = supabase.realtime.channel("buildings-watch")
    channel.on_postgres_changes(
        event="INSERT",
        schema="public",
        table="buildings",
        callback=handle_pending
    )
    # Listen for retries (UPDATE back to status='pending')
    channel.on_postgres_changes(
        event="UPDATE",
        schema="public",
        table="buildings",
        filter="status=eq.pending",
        callback=handle_pending
    )
    await channel.subscribe()

    logger.info("Worker listening for pending buildings")
    while True:
        await asyncio.sleep(60)

if __name__ == "__main__":
    asyncio.run(main())
```

### 4.4 Core pipeline

```python
# pipeline.py
import asyncio
import time
import logging
from uuid import uuid4

from gemini_client import call_gemini, total_file_size
from parser import parse_gemini_response, validate_hazards
from prompt import build_prompt, PROMPT_VERSION

logger = logging.getLogger(__name__)

MAX_RETRIES = 1
RETRY_DELAY_S = 3
MAX_TOTAL_BYTES = 100 * 1024 * 1024  # 100MB MVP limit

# TODO: Production — when total document size exceeds single Gemini call limits (~20MB of
# inline bytes, or ~1M tokens of content), implement chunked processing:
#   1. Sort documents by relevance (pre-plans first, then floor plans, then photos)
#   2. Split into chunks that fit within Gemini's context window
#   3. Call Gemini once per chunk with a partial-extraction prompt
#   4. Merge partial results into a unified pre_plan_summary and known_hazards
#   5. Run a final Gemini call to reconcile and deduplicate merged hazards
# Do NOT build this now. The MVP cap of 20 files / 100MB fits in one call for typical buildings.

async def process_building(supabase, building_id: str):
    run_id = str(uuid4())
    started_at = time.time()

    try:
        # 1. Mark processing
        supabase.table("buildings").update({
            "status": "processing",
            "error_message": None
        }).eq("building_id", building_id).execute()

        # 2. Log run start
        supabase.table("preprocessing_runs").insert({
            "id": run_id,
            "building_id": building_id,
            "status": "started",
            "prompt_version": PROMPT_VERSION
        }).execute()

        # 3. Fetch document records
        docs = supabase.table("site_documents") \
            .select("*") \
            .eq("building_id", building_id) \
            .execute().data

        if not docs:
            raise ValueError("No documents uploaded for this building")

        # 4. Download document bytes from storage
        doc_payloads = []
        total_size = 0
        for doc in docs:
            file_bytes = supabase.storage.from_("site-documents") \
                .download(doc["storage_path"])
            total_size += len(file_bytes)
            if total_size > MAX_TOTAL_BYTES:
                raise ValueError(
                    f"Total document size ({total_size // (1024*1024)}MB) exceeds "
                    f"MVP limit of {MAX_TOTAL_BYTES // (1024*1024)}MB"
                )
            doc_payloads.append({
                "bytes": file_bytes,
                "mime_type": doc["file_type"],
                "name": doc["file_name"],
                "id": doc["id"]
            })

        # 5. Build prompt and call Gemini (with retry)
        building_row = supabase.table("buildings") \
            .select("address") \
            .eq("building_id", building_id) \
            .single().execute().data

        prompt = build_prompt(building_row["address"])

        response = None
        last_error = None
        for attempt in range(MAX_RETRIES + 1):
            try:
                response = await call_gemini(prompt, doc_payloads)
                break
            except Exception as e:
                last_error = e
                logger.warning(f"Gemini attempt {attempt+1} failed for {building_id}: {e}")
                if attempt < MAX_RETRIES:
                    await asyncio.sleep(RETRY_DELAY_S)

        if response is None:
            raise last_error

        # 6. Parse and validate
        result = parse_gemini_response(response)
        validate_hazards(result["known_hazards"])

        # 7. Build floor_plans URL array from site_documents
        floor_plan_urls = []
        for doc in docs:
            signed_url = supabase.storage.from_("site-documents") \
                .create_signed_url(doc["storage_path"], 60 * 60 * 24 * 365)  # 1 year
            floor_plan_urls.append(signed_url["signedURL"])

        # 8. Write results — status goes to 'pending_review', NOT 'ready'
        duration_ms = int((time.time() - started_at) * 1000)

        supabase.table("buildings").update({
            "status": "pending_review",
            "reviewed_status": "pending_review",
            "pre_plan_summary": result["pre_plan_summary"],
            "known_hazards": result["known_hazards"],
            "floor_plans": floor_plan_urls,
            "error_message": None
        }).eq("building_id", building_id).execute()

        # 9. Log run completion
        supabase.table("preprocessing_runs").update({
            "status": "completed",
            "completed_at": "now()",
            "duration_ms": duration_ms,
            "model_used": "gemini-2.5-flash",
            "input_tokens": getattr(getattr(response, 'usage_metadata', None), 'prompt_token_count', None),
            "output_tokens": getattr(getattr(response, 'usage_metadata', None), 'candidates_token_count', None),
        }).eq("id", run_id).execute()

        logger.info(f"Building {building_id} processed in {duration_ms}ms → pending_review")

    except Exception as e:
        duration_ms = int((time.time() - started_at) * 1000)
        error_msg = str(e)[:500]

        supabase.table("buildings").update({
            "status": "failed",
            "error_message": error_msg
        }).eq("building_id", building_id).execute()

        supabase.table("preprocessing_runs").update({
            "status": "failed",
            "completed_at": "now()",
            "duration_ms": duration_ms,
            "error_type": type(e).__name__,
            "error_message": error_msg
        }).eq("id", run_id).execute()

        logger.error(f"Building {building_id} failed: {e}")
```

### 4.5 Retry and failure handling

| Scenario | Behavior |
|---|---|
| Gemini API timeout / 5xx | Retry once after 3s. If still fails → `status='failed'`, error shown in UI. |
| Gemini returns unparseable response | No retry (deterministic failure). Mark failed, log raw response. |
| Validation fails (missing required hazard fields) | Mark failed with specific validation error. |
| Total file size exceeds 100MB | Mark failed immediately with size error. Admin must reduce documents. |
| No documents uploaded | Mark failed with "no documents" error. |
| Supabase write fails | Mark failed. Worker stays alive for next event. |
| Worker process crashes | Cloud Run restarts container. On startup, orphaned `processing` rows older than 10 minutes are reset to `pending`. |

---

## 5. Gemini Prompt Design

### 5.1 Prompt template

The prompt requests exactly two outputs matching the PRD contract columns: `pre_plan_summary` (text) and `known_hazards` (JSON). All building detail — Knox boxes, sprinklers, hydrants, access, utilities, contacts — goes into the summary text since the schema has no individual structured columns for these.

```python
# prompt.py
PROMPT_VERSION = "v1.0"

def build_prompt(address: str) -> str:
    return f"""You are a fire service pre-incident planning analyst. You are analyzing source documents for the building at: {address}

Your job is to extract all operationally relevant information for firefighters responding to this building, following NFPA 1620 guidelines.

Return your response as a JSON object with exactly two top-level keys:

1. "pre_plan_summary" — A concise plain-text operational brief designed to be read aloud via text-to-speech to firefighters approaching the building. Keep it under 300 words. Use short, direct sentences. Cover these topics in this order, skipping any not found in the documents:
   - Building address (confirm: {address})
   - Construction type and occupancy classification
   - Number of floors and approximate square footage
   - Knox box location(s) and type
   - Sprinkler system type, riser location, and FDC location
   - Standpipe and fire alarm system details
   - Nearest hydrant locations and approximate distances
   - Primary and secondary access points, gate codes if known
   - Utility shutoff locations (gas, electric, water)
   - Emergency contacts (facility manager, security, owner)
   - Hazard summary (count and types — defer details to known_hazards)
   - Any special considerations: high-rise, basement access, roof access, elevators

2. "known_hazards" — A structured object containing:
   - "hazards": array of hazard records, each with:
     - "id": string, sequential like "haz_001"
     - "type": one of "chemical", "radiological", "structural", "biological", "electrical"
     - "name": string, name of the hazard
     - "location": string, specific location within the building
     - "quantity": string or null
     - "nfpa_704": object with "health", "flammability", "instability", "special" fields, or null if not applicable
     - "mitigation_notes": string, tactical guidance for firefighters
     - "source_document": string or null, the filename of the document this was extracted from
     - "confidence": "high", "medium", or "low" based on how clearly the source document states this information
   - "summary": one-sentence plain-text summary of all hazards
   - "extracted_at": ISO 8601 timestamp of when you produced this extraction

Critical instructions:
- Extract ONLY what is explicitly stated or clearly implied in the documents. Do not fabricate information. If something is not in the documents, omit it from the summary rather than guessing.
- If a document is a scanned image with poor quality, reduce confidence on affected hazards and note the quality issue in the summary.
- If no hazards are found, return an empty hazards array with summary "No hazards identified in provided documents."
- For the summary, write as if briefing a firefighter en route. They know fire service terminology — do not explain jargon.
- Every hazard MUST have a location. If the document mentions a hazard but not its location, set location to "Location not specified in source documents" and confidence to "low".
- If documents conflict (e.g., two different sprinkler types listed), note the conflict in the summary and use the most recent document's information.

Respond with ONLY the JSON object. No markdown fencing, no commentary outside the JSON."""
```

### 5.2 Gemini call

```python
# gemini_client.py
from google import genai
from google.genai import types
from config import GEMINI_API_KEY

client = genai.Client(api_key=GEMINI_API_KEY)

async def call_gemini(prompt: str, doc_payloads: list[dict]) -> types.GenerateContentResponse:
    """Send all documents + prompt to Gemini in a single multimodal call."""

    parts = []

    # Add each document as a Part with a label
    for doc in doc_payloads:
        parts.append(types.Part.from_bytes(
            data=doc["bytes"],
            mime_type=doc["mime_type"]
        ))
        parts.append(types.Part.from_text(
            f"[Document: {doc['name']}]"
        ))

    # Prompt as final part
    parts.append(types.Part.from_text(prompt))

    response = await client.aio.models.generate_content(
        model="gemini-2.5-flash",
        contents=types.Content(parts=parts),
        config=types.GenerateContentConfig(
            temperature=0.1,
            max_output_tokens=8192,
            response_mime_type="application/json",
        )
    )

    return response
```

### 5.3 Response parser

```python
# parser.py
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
            raise ValueError(f"Hazard {i} has invalid type '{h['type']}'. "
                           f"Valid: {VALID_HAZARD_TYPES}")
        if h["confidence"] not in VALID_CONFIDENCE:
            raise ValueError(f"Hazard {i} has invalid confidence '{h['confidence']}'. "
                           f"Valid: {VALID_CONFIDENCE}")
```

---

## 6. Next.js Admin UI — Key Screens

### 6.1 Upload flow

1. Admin enters building address.
2. Admin selects files (PDF, JPG, PNG). Client-side validation: max 20 files, 100MB total.
3. On submit:
   - Insert a row into `buildings` with `status='pending'`.
   - Upload each file to Supabase Storage at `site-documents/{building_id}/{document_id}.ext`.
   - Insert corresponding `site_documents` rows.
   - Worker picks up the new row via Realtime.
4. UI subscribes to Realtime changes on the building row and shows status updates live.

### 6.2 Building list

| Address | Status | Documents | Summary Preview | Reviewed | Actions |
|---|---|---|---|---|---|
| 123 Industrial Blvd | Ready | 3 files | "Building at 123..." | Apr 18 by admin | View |
| 456 Main St | Pending Review | 2 files | "Building at 456..." | — | Review |
| 789 Oak Ave | Failed | 1 file | — | — | Retry / View Error |

### 6.3 Review screen (new — life safety gate)

When a building reaches `pending_review`:
1. Admin sees the full `pre_plan_summary` text and `known_hazards` table side by side.
2. Uploaded source documents are viewable via signed URLs for cross-reference.
3. Hazards show confidence badges (high/medium/low).
4. Two buttons: **Approve** and **Reject**.
   - **Approve:** sets `status='ready'`, `reviewed_status='approved'`, `reviewed_at=now()`, `reviewed_by=current_user_id`.
   - **Reject:** sets `status='rejected'`, `reviewed_status='rejected'`, `reviewed_at=now()`, `reviewed_by=current_user_id`. Admin can add a rejection reason to `error_message`.
5. Only `ready` buildings are queryable by downstream agents.

### 6.4 Retry flow

Admin clicks "Retry" on a failed or rejected building → UI sets `status='pending'`, clears `error_message`, resets `reviewed_status='pending_review'` → worker picks it up again.

---

## 7. MVP Build Plan

### Phase 1: Foundation (Supabase setup)
- Create Supabase project
- Run SQL migrations: `buildings`, `site_documents`, `preprocessing_runs`
- Create `site-documents` storage bucket with RLS policies
- Enable Realtime on `buildings` table
- Create single admin user via Supabase Auth (email/password)
- **Verify:** Insert a building row via SQL editor, see it in dashboard, Realtime event fires

### Phase 2: Upload UI (Next.js)
- `npx create-next-app@latest` with TypeScript, App Router
- Install `@supabase/supabase-js`, `@supabase/ssr`
- Build login page (email/password against Supabase Auth)
- Build upload form: address input + multi-file picker with size validation
- On submit: insert building row → upload files to storage → insert site_documents
- Build building list page showing all buildings with status
- **Verify:** Upload documents through UI → building row appears with `status='pending'`

### Phase 3: Python Worker + Gemini
- Set up Python project with `google-genai`, `supabase`, `python-dotenv`
- Implement Realtime subscription (main.py)
- Implement Gemini call with structured prompt (gemini_client.py, prompt.py)
- Implement response parsing and validation (parser.py)
- Write `pre_plan_summary` + `known_hazards` + `floor_plans` URLs to buildings row
- Set status → `pending_review` on success
- Insert preprocessing_run records
- Dockerize for Cloud Run
- **Verify:** Upload → worker processes → summary and hazards appear in DB with status `pending_review`

### Phase 4: Review Gate + Status
- Add Realtime subscription in Next.js for live status updates
- Build review screen: summary + hazards display + approve/reject buttons
- On approve: set `status='ready'`, `reviewed_at`, `reviewed_by`
- On reject: set `status='rejected'`, admin enters reason
- Display error messages for failed buildings
- Add retry button (resets to `pending`)
- **Verify:** Full lifecycle: upload → process → review → approve → status='ready'

### Phase 5: Harden + Deploy
- Orphaned `processing` row recovery on worker startup
- Preprocessing_runs log view for debugging
- Signed URL generation for document access
- Input validation hardening (file types, sizes, server-side)
- Deploy worker to Cloud Run (min=1, max=3, 2GB, 1vCPU)
- Deploy Next.js to Vercel
- **Verify:** End-to-end with real fire department source documents

---

## 8. Limits, Assumptions, and Risks

### Assumptions

1. **Gemini 2.5 Flash handles the document mix** — PDFs, images, scanned floor plans — in one call. This is the load-bearing assumption.
2. **Source documents contain enough information.** Sparse inputs produce sparse outputs. The system handles this (thin summary, empty hazards) but output quality depends on input quality.
3. **One Gemini call fits within context window.** At 20 files / 100MB MVP cap, typical building docs (3-10 PDFs + photos) fit comfortably. The TODO for chunked calls covers the production case.
4. **Supabase Realtime is reliable at this scale.** Under 100 buildings, low event volume. Well within limits.
5. **Cloud Run min_instances=1 keeps the Realtime websocket alive.** If Cloud Run tears down the instance despite min=1 (e.g., during deploys), the orphan recovery mechanism catches missed events on restart.

### Risks

| Risk | Severity | Likelihood | Mitigation |
|---|---|---|---|
| **Gemini misses a hazard in source documents** | Critical | Medium | Confidence field + admin review gate. Only `ready` (approved) buildings reach agents. |
| **Gemini hallucinates a hazard** | Critical | Low-Medium | Temperature 0.1. Prompt: "extract ONLY what is explicitly stated." Admin review is the safety net. |
| **Admin approves a bad brief without careful review** | Critical | Low | Show confidence badges prominently. Display source documents alongside brief for easy cross-reference. |
| **Scanned documents with poor image quality** | Medium | High | Gemini notes quality issues. Confidence field surfaces uncertainty. |
| **Cloud Run Realtime connection drops** | Medium | Medium | Orphan recovery on startup. Cloud Run restart policy. |
| **Gemini API outage** | Medium | Low | Retry once. Clear error in UI. Admin retries later. |
| **Document exceeds single Gemini call limits** | Medium | Low (MVP) | 100MB cap enforced. TODO marker for chunked processing. |

### Fragile points

1. **The Gemini prompt** — small wording changes shift output quality. Version it (`PROMPT_VERSION` in preprocessing_runs) to correlate quality issues with prompt changes.
2. **JSON parsing of Gemini output** — even with `response_mime_type="application/json"`, edge cases exist. Parser is defensive with clear error messages.
3. **`pre_plan_summary` is free-form text** — no structural validation possible. A technically valid but operationally useless brief can only be caught by admin review.
4. **`floor_plans` URL expiry** — signed URLs expire. MVP uses 1-year signed URLs. Production should use public URLs or a refresh mechanism.

---

## 9. Reflections

### Why Gemini handles preprocessing end-to-end

The alternative is PyMuPDF → Tesseract → chunking → embedding → retrieval → synthesis. Five failure surfaces, three dependencies, worse output. With budget unconstrained, the trade is clear: Gemini's native multimodal understanding produces better results with simpler code.

### Most fragile assumption

**Gemini quality on messy source documents.** A pristine NFPA 1620 form → excellent summary. Blurry phone photos of a faded 1990s floor plan → mediocre. The system can't fix bad inputs, but it surfaces confidence levels, keeps raw documents accessible, and gates everything behind admin review.

### What breaks first at 10x scale (1,000 buildings)

- **Worker throughput.** 30-120s per Gemini call × 1,000 = 8-33 hours serially. Fix: add a task queue (Redis + Celery), max_instances=3 Cloud Run already helps with concurrent processing.
- **Admin review bottleneck.** 1,000 buildings each needing human review. Fix: batch review UI, auto-approve high-confidence buildings (future — requires validated confidence threshold).
- **Supabase Realtime / storage / DB** — fine at this scale on a paid plan.

### What changes if the solo developer constraint lifts

- Separate repos for Next.js UI and Python worker with independent CI/CD.
- Task queue (BullMQ or Celery) between Realtime and Gemini calls.
- Integration tests against Gemini with sample documents.
- Prompt evaluation harness scoring brief quality on a test set.
- Role-based review workflow (uploader ≠ reviewer).

### Stack conflicts or redundancies

None meaningful. Supabase (state + storage + events), Gemini (intelligence), Python worker (orchestration), Next.js (presentation) occupy distinct roles with clean interfaces. The one shared dependency is Realtime for both worker dispatch and UI updates — intentional, acceptable at this scale.

### Coupling to downstream

**Tightly coupled:** The `buildings` table contract columns (`building_id`, `address`, `floor_plans`, `pre_plan_summary`, `known_hazards`, `created_at`, `updated_at`). Changing these requires downstream migrations. Locked now.

**Loosely coupled:** Everything else — prompt, worker internals, storage layout, UI, operational columns. These can change without affecting downstream.

**Flexibility left:**
- `known_hazards` JSON structure can evolve. Downstream should parse defensively.
- `pre_plan_summary` format may change based on firefighter feedback. Downstream treats it as opaque text.
- Auth schema already references `auth.users(id)` — adding roles requires only a new table + RLS policies.

### Life safety risk surface

The sharpest risk: **a brief that is confidently wrong** — not missing (the agent says "no static knowledge") but incorrect ("no hazardous materials on site" when ammonia is in the basement).

Mitigations in order of effectiveness:
1. **Admin review gate** — only `ready` buildings reach agents. The admin sees brief + hazards + source documents.
2. **Confidence field** — agent can say "hazard data is low confidence, verify on site."
3. **Source document traceability** — every hazard cites its source filename.
4. **"No hazards found" ≠ "hazards assessed as zero"** — if documents were too poor to assess, the summary says so.
5. **Downstream agent caveat** — pre-arrival brief should always note that static knowledge is based on uploaded documents and may not reflect current conditions. (Design into agent prompt, out of scope for preprocessing.)
