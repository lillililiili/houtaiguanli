# 阶段 9 C07 证据链契约（协作者 A 承接）

2026-09-10 修订：AUTHORIZATION 汇总同时承接实际 `disposal_authorization`，通过事件或目标关联，不再只依赖旧设备命令。返回前要求 `disposal:read` 并核对授权主体范围；权限不足不泄漏授权 ID。

> 状态：本仓库实现稿。本切片在已交付的证据**文件底座**之上做只读业务汇总：八类记录、链完整性校验值、缺失标志、目标 ID 变更回溯。
> 不自存文件字节，不新建证据链实体表，不扩展 `evidence_link` 六列。文件留存年限与人工销毁见文件底座契约。

## 交付边界

| 做 | 不做 |
| --- | --- |
| 按 `EVENT` / `TARGET` / `CASE` 聚合八类已有记录 | 空域、风险事件表；`AUTHORIZATION` 不作链根 |
| 读时计算 SHA-256 链校验值 | 链快照落库、ZIP 打包、按 Demo 年限销毁 |
| 用 `target_current_alias` 展开家族，合并前判定取 `assessment_result` | 改写历史 FK、编辑 `modules/fusion/**`、虚构 `legal_status` |
| 缺权桶标 `FORBIDDEN`，缺记录标 `ABSENT` | 把缺权当成缺失，或编造记录凑齐八类 |
| 告警/处罚页只读展示；文件详情与证据台账共用渲染 | 改页面骨架、回退 mock.js、自动截图入库 |

文件底座接口不变，见[证据关联服务契约](evidence-api-contract.md)。人工销毁在文件底座，不在本链接口。

## 通用约定

沿用 `/api/v1`、`{ok,data}` / `{ok:false,error:{code,message}}`、snake_case、字符串 ID、epoch 毫秒。鉴权先于路径与 query 解析。未知 query 400。`ASSIGNED` 匹配同一有效 `(owner_org_id,district_id)` 元组；越权对象 404。

每类最多 100 条；超限 `coverage[TYPE].truncated=true`，校验值只含实际返回的成员。

## 权限

| 接口 | 权限 |
| --- | --- |
| 读取证据链 | `evidence:read` |

关联桶额外权限（缺权时该桶 `FORBIDDEN` 并省略条目，不 403 整条链）：

| 桶 | 额外权限 |
| --- | --- |
| `TRACK`、目标家族、`current_target_id` | `target:read` |
| `VIDEO` / `IMAGE` | 已有 `evidence:read`；下载仍走 `/evidence-files/{id}/content` 的 `evidence:download` |
| `ALARM`、核实类 `DISPOSAL` | `alarm:read` |
| `JUDGMENT`、合并前判定 | `assessment:read` |
| `AUTHORIZATION` 中的指令 | 与文件底座 COMMAND 相同的设备范围可见性；不可见则不算入链 |
| `DISPOSAL` 中的交接 | `handoff:read`（缺权只省略交接，不 403） |
| `OPERATION` 中的审计 | `audit.read` |
| `lineage` | `target:read` + `fusion:read` |

菜单 `evidence` 只控制导航。

## 接口

```
GET /api/v1/evidence-chains/{subject_kind}/{subject_id}
```

`subject_kind` 仅 `EVENT` | `TARGET` | `CASE`。`AUTHORIZATION` 只作文件关联主体，不是链根。其它 400。无 query；出现任一 query 400。CASE 链需要 `punishment:read` 且案件可见，否则 404。文件桶含挂到本案的证据；若案件有 `event_id` 且调用者可见，告警/处置桶按 EVENT 链同口径聚合。

### 成功 `data`

固定字段：`subject_kind, subject_id, subject_no, coverage, integrity, records, lineage`。

可省略：`current_target_id`（无目标或无 `target:read`）；`historical_target_ids`（无谱系权限或家族无历史 ID）。

`coverage` 固定八键 `TRACK, VIDEO, IMAGE, ALARM, JUDGMENT, AUTHORIZATION, DISPOSAL, OPERATION`，每项：

- `status`：`PRESENT` | `ABSENT` | `FORBIDDEN`
- `count`：返回条数
- `broken_count`：仅文件类，`MISSING`/`CORRUPT` 件数；无文件时省略
- `truncated`：超 100 条时为 true，否则省略

`integrity`：

- `algorithm` 固定 `SHA-256`
- `checksum` 小写 hex
- `member_count`
- `computed_at` epoch 毫秒（`AppClock`）

