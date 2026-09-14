-- 阶段 9：空域写模型（接替式版本）与 GeoJSON 导入。
-- 空域版本本身仍是"只增 + 只允许关闭 valid_to"的历史证据：已保存的研判引用某个版本的几何与高度带，
-- 原地修改会让历史结论悄悄漂移，所以变更一律是"新版本接替旧版本"（决策 9-1）。
-- PostgreSQL 专属的接替触发器与几何 CHECK 放 db/postgresql/R__stage9_airspace_succession.sql。

-- ---------- kind_code 字典 ----------
-- 决策 9-3：种类必须来自固定字典，页面才能有稳定的图层映射（禁飞/限高/适飞）。
-- 历史行先归一：阶段 3/7 种子写过 HEIGHT_LIMIT 与 TEMPORARY 两个同义写法。
-- 归一动作本身移到 db/postgresql/V202609050065__stage9_airspace_kind_code_normalization.sql：
-- 阶段 3 的 trg_stage3_airspace_version_immutable 禁止一切 UPDATE，而它建在 R__ 里（Flyway 先跑完
-- 全部 V 迁移才跑 R），所以在已经启动过一次的库上，这里的 UPDATE 必然被触发器拒绝；全新库上反而
-- 因为触发器尚未创建而侥幸通过。归一需要临时摘掉触发器，那是 PostgreSQL 专属语法，不能放进本文件
-- （本目录同时要在 H2 测试库执行）。

-- CHECK 里额外保留 HEIGHT_LIMIT / TEMPORARY 两个历史写法：阶段 7 的种子与测试仍在插入它们，
-- 而那些文件不属于本任务；去掉会让全新 H2 库一启动就违反约束。写接口（AirspaceKind）只接受规范的五个值，
-- 因此新数据不会再产生历史写法。待领导安排阶段 7 种子归一后，可由后续迁移收紧为五值。
ALTER TABLE airspace_version ADD CONSTRAINT ck_stage9_airspace_kind_code CHECK (
    kind_code IN ('PROHIBITED', 'RESTRICTED', 'ALTITUDE_LIMIT', 'PERMITTED', 'TEMPORARY_CONTROL',
                  'HEIGHT_LIMIT', 'TEMPORARY')
);

