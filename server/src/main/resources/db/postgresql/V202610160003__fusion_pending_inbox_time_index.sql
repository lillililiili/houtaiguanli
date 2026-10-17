-- The oldest pending timestamp protects fusion loss detection while work is in flight.
-- Keep source-prefix, retry-limit and simulation policy in the existing runtime query.
-- This transactional index only excludes terminal/incomplete inbox rows; it changes no history.
CREATE INDEX idx_fusion_inbox_pending_received_at
    ON inbox_message (received_at)
    WHERE status IN ('RECEIVED', 'PROCESSING')
      AND source_id IS NOT NULL
      AND payload IS NOT NULL;