`records[]` 按 `occurred_at ASC, record_type ASC, record_id ASC`：

- `record_type, record_id, occurred_at, fingerprint, availability, summary`
- `availability`：`PRESENT` | `UNAVAILABLE`（文件 `MISSING`/`CORRUPT`/`DESTROYED`/`PENDING`）

`lineage`：

- `availability`：`PRESENT` | `FORBIDDEN`
- `ops[]`、`pre_merge_judgments[]` 仅在 `PRESENT` 时出现

### 八类记录来源

| record_type | 来源 | summary 白名单 |
| --- | --- | --- |
| `TRACK` | `track`（含 `layer`，不下发点列） | `layer, started_at, ended_at, point_count` |
| `VIDEO` | `evidence_file.kind_code=EO_VIDEO` 且已关联 | `evidence_no, kind_code, original_name, status, sha256, size_bytes` |
| `IMAGE` | `EO_STILL` / `SCENE_PHOTO` / `TRACK_SNAPSHOT` | 同上 |
| `ALARM` | `alarm` | `severity, alarm_type, source_mode, received_at` |
| `JUDGMENT` | `assessment_result`（只增投影） | `target_id, conclusion_code, assessed_at, rule_version_id` |
| `AUTHORIZATION` | 可见且 `authorization_id` 非空的 `device_command`；以及 `COMMAND_LOG` 文件 | 指令：`command_no, authorization_id, status`；文件：同 VIDEO 摘要。不校验审批表（表不存在） |
| `DISPOSAL` | `uav_event_verification`；`handoff`（`UAV_EVENT`）；`NOTICE_RECEIPT` / `PENALTY_DOCUMENT` 文件 | 核实：`conclusion, version, resulting_state`；交接：`handoff_type, delivery_status, source_version`；文件：同 VIDEO 摘要。反制完成事实未接入则没有对应条目 |
| `OPERATION` | `audit_log`（`object_id` 落在本链 ID 集合且 `object_type` 白名单）；`COMMISSION_REPORT` 文件 | 审计：`action, result, module_code`（不回显 detail/ip/凭据）；文件：同 VIDEO 摘要 |

其它文件种类不单开第九类，按上表挂到授权/处置/操作。

### 目标 ID 家族与合并前判定

查询历史目标或幸存目标都经 `target_current_alias` 展开同一家族。`evidence_link` / 告警 / 研判上的历史 `target_id` 不改写。

`pre_merge_judgments[]`：对 `target_lineage.op ∈ {MERGE,SPLIT}` 的成员/源 ID，取 `assessment_result.target_id` 命中且 `assessed_at <= lineage.occurred_at` 的行。无研判则 `judgment_availability=ABSENT`。`ops[].snapshots` 只透出融合已写入的 JSON（`target_no/object_type_code/version`），不虚构合法性字段。

### 完整性校验值

规范串：成员按 `record_type|record_id|fingerprint` 字典序排序后以 `\n` 连接（UTF-8），对其做 SHA-256。空链为对空串哈希。

fingerprint：

| 类型 | 格式 |
| --- | --- |
| TRACK | `layer={layer}\|ended={epoch或空}\|points={n}` |
| 文件（VIDEO/IMAGE 及挂到其它类的文件） | `sha256={hex或空}\|status={status}` |
| ALARM | `received={epoch}\|severity={s}` |
| JUDGMENT | `assessed={epoch}\|conclusion={code}` |
| AUTHORIZATION 指令 | `status={s}\|auth={authorization_id或空}` |
| DISPOSAL 核实 | `version={n}\|conclusion={c}` |
| DISPOSAL 交接 | `delivery={status}\|version={source_version}` |
| OPERATION 审计 | `action={a}\|at={epoch}` |

成员增减或文件状态变化则校验值变。本值不是事件关闭门禁。

## 错误

| 条件 | HTTP | code |
| --- | --- | --- |
| 未登录 | 401 | `UNAUTHENTICATED` |
| 无 `evidence:read` | 403 | `FORBIDDEN` |
| 未知 query / 非法 kind / 非法 ID | 400 | `VALIDATION_ERROR` |
| 对象不存在或越权 | 404 | `NOT_FOUND` |

## 明确不做

不写 `evidence_*` 以外的表；不写融合 inbox；不建设处罚立案/文书/反制授权审批；打开本接口 ≠ 正式档案制度完成，≠ 现场取证完成。
