CREATE TABLE ai_conversations(id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES users(id), state JSONB NOT NULL DEFAULT '{}', revision BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE ai_turns(id BIGSERIAL PRIMARY KEY, conversation_id VARCHAR(36) NOT NULL REFERENCES ai_conversations(id), role VARCHAR(10) NOT NULL CHECK(role IN ('user','assistant')), content TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX conversation_owner ON ai_conversations(owner_id,updated_at DESC);
CREATE INDEX turn_conversation ON ai_turns(conversation_id,id);
CREATE TABLE scheduling_policy(id INT PRIMARY KEY CHECK(id=1), settings JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
INSERT INTO scheduling_policy VALUES(1,'{"weekdays":[1,2,3,4,5],"work_start":"09:00","work_end":"18:00","step_minutes":30,"horizon_days":7,"holidays":[],"workdays":[]}',now());
