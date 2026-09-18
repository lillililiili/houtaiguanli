-- 每次通知保存当次接收资料；旧交接和历史投递保留原样，不补造历史快照。
ALTER TABLE handoff_delivery ADD COLUMN recipient_snapshot TEXT;
