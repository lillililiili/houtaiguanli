-- 光电跟踪画面取证：来源设备由平台按当前跟踪任务登记（EO_TRACKING_CAPTURE），
-- 与入库人自行填报的 UPLOADER_DECLARED 区分。只放宽取值范围，不改历史行。
ALTER TABLE evidence_file DROP CONSTRAINT ck_evidence_capture_provenance;
ALTER TABLE evidence_file ADD CONSTRAINT ck_evidence_capture_provenance CHECK (
    capture_provenance IS NULL OR capture_provenance IN ('UPLOADER_DECLARED', 'EO_TRACKING_CAPTURE'));
