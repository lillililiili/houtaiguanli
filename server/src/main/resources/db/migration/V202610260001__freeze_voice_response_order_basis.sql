-- Preserve the final send-time ordering decision independently from the delivery result.
-- Existing accepted calls intentionally remain NULL: current state cannot reconstruct their reason.
ALTER TABLE uav_auto_voice_task ADD COLUMN response_order_basis_json TEXT;
