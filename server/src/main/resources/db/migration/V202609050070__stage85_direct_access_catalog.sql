-- 阶段 8.5 设备直连切片：来源类型目录与融合参数按凌云协议 A v8.6 扩展（决策 8-30）。
-- 三路字段来自凌云协议（已提供、未联调），schema_status 仍为 DEMO；联调通过后再改 CONFIRMED。

INSERT INTO source_type_catalog (source_type, display_name, schema_status, spec_ref, created_at) VALUES
    ('AOA', '无线电测向（AOA）', 'DEMO', '设备资料/凌云协议/协议A-设备数据及感知数据接入协议v8.6.pdf', CURRENT_TIMESTAMP),
    ('DCD', '协议破解', 'DEMO', '设备资料/凌云协议/协议A-设备数据及感知数据接入协议v8.6.pdf', CURRENT_TIMESTAMP),
    ('RID', 'RemoteID', 'DEMO', '设备资料/凌云协议/协议A-设备数据及感知数据接入协议v8.6.pdf', CURRENT_TIMESTAMP);

UPDATE source_type_catalog SET spec_ref = '设备资料/凌云协议/协议A-设备数据及感知数据接入协议v8.6.pdf'
 WHERE source_type IN ('EO', 'TDOA', 'FIVE_G_A', 'FUSION_BOX') AND spec_ref IS NULL;
UPDATE source_type_catalog SET display_name = 'TDOA' WHERE source_type = 'TDOA';

-- demo-v1 的参数整体重写（JSON 列在 H2/PG 没有共同的局部更新语法）：相对迁移 050 只新增六个键——
-- filter.accuracy_default_m 的 AOA/DCD/RID 三项与 weights 的 AOA/DCD/RID 三项，其余逐字相同，无修改、无删除。
-- 只按 config_version 匹配：不带 status/version 条件，否则在激活或停用过配置（version 已递增）的库上会静默匹配 0 行。
-- AOA 只给方位不给位置，精度值仅为"若厂家给位置时"的兜底；DCD/RID 精度取 TDOA 同级（DEMO）。
UPDATE fusion_config SET params = CAST('{"filter":{"alpha":0.6,"beta":0.25,"max_dt_ms":3000,"pred_max_frames":3,"bridge_max_gap_ms":6000,"accuracy_default_m":{"RADAR":15,"TDOA":60,"EO":25,"FIVE_G_A":80,"FUSION_BOX":20,"AOA":60,"DCD":60,"RID":60}},"association":{"gate_sigma":3.0,"w_pos":1.0,"w_alt":0.5,"w_time":0.3,"w_motion":0.6,"w_class":0.4,"w_hist":0.3,"alt_scale_m":30,"time_scale_ms":1500,"speed_scale_mps":6,"heading_scale_deg":45,"cost_max":6.0,"pending_confirm_frames":3,"pending_expire_frames":6},"identity":{"tentative_to_stable_hits":3,"short_lost_after_ms":3000,"terminate_after_ms":15000,"merge_min_frames":4,"merge_max_dist_sigma":2.0,"split_min_frames":4,"split_min_separation_m":100},"weights":{"RADAR":{"position":0.45,"motion":0.5,"class":0.2,"identity":0.0},"EO":{"position":0.2,"motion":0.1,"class":0.6,"identity":0.1},"TDOA":{"position":0.25,"motion":0.2,"class":0.0,"identity":0.5},"FIVE_G_A":{"position":0.1,"motion":0.2,"class":0.2,"identity":0.4},"FUSION_BOX":{"position":0.4,"motion":0.4,"class":0.3,"identity":0.0},"AOA":{"position":0.0,"motion":0.0,"class":0.0,"identity":0.5},"DCD":{"position":0.25,"motion":0.2,"class":0.0,"identity":0.6},"RID":{"position":0.25,"motion":0.2,"class":0.0,"identity":0.6}},"quality":{"latency_penalty_ms":2000,"loss_window_frames":10,"loss_penalty_per_miss":0.08,"anomaly_zscore":4.0,"anomaly_downweight":0.25},"degradation":{"three_source_min":3,"undetermined_deficit":0.5,"single_source_deficit":0.35,"fusion_box_only_deficit":0.2,"lost_step_deficit":0.1}}' AS JSON),
    note = 'DEMO：全部阈值与权重为演示值，未经算法方确认；AOA/DCD/RID 三项按凌云协议 v8.6 补入（阶段 8.5）'
 WHERE config_version = 'demo-v1';
