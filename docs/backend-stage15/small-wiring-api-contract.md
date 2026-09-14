# 小接线 API 契约（阶段 15，v1.0）

> 状态：领导冻结稿（2026-09-08）。通用约定同前（`{ok,data}`、snake_case、先鉴权再解析、越权 404、幂等键 + `expected_version`、成功审计同事务）。

## 1. 动作权限（角色矩阵）

| 方法 | 路径 | 权限 | 语义 |
| --- | --- | --- | --- |
| GET | `/permissions/actions` | 与 `/permissions/catalog` 同 | `[{module_code, module_name, actions:[{permission_code, action_code, name, level}]}]`，`level` 对当前查询无意义时省略 |
| GET | `/roles/{code}` | 同现有 | 增 `actions: [{permission_code, level}]`（只列该角色已授的 READ/OP 行） |
| PUT | `/roles/{code}/permissions` | 同现有 | body `{permissions[], expected_version, reason?, actions?: [{permission_code, level ∈ NONE|READ|OP}]}`；`actions` 缺省 → 动作行不动；给了 → 该角色动作行整组替换（NONE 即删除）；未知码 400 `INVALID_PERMISSION`；`ROLE-ADMIN` 的动作行不可改（409 `ROLE_LOCKED`）；受保护模块动作 400 `SYSTEM_PERMISSION_PROTECTED`；成功后 `permission_version` 递增，审计 `role_permissions_updated` detail 含 `actions_granted[]` |

## 2. 目标读接口（追加可空字段，列表与详情同形）

`risk_summary {risk_id, severity, state, occurred_at}` · `legality_summary {evaluation_id, legal_status, grade, violation_reasons[]}` · `disposal_summary {authorization_id, authorization_no, action_type, status}`；无则省略键。`latest_state` 增 `bearing_deg`、`bearing_device_id`：只在 `latest_state.location` 为空时给（15-15），与 `pilot_location` 无关；有位置的目标一律省略这两个键。

`GET /targets/{id}/observations` 的 `ObservationDto` 增 `bearing_deg`、`identity_confidence`、`device_id`（可空）。

## 3. 列表排序、筛选、导出

- `GET /alarms?…&sort=received_at|occurred_at|severity|state&order=asc|desc&alarm_type=&district_id=`；`GET /risks?…&sort=…&order=…&target_type=`。非法 `sort/order` 400 `VALIDATION_ERROR`。次序键后恒附 `id` 作稳定次序。
- `GET /alarms/export.csv`、`GET /risks/export.csv`：同列表权限与筛选（含 `sort/order`），上限 5000 行（常量 `platform/export/CsvExport.MAX_ROWS`，超出 400 `EXPORT_TOO_LARGE`；两侧由 `Stage15PostgresTest` 在真实 PG 上钉：恰 5000 → 200、5001 → 400），`text/csv; charset=UTF-8` + BOM，`Content-Disposition: attachment; filename="alarms-YYYYMMDD.csv"`；列头中文（与页面列一致）；审计 `alarms_exported` / `risks_exported`（detail：筛选条件、行数）。

## 4. 前端

- 角色页新增"动作权限"分区（按模块分组，等级 无/查看/操作），保存时随矩阵一起提交 `actions`。
- 态势页悬浮卡增三行（风险等级、违规事由、处置状态；缺则不渲染那行）；无位置目标按 `bearing_deg` 画方位线（设备位置来自设备列表）；来源面板显示分路 `class_confidence/identity_confidence`。
- 飞行监管页"鸟类事件"= `GET /risks?risk_type=SPACE_OBJECT&object_subtype=BIRD_FLOCK&size=1` 的 total；"涉及航线"= `GET /space-risks/summary` 的涉及航线数；两页列头排序、筛选、导出按钮启用；无权限提示按 15-8。

## 5. 审计
动作：`role_permissions_updated`（既有，detail 增 actions）、`alarms_exported`、`risks_exported`。路径 `/permissions/actions` → 模块 `roles`；`/alarms/export.csv` → `alarms`；`/risks/export.csv` → `risks`。

## 6. 提请协作者 A
- `GET /targets/{id}/eo-tracking-tasks` 在目标没有光电跟踪任务时返回 404，建议改为 `200 []`（空集合应能自证为空；告警页每选一个目标就一条 404）。
`evidence_link.subject_kind` 增 `CASE`、`AUTHORIZATION`（含 FK 列与三条 CHECK），链根增 `CASE`；B 侧处罚页与授权详情读证据留到 A 落地后。
