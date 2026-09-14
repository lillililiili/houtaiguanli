-- 取消无人机核实结论「证据待补充」。现网停在该态的事件改回待核实，仍可判属实或误报。
-- 核实历史里的旧结论保留（结论检查仍允许 EVIDENCE_REQUIRED），只收紧当前事件状态。
UPDATE uav_event
   SET state_code = 'PENDING_VERIFICATION',
       updated_at = CURRENT_TIMESTAMP
 WHERE state_code = 'EVIDENCE_REQUIRED';

ALTER TABLE uav_event DROP CONSTRAINT ck_stage4_uav_event_state;
ALTER TABLE uav_event ADD CONSTRAINT ck_stage4_uav_event_state
    CHECK (state_code IN ('PENDING_VERIFICATION','CONFIRMED','FALSE_POSITIVE'));
