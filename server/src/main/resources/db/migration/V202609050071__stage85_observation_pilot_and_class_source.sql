-- 阶段 8.5 设备直连切片：观测层补两件事实——飞手位置与"类别是谁给的"（契约 v1.1 §3/§6）。
-- PostgreSQL 专属的几何 CHECK 与 GIST 放 db/postgresql/R__stage85_direct_access.sql（H2 测试不加载该目录）。

-- 飞手位置来自协议 A 的 pilotLon/pilotLat，是设备直接上报的**另一个点**，不是目标位置：
-- 阶段 7 的 C02-6（超视距）要拿它和目标位置算大圆距离，所以必须与目标位置分列存放，不能挤进 location。
ALTER TABLE source_observation ADD COLUMN pilot_location GEOMETRY(POINT, 4326);

-- 类别来源：同一个目标的 class_code 可能来自协议 A 的 objectType、协议 C 的光电 aiStatus、雷达分类码或人工修订，
-- 四者的可信度和口径完全不同。不记来源就无法解释"为什么这台设备说是鸟、那台说是无人机"。
ALTER TABLE source_observation ADD COLUMN class_source VARCHAR(16);

ALTER TABLE source_observation ADD CONSTRAINT ck_stage85_observation_class_source CHECK (
    class_source IS NULL OR class_source IN ('SENSE_DATA', 'EO_TRACKING', 'RADAR', 'MANUAL')
);

-- 目标最新状态同样带出飞手位置：规则引擎读的是 target_latest_state，不逐条回溯观测。
ALTER TABLE target_latest_state ADD COLUMN pilot_location GEOMETRY(POINT, 4326);
