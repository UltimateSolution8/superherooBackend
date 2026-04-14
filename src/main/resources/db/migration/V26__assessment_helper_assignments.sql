CREATE TABLE IF NOT EXISTS learning_assessment_assignments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  assessment_id UUID NOT NULL REFERENCES learning_assessments(id) ON DELETE CASCADE,
  helper_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_learning_assessment_assignment UNIQUE (assessment_id, helper_id)
);

CREATE INDEX IF NOT EXISTS idx_learning_assessment_assignments_helper
  ON learning_assessment_assignments (helper_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_learning_assessment_assignments_assessment
  ON learning_assessment_assignments (assessment_id, created_at DESC);
