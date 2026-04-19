-- FireSight Preprocessing — buildings table
-- PRD contract columns + operational columns for preprocessing workflow

-- ============================================================
-- buildings table
-- ============================================================
CREATE TABLE buildings (
  -- PRD CONTRACT (do not add data columns outside this set)
  building_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  address           TEXT NOT NULL,
  floor_plans       TEXT[] DEFAULT '{}',
  pre_plan_summary  TEXT,
  known_hazards     JSONB,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- OPERATIONAL (preprocessing workflow)
  status            TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'processing', 'pending_review', 'ready', 'failed', 'rejected')),
  error_message     TEXT,

  -- Review gate (life safety)
  reviewed_status   TEXT DEFAULT 'pending_review'
                    CHECK (reviewed_status IN ('pending_review', 'approved', 'rejected')),
  reviewed_at       TIMESTAMPTZ,
  reviewed_by       UUID REFERENCES auth.users(id),

  -- Ownership
  created_by        UUID REFERENCES auth.users(id)
);

-- Auto-update updated_at
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

-- Enable Realtime
ALTER PUBLICATION supabase_realtime ADD TABLE buildings;

-- ============================================================
-- site_documents table
-- ============================================================
CREATE TABLE site_documents (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  building_id     UUID NOT NULL REFERENCES buildings(building_id) ON DELETE CASCADE,

  file_name       TEXT NOT NULL,
  file_type       TEXT NOT NULL,
  file_size_bytes INTEGER,
  storage_path    TEXT NOT NULL,

  uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  uploaded_by     UUID REFERENCES auth.users(id)
);

CREATE INDEX idx_site_documents_building ON site_documents(building_id);

-- ============================================================
-- preprocessing_runs table
-- ============================================================
CREATE TABLE preprocessing_runs (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  building_id     UUID NOT NULL REFERENCES buildings(building_id) ON DELETE CASCADE,

  status          TEXT NOT NULL CHECK (status IN ('started', 'completed', 'failed')),
  started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at    TIMESTAMPTZ,
  duration_ms     INTEGER,

  model_used      TEXT,
  prompt_version  TEXT,
  input_tokens    INTEGER,
  output_tokens   INTEGER,

  error_type      TEXT,
  error_message   TEXT,
  raw_response    TEXT,

  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_preprocessing_runs_building ON preprocessing_runs(building_id);

-- ============================================================
-- Row-Level Security
-- ============================================================
ALTER TABLE buildings ENABLE ROW LEVEL SECURITY;
ALTER TABLE site_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE preprocessing_runs ENABLE ROW LEVEL SECURITY;

-- MVP: authenticated users can do everything
CREATE POLICY "Authenticated users full access on buildings"
  ON buildings FOR ALL TO authenticated
  USING (true) WITH CHECK (true);

CREATE POLICY "Authenticated users full access on site_documents"
  ON site_documents FOR ALL TO authenticated
  USING (true) WITH CHECK (true);

CREATE POLICY "Authenticated users full access on preprocessing_runs"
  ON preprocessing_runs FOR ALL TO authenticated
  USING (true) WITH CHECK (true);

-- Service role bypasses RLS by default (Python worker)

-- ============================================================
-- Storage bucket
-- ============================================================
INSERT INTO storage.buckets (id, name, public)
VALUES ('site-documents', 'site-documents', false);

CREATE POLICY "Authenticated users can upload site documents"
  ON storage.objects FOR INSERT TO authenticated
  WITH CHECK (bucket_id = 'site-documents');

CREATE POLICY "Authenticated users can read site documents"
  ON storage.objects FOR SELECT TO authenticated
  USING (bucket_id = 'site-documents');
