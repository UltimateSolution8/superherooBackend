ALTER TABLE tasks
  ADD COLUMN buyer_rating NUMERIC(3,2),
  ADD COLUMN buyer_rating_comment TEXT,
  ADD COLUMN buyer_rated_at TIMESTAMPTZ,
  ADD COLUMN helper_rating NUMERIC(3,2),
  ADD COLUMN helper_rating_comment TEXT,
  ADD COLUMN helper_rated_at TIMESTAMPTZ;
