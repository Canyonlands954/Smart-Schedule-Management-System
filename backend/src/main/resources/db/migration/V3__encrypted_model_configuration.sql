CREATE TABLE ai_model_settings (
  id INT PRIMARY KEY CHECK(id=1),
  encrypted_payload TEXT,
  enabled BOOLEAN NOT NULL DEFAULT false,
  revision BIGINT NOT NULL DEFAULT 0,
  updated_by BIGINT REFERENCES users(id),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK(NOT enabled OR encrypted_payload IS NOT NULL)
);
INSERT INTO ai_model_settings(id) VALUES(1);
