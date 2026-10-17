-- Notification decisions affect both the follow-up list and the originating alarm progress.
-- Payload is only the existing topic name; committed changes trigger scoped API rereads.
CREATE TRIGGER trg_handoff_decision_punishment_ins AFTER INSERT ON handoff_notification_decision
REFERENCING NEW TABLE AS app_changed_rows FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change('punishment');
CREATE TRIGGER trg_handoff_decision_punishment_upd AFTER UPDATE ON handoff_notification_decision
REFERENCING NEW TABLE AS app_changed_rows FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change('punishment');
CREATE TRIGGER trg_handoff_decision_punishment_del AFTER DELETE ON handoff_notification_decision
REFERENCING OLD TABLE AS app_changed_rows FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change('punishment');
CREATE TRIGGER trg_handoff_decision_alarm_ins AFTER INSERT ON handoff_notification_decision
REFERENCING NEW TABLE AS app_changed_rows FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change('alarm');
CREATE TRIGGER trg_handoff_decision_alarm_upd AFTER UPDATE ON handoff_notification_decision
REFERENCING NEW TABLE AS app_changed_rows FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change('alarm');
CREATE TRIGGER trg_handoff_decision_alarm_del AFTER DELETE ON handoff_notification_decision
REFERENCING OLD TABLE AS app_changed_rows FOR EACH STATEMENT EXECUTE FUNCTION app_notify_data_change('alarm');
