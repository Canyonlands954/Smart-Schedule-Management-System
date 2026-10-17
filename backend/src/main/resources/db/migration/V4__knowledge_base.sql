CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_settings (
  id INT PRIMARY KEY CHECK(id=1),
  encrypted_payload TEXT,
  enabled BOOLEAN NOT NULL DEFAULT false,
  revision BIGINT NOT NULL DEFAULT 0,
  space_id TEXT NOT NULL DEFAULT '',
  updated_by BIGINT REFERENCES users(id),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK(NOT enabled OR encrypted_payload IS NOT NULL)
);
INSERT INTO knowledge_settings(id) VALUES(1);

CREATE TABLE knowledge_bases (
  id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 100),
  description TEXT NOT NULL DEFAULT '',
  scope TEXT NOT NULL CHECK(scope IN ('all','team','private')),
  team_id BIGINT REFERENCES teams(id),
  owner_id BIGINT NOT NULL REFERENCES users(id),
  revision BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK((scope='team' AND team_id IS NOT NULL) OR (scope<>'team' AND team_id IS NULL))
);
CREATE TABLE knowledge_documents (
  id BIGSERIAL PRIMARY KEY,
  base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  published_version_id BIGINT,
  revision BIGINT NOT NULL DEFAULT 1,
  deleted BOOLEAN NOT NULL DEFAULT false,
  created_by BIGINT NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE knowledge_grants (
  base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY(base_id,user_id)
);
CREATE TABLE knowledge_versions (
  id BIGSERIAL PRIMARY KEY,
  document_id BIGINT NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
  version INT NOT NULL,
  filename TEXT NOT NULL,
  storage_key TEXT NOT NULL UNIQUE,
  content_hash TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'queued' CHECK(status IN ('queued','processing','ready','failed')),
  effective_from DATE NOT NULL DEFAULT CURRENT_DATE,
  effective_until DATE,
  external_allowed BOOLEAN NOT NULL DEFAULT false,
  space_id TEXT NOT NULL DEFAULT '',
  chunk_count INT NOT NULL DEFAULT 0,
  created_by BIGINT NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(document_id,version),
  CHECK(effective_until IS NULL OR effective_until>=effective_from)
);
ALTER TABLE knowledge_documents ADD CONSTRAINT published_knowledge_version
  FOREIGN KEY(published_version_id) REFERENCES knowledge_versions(id);
CREATE TABLE knowledge_chunks (
  id BIGSERIAL PRIMARY KEY,
  version_id BIGINT NOT NULL REFERENCES knowledge_versions(id) ON DELETE CASCADE,
  ordinal INT NOT NULL,
  locator TEXT NOT NULL,
  content TEXT NOT NULL,
  embedding vector,
  space_id TEXT NOT NULL DEFAULT '',
  UNIQUE(version_id,ordinal)
);
CREATE INDEX knowledge_chunks_version ON knowledge_chunks(version_id);
CREATE INDEX knowledge_documents_base ON knowledge_documents(base_id);
CREATE TABLE knowledge_jobs (
  id BIGSERIAL PRIMARY KEY,
  version_id BIGINT NOT NULL UNIQUE REFERENCES knowledge_versions(id) ON DELETE CASCADE,
  requested_by BIGINT NOT NULL REFERENCES users(id),
  status TEXT NOT NULL DEFAULT 'queued' CHECK(status IN ('queued','processing','done','failed')),
  attempts INT NOT NULL DEFAULT 0,
  error TEXT NOT NULL DEFAULT '',
  available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  lease_until TIMESTAMPTZ,
  lease_token UUID,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX knowledge_jobs_queue ON knowledge_jobs(status,available_at);
CREATE TABLE knowledge_queries (
  id UUID PRIMARY KEY,
  owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  question TEXT NOT NULL,
  answer JSONB NOT NULL,
  citations JSONB NOT NULL,
  mode TEXT NOT NULL,
  retrieval_ms BIGINT NOT NULL,
  total_ms BIGINT NOT NULL,
  feedback INT CHECK(feedback IN (-1,1)),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX knowledge_queries_owner ON knowledge_queries(owner_id,created_at);
