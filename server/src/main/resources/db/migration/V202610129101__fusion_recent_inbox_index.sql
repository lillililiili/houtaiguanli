-- Bound the recent-source priority lookup by reception time as inbox history grows.
CREATE INDEX idx_fusion_inbox_recent_sources ON inbox_message (received_at, source, source_id);
