-- Synchronize only explicit scoped evidence links in the same ingest transaction.
CREATE FUNCTION uav_risk_sync_event(p_event varchar) RETURNS void LANGUAGE plpgsql AS $$
DECLARE e record; x record; p record; cid varchar; stamp bigint;
BEGIN
 SELECT u.*,a.target_id,a.source_mode INTO e FROM uav_event u JOIN alarm a USING(alarm_id)
 WHERE u.event_id=p_event AND a.owner_org_id=u.owner_org_id AND a.district_id=u.district_id;
 IF NOT FOUND THEN RETURN; END IF;
 stamp := floor(extract(epoch FROM e.created_at)*1000);
 INSERT INTO uav_risk_case(risk_case_id,root_event_id,target_id,owner_org_id,district_id,source_mode,created_at)
 VALUES(e.event_id,e.event_id,e.target_id,e.owner_org_id,e.district_id,e.source_mode,stamp) ON CONFLICT DO NOTHING;
 INSERT INTO uav_risk_case_member VALUES(e.event_id,e.event_id,'OBSERVED',stamp) ON CONFLICT DO NOTHING;
 FOR x IN SELECT DISTINCT airspace_id FROM prediction_actual_entry_link WHERE event_id=e.event_id AND target_id=e.target_id LOOP
   cid := md5(e.event_id||chr(31)||x.airspace_id)::uuid::text;
   INSERT INTO uav_risk_case(risk_case_id,root_event_id,airspace_id,target_id,owner_org_id,district_id,source_mode,created_at)
   VALUES(cid,e.event_id,x.airspace_id,e.target_id,e.owner_org_id,e.district_id,e.source_mode,stamp) ON CONFLICT DO NOTHING;
   INSERT INTO uav_risk_case_member VALUES(cid,e.event_id,'OBSERVED',stamp) ON CONFLICT DO NOTHING;
   UPDATE uav_risk_case SET superseded_by=cid WHERE risk_case_id=e.event_id AND superseded_by IS NULL;
 END LOOP;
 FOR p IN SELECT ep.*,l.role,l.created_at AS linked_at,pl.event_id AS predicted_event FROM prediction_event_link l
 JOIN prediction_episode ep USING(episode_id)
 JOIN prediction_event_link pl ON pl.episode_id=ep.episode_id AND pl.role='PREDICTED'
 WHERE l.event_id=e.event_id AND ep.target_id=e.target_id AND ep.owner_org_id=e.owner_org_id
 AND ep.district_id=e.district_id AND ep.source_mode=e.source_mode
 AND (l.role='PREDICTED' OR EXISTS(SELECT 1 FROM prediction_actual_entry_link ax WHERE ax.event_id=e.event_id AND ax.airspace_id=ep.airspace_id AND ax.target_id=ep.target_id)) LOOP
   INSERT INTO uav_risk_case(risk_case_id,root_event_id,episode_id,airspace_id,target_id,owner_org_id,district_id,source_mode,created_at)
   VALUES(p.episode_id,p.predicted_event,p.episode_id,p.airspace_id,p.target_id,p.owner_org_id,p.district_id,p.source_mode,p.started_at) ON CONFLICT DO NOTHING;
   INSERT INTO uav_risk_case_member VALUES(p.episode_id,p.predicted_event,'PREDICTED',p.started_at) ON CONFLICT DO NOTHING;
   INSERT INTO uav_risk_case_member VALUES(p.episode_id,e.event_id,p.role,p.linked_at) ON CONFLICT DO NOTHING;
   IF FOUND THEN UPDATE uav_risk_case SET version=version+1 WHERE risk_case_id=p.episode_id; END IF;
   UPDATE uav_risk_case SET superseded_by=p.episode_id WHERE risk_case_id=e.event_id;
   UPDATE uav_risk_case SET superseded_by=p.episode_id WHERE root_event_id=e.event_id AND episode_id IS NULL AND airspace_id=p.airspace_id;
 END LOOP;
END $$;
CREATE FUNCTION uav_risk_sync_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM uav_risk_sync_event(NEW.event_id); RETURN NEW; END $$;
CREATE TRIGGER uav_risk_event_created AFTER INSERT ON uav_event FOR EACH ROW EXECUTE FUNCTION uav_risk_sync_trigger();
CREATE TRIGGER uav_risk_actual_link AFTER INSERT ON prediction_actual_entry_link FOR EACH ROW EXECUTE FUNCTION uav_risk_sync_trigger();
CREATE TRIGGER uav_risk_prediction_link AFTER INSERT ON prediction_event_link FOR EACH ROW EXECUTE FUNCTION uav_risk_sync_trigger();
CREATE FUNCTION uav_risk_stage_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status IS DISTINCT FROM NEW.status OR OLD.active_key IS DISTINCT FROM NEW.active_key THEN
   UPDATE uav_risk_case SET version=version+1 WHERE episode_id=NEW.episode_id;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER uav_risk_stage_changed AFTER UPDATE ON prediction_episode FOR EACH ROW EXECUTE FUNCTION uav_risk_stage_trigger();
-- Historical evidence stays immutable. Only proven associations are represented together.
DO $$ DECLARE e record; BEGIN FOR e IN SELECT event_id FROM uav_event ORDER BY event_id LOOP PERFORM uav_risk_sync_event(e.event_id); END LOOP; END $$;

-- Accepted, failed and unknown historical attempts keep their original task and provider key.
INSERT INTO uav_risk_channel(risk_case_id,channel,event_id,task_scope,claimed_at)
SELECT DISTINCT ON(c.risk_case_id) c.risk_case_id,'SMS',t.event_id,t.risk_scope,COALESCE(t.triggered_at,t.updated_at)
FROM uav_risk_case c JOIN uav_risk_case_member m USING(risk_case_id) JOIN uav_auto_sms_task t ON t.event_id=m.event_id
WHERE c.superseded_by IS NULL AND t.attempt_count>0
  AND (SELECT COUNT(*) FROM uav_risk_case_member cm JOIN uav_risk_case cc USING(risk_case_id) WHERE cm.event_id=t.event_id AND cc.superseded_by IS NULL)=1
ORDER BY c.risk_case_id,COALESCE(t.triggered_at,t.updated_at),t.event_id;
INSERT INTO uav_risk_channel(risk_case_id,channel,event_id,task_scope,claimed_at)
SELECT DISTINCT ON(c.risk_case_id) c.risk_case_id,'VOICE',t.event_id,t.risk_scope,COALESCE(t.triggered_at,t.updated_at)
FROM uav_risk_case c JOIN uav_risk_case_member m USING(risk_case_id) JOIN uav_auto_voice_task t ON t.event_id=m.event_id
WHERE c.superseded_by IS NULL AND t.attempt_count>0
  AND (SELECT COUNT(*) FROM uav_risk_case_member cm JOIN uav_risk_case cc USING(risk_case_id) WHERE cm.event_id=t.event_id AND cc.superseded_by IS NULL)=1
ORDER BY c.risk_case_id,COALESCE(t.triggered_at,t.updated_at),t.event_id;
CREATE FUNCTION uav_risk_notify_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM pg_notify('app_data_change','alarm'); PERFORM pg_notify('app_data_change','prediction'); RETURN NEW; END $$;
CREATE TRIGGER uav_risk_changed AFTER INSERT OR UPDATE ON uav_risk_case FOR EACH STATEMENT EXECUTE FUNCTION uav_risk_notify_trigger();
