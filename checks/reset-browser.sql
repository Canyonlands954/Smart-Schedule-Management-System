DO $$ BEGIN IF current_database()<>'schedule_test' THEN RAISE EXCEPTION 'Only schedule_test may be reset'; END IF; END $$;
TRUNCATE users,teams,sessions,admin_invites,events,event_details,event_history,notifications,ai_drafts,event_requests,audit,rooms,migration_runs RESTART IDENTITY CASCADE;
INSERT INTO ai_model_settings(id) VALUES(1);
INSERT INTO knowledge_settings(id) VALUES(1);
INSERT INTO rooms(name,capacity) SELECT i||'号会议室',8+i*2 FROM generate_series(1,5) i;
