CREATE TABLE IF NOT EXISTS training_materials (
  id UUID PRIMARY KEY,
  title VARCHAR(180) NOT NULL,
  description TEXT,
  content_type VARCHAR(24) NOT NULL,
  resource_url TEXT NOT NULL,
  thumbnail_url TEXT,
  duration_seconds INTEGER,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_training_materials_active_created
  ON training_materials (is_active, created_at DESC);

CREATE TABLE IF NOT EXISTS helper_training_progress (
  id UUID PRIMARY KEY,
  material_id UUID NOT NULL REFERENCES training_materials(id) ON DELETE CASCADE,
  helper_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
  progress_percent INTEGER NOT NULL DEFAULT 0,
  viewed_seconds INTEGER NOT NULL DEFAULT 0,
  last_accessed_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_helper_material UNIQUE (material_id, helper_id),
  CONSTRAINT ck_helper_training_progress_percent CHECK (progress_percent >= 0 AND progress_percent <= 100),
  CONSTRAINT ck_helper_training_viewed_seconds CHECK (viewed_seconds >= 0)
);

CREATE INDEX IF NOT EXISTS idx_helper_training_progress_helper
  ON helper_training_progress (helper_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_helper_training_progress_material
  ON helper_training_progress (material_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS learning_assessments (
  id UUID PRIMARY KEY,
  title VARCHAR(180) NOT NULL,
  description TEXT,
  instructions TEXT,
  max_attempts INTEGER NOT NULL DEFAULT 1,
  time_limit_minutes INTEGER,
  pass_percentage INTEGER NOT NULL DEFAULT 60,
  question_schema JSONB NOT NULL,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_learning_assessments_max_attempts CHECK (max_attempts >= 1 AND max_attempts <= 20),
  CONSTRAINT ck_learning_assessments_time_limit CHECK (time_limit_minutes IS NULL OR (time_limit_minutes >= 1 AND time_limit_minutes <= 240)),
  CONSTRAINT ck_learning_assessments_pass CHECK (pass_percentage >= 0 AND pass_percentage <= 100)
);

CREATE INDEX IF NOT EXISTS idx_learning_assessments_active_created
  ON learning_assessments (is_active, created_at DESC);

CREATE TABLE IF NOT EXISTS helper_assessment_attempts (
  id UUID PRIMARY KEY,
  assessment_id UUID NOT NULL REFERENCES learning_assessments(id) ON DELETE CASCADE,
  helper_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  attempt_no INTEGER NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'IN_PROGRESS',
  answers_json JSONB,
  score_percentage INTEGER,
  correct_count INTEGER,
  total_count INTEGER,
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  submitted_at TIMESTAMPTZ,
  duration_seconds INTEGER,
  metadata_json JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_helper_assessment_attempt UNIQUE (assessment_id, helper_id, attempt_no),
  CONSTRAINT ck_helper_assessment_attempt_no CHECK (attempt_no >= 1),
  CONSTRAINT ck_helper_assessment_score CHECK (score_percentage IS NULL OR (score_percentage >= 0 AND score_percentage <= 100)),
  CONSTRAINT ck_helper_assessment_duration CHECK (duration_seconds IS NULL OR duration_seconds >= 0)
);

CREATE INDEX IF NOT EXISTS idx_helper_assessment_attempts_helper
  ON helper_assessment_attempts (helper_id, assessment_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_helper_assessment_attempts_assessment
  ON helper_assessment_attempts (assessment_id, created_at DESC);
