-- Saved addresses were device-only, in the app's AsyncStorage. A reinstall lost
-- them, a second device never saw them, and nothing server-side could use them --
-- not the create-task prefill, not a Play reviewer starting from a clean install.
CREATE TABLE IF NOT EXISTS saved_addresses (
  id            UUID PRIMARY KEY,
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  label         VARCHAR(40) NOT NULL,
  address_text  VARCHAR(400) NOT NULL,
  lat           DOUBLE PRECISION NOT NULL,
  lng           DOUBLE PRECISION NOT NULL,
  landmark      VARCHAR(200),
  is_default    BOOLEAN NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_saved_address_lat CHECK (lat BETWEEN -90 AND 90),
  CONSTRAINT ck_saved_address_lng CHECK (lng BETWEEN -180 AND 180)
);

-- The list view, newest default first.
CREATE INDEX IF NOT EXISTS idx_saved_addresses_user
  ON saved_addresses(user_id, is_default DESC, created_at DESC);

-- One default per user, enforced by the database rather than by whichever code
-- path happened to write last. A partial unique index is the only way to say
-- "at most one row where is_default" in Postgres.
CREATE UNIQUE INDEX IF NOT EXISTS uq_saved_addresses_one_default
  ON saved_addresses(user_id) WHERE is_default;

-- The same address saved twice is a mistake, not a feature. Case-insensitive so
-- "Madhapur" and "madhapur" collide the way a person would expect.
CREATE UNIQUE INDEX IF NOT EXISTS uq_saved_addresses_user_label
  ON saved_addresses(user_id, lower(label));
