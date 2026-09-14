-- C04 评估会把回放目标记到独立来源，与 live/mock 分开统计；缺这一行则 ingest 判来源无效。
INSERT INTO integration_source (source_id, source_code, name, protocol_code, protocol_version, enabled, credential_ref, source_mode, created_at, updated_at, version)
SELECT 'rule-engine-space-risk-replay', 'RULE-ENGINE-SPACE-RISK-REPLAY', '空间安全风险规则引擎（回放）',
       NULL, NULL, TRUE, NULL, 'replay', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM integration_source WHERE source_id = 'rule-engine-space-risk-replay');
