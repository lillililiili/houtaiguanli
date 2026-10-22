-- Retirement is independent of scheduled versions: retain immutable history, including future schedules.
ALTER TABLE airspace ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_airspace_lifecycle_scope ON airspace (owner_org_id, district_id, deleted_at);
