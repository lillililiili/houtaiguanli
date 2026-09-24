-- 联动反制和信号干扰的批准有效期由 30 分钟改为 10 分钟。驱离、诱骗未纳入当前告警处置流程，保持原值。
-- 已批准记录的 valid_until 不回写。
UPDATE disposal_policy
SET params = CAST('{"approval_required":true,"two_person_rule":true,"max_active_per_subject":1,"time_limit_min":{"COUNTERMEASURE":10,"JAMMING":10,"DISPERSAL":15,"DECOY":30},"requires_confirmed_event":{"COUNTERMEASURE":true,"JAMMING":true,"DISPERSAL":false,"DECOY":true},"command_map":{"COUNTERMEASURE":{"operation_type":1,"operation_cmd":60003},"JAMMING":{"operation_type":1,"operation_cmd":60002},"DISPERSAL":{"operation_type":1,"operation_cmd":70001},"DECOY":{"operation_type":1,"operation_cmd":50002}}}' AS JSON),
    updated_at = CURRENT_TIMESTAMP,
    version = version + 1
WHERE policy_code = 'demo-v1';
