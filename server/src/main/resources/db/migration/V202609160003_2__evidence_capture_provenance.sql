-- 新入库证据可登记来源设备及原始采集坐标；不回填历史，不使用当前目标位置推断。
ALTER TABLE evidence_file ADD COLUMN source_device_id VARCHAR(36);
ALTER TABLE evidence_file ADD COLUMN source_device_name VARCHAR(256);
ALTER TABLE evidence_file ADD COLUMN capture_longitude DOUBLE PRECISION;
ALTER TABLE evidence_file ADD COLUMN capture_latitude DOUBLE PRECISION;
ALTER TABLE evidence_file ADD COLUMN capture_provenance VARCHAR(32);
ALTER TABLE evidence_file ADD CONSTRAINT fk_evidence_source_device
    FOREIGN KEY (source_device_id) REFERENCES ops_device(device_id) ON DELETE RESTRICT;
ALTER TABLE evidence_file ADD CONSTRAINT ck_evidence_capture_position CHECK (
    (capture_longitude IS NULL AND capture_latitude IS NULL)
    OR (capture_longitude IS NOT NULL AND capture_latitude IS NOT NULL
        AND capture_longitude BETWEEN -180 AND 180 AND capture_latitude BETWEEN -90 AND 90));
ALTER TABLE evidence_file ADD CONSTRAINT ck_evidence_capture_provenance CHECK (
    capture_provenance IS NULL OR capture_provenance='UPLOADER_DECLARED');
