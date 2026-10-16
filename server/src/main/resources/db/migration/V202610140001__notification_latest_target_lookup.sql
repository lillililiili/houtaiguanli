-- Notification eligibility reads the latest ACTIVE evaluation without a subject_kind
-- predicate. The existing TARGET-only index cannot serve that query, and a reused
-- prepared plan may scan the global time index across unrelated target history.
-- Keep the query, ordering and historical rows unchanged; provide its full key.
CREATE INDEX idx_notification_latest_target
    ON rule_evaluation (target_id, mode, evaluated_at DESC, evaluation_id DESC);