-- ---------- 版本来源 ----------
-- 决策 9-2：人工与导入的空域不是外部来源，airspace.source_id 保持 NULL；操作者痕迹落在这里。
-- superseded_version_id 记录本版接替了哪一版，配合 version_no 可以还原完整的接替链。
CREATE TABLE airspace_version_origin (
    origin_id               VARCHAR(36) PRIMARY KEY,
    airspace_version_id     VARCHAR(36) NOT NULL UNIQUE,
    origin_kind             VARCHAR(16) NOT NULL,
    actor_id                VARCHAR(36),
    import_item_id          VARCHAR(36),
    superseded_version_id   VARCHAR(36),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage9_origin_version FOREIGN KEY (airspace_version_id)
        REFERENCES airspace_version (airspace_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage9_origin_superseded FOREIGN KEY (superseded_version_id)
        REFERENCES airspace_version (airspace_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage9_origin_actor FOREIGN KEY (actor_id)
        REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage9_origin_kind CHECK (origin_kind IN ('MANUAL', 'GEOJSON_IMPORT', 'SEED')),
    -- 人工与导入都必须留下操作者；种子没有操作者。
    CONSTRAINT ck_stage9_origin_actor_pair CHECK (origin_kind = 'SEED' OR actor_id IS NOT NULL),
    CONSTRAINT ck_stage9_origin_import_pair CHECK (origin_kind = 'GEOJSON_IMPORT' OR import_item_id IS NULL)
);

CREATE INDEX idx_stage9_origin_superseded ON airspace_version_origin (superseded_version_id);

-- ---------- GeoJSON 导入 ----------
-- 两步（暂存 → 确认/放弃）而不是一次性写入：导入的是别人给的文件，必须先让操作者看清每个要素被解析成什么、
-- 哪些被拒绝以及为什么，确认后才产生空域版本这种不可回退的历史事实。
CREATE TABLE airspace_import_batch (
    batch_id        VARCHAR(36) PRIMARY KEY,
    status          VARCHAR(16) NOT NULL,
    feature_count   INTEGER NOT NULL,
    accepted_count  INTEGER NOT NULL,
    note            VARCHAR(500),
    owner_org_id    VARCHAR(36) NOT NULL,
    district_id     VARCHAR(36) NOT NULL,
    created_by      VARCHAR(36) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by      VARCHAR(36),
    decided_at      TIMESTAMP WITH TIME ZONE,
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_stage9_import_batch_org FOREIGN KEY (owner_org_id) REFERENCES app_org (org_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage9_import_batch_district FOREIGN KEY (district_id) REFERENCES app_district (district_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage9_import_batch_creator FOREIGN KEY (created_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage9_import_batch_decider FOREIGN KEY (decided_by) REFERENCES app_user (user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_stage9_import_batch_status CHECK (status IN ('STAGED', 'CONFIRMED', 'DISCARDED')),
    CONSTRAINT ck_stage9_import_batch_counts CHECK (feature_count >= 0 AND accepted_count >= 0 AND accepted_count <= feature_count),
    CONSTRAINT ck_stage9_import_batch_decision CHECK (
        (status = 'STAGED' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('CONFIRMED', 'DISCARDED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    ),
    CONSTRAINT ck_stage9_import_batch_version CHECK (version >= 0)
);

CREATE INDEX idx_stage9_import_batch_scope ON airspace_import_batch (owner_org_id, district_id, created_at DESC, batch_id DESC);

-- 每个 feature 一行：解析结果、问题清单与是否可接受都留痕，确认后回填它建出了哪个空域版本。
-- 不可接受的要素也保留：操作者要能看到"这份文件里哪几条没进来、为什么"。
CREATE TABLE airspace_import_item (
    item_id                     VARCHAR(36) PRIMARY KEY,
    batch_id                    VARCHAR(36) NOT NULL,
    seq                         INTEGER NOT NULL,
    name                        VARCHAR(128),
    airspace_no                 VARCHAR(64),
    kind_code                   VARCHAR(32),
    boundary_geojson            JSONB,
    boundary                    GEOMETRY(MULTIPOLYGON, 4326),
    min_altitude_m              NUMERIC(10, 2),
    max_altitude_m              NUMERIC(10, 2),
    altitude_datum              VARCHAR(16),
    valid_from                  TIMESTAMP WITH TIME ZONE,
    valid_to                    TIMESTAMP WITH TIME ZONE,
    issues                      JSONB NOT NULL DEFAULT '[]',
    accepted                    BOOLEAN NOT NULL DEFAULT FALSE,
    target_airspace_id          VARCHAR(36),
    result_airspace_version_id  VARCHAR(36),
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stage9_import_item_batch FOREIGN KEY (batch_id) REFERENCES airspace_import_batch (batch_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage9_import_item_airspace FOREIGN KEY (target_airspace_id) REFERENCES airspace (airspace_id) ON DELETE RESTRICT,
    CONSTRAINT fk_stage9_import_item_version FOREIGN KEY (result_airspace_version_id) REFERENCES airspace_version (airspace_version_id) ON DELETE RESTRICT,
    CONSTRAINT uk_stage9_import_item_seq UNIQUE (batch_id, seq),
    CONSTRAINT ck_stage9_import_item_seq CHECK (seq >= 1),
    CONSTRAINT ck_stage9_import_item_altitude_pair CHECK (
        (min_altitude_m IS NULL AND max_altitude_m IS NULL AND altitude_datum IS NULL)
        OR (min_altitude_m IS NOT NULL AND max_altitude_m IS NOT NULL
            AND altitude_datum IN ('AGL', 'AMSL') AND min_altitude_m <= max_altitude_m)
    ),
    CONSTRAINT ck_stage9_import_item_validity CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_from < valid_to),
    -- 只有被接受的要素才可能有解析成功的几何；被拒绝的要素不得带着半成品几何进入确认流程。
    CONSTRAINT ck_stage9_import_item_accepted_geometry CHECK (accepted = FALSE OR boundary IS NOT NULL)
);

CREATE INDEX idx_stage9_import_item_batch ON airspace_import_item (batch_id, seq ASC);
