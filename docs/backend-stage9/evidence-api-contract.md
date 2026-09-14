# 阶段 9 证据关联服务契约（协作者 A）

> 状态：本仓库实现稿。本切片只建设证据**文件底座**：摄取、元数据、真实内容哈希、业务关联、授权下载、冻结与访问留痕。
> C07 证据链（八类记录汇总、链完整性、目标 ID 变更回溯）见[证据链契约](evidence-chain-api-contract.md)；链只通过本服务引用文件，不自存一份。

## 交付边界

| 做 | 不做 |
| --- | --- |
| 真实字节入库，SHA-256 由服务端对内容计算 | 客户端提交的哈希、演示哈希、按哈希合并文件 |
| `evidence_link` 关联已存在的业务对象（见下表，含案件与处置授权） | 空域、风险事件、交接头等未建 FK 的对象 |
| 授权下载（无公开 URL）、访问成功/拒绝留痕 | 调阅按钮、页面手工「完整性筛选」 |
| 冻结/解冻阻断后续清理；入库按平台缺省留存期写入 `retain_until`；人工销毁（已到期且未冻结） | 按年限自动销毁、ZIP 打包导出、异地备份 |
| 文件级校验：缺失 → `MISSING`，哈希不符 → `CORRUPT` | 证据链完整性校验值、合并前判定依据（见 C07 契约，不在本文件切片） |

平台缺省留存期已启用（见下表），**不是**档案管理方确认的正式制度。到期只改变保管结论 `custody`。人工销毁仅在 `custody=DUE` 且无未释放冻结时删除存储对象，台账行改为 `DESTROYED` 并留下原因。不按年限自动清理。Q7 仍阻塞正式档案制度替换。历史行 `retain_until` 可空：读时按同规则从 `captured_at`（缺则 `stored_at`）推算，不回写；空不表示立即过期。

## 通用约定

沿用 `/api/v1`、`{ok,data}` / `{ok:false,error:{code,message}}`、snake_case、字符串 ID、epoch 毫秒、`page/size → items/page/size/total`（默认 1/20，最大 100）。未知/重复 query 400。写请求 `Idempotency-Key` 8–128 位。鉴权先于路径、query、multipart 解析。`ASSIGNED` 匹配同一有效 `(owner_org_id,district_id)` 元组；`ALL` 仍要求归属目录存在且启用。越权对象 404。

工程上限：单文件 32 MiB，不是业务保管容量指标。

## 权限

| 接口 | 权限 |
| --- | --- |
| 列表/详情/访问记录/CSV | `evidence:read` |
| 摄取 | `evidence:ingest` |
| 关联 | `evidence:link` |
| 下载内容 | `evidence:download` |
| 冻结/解冻 | `evidence:hold` |
| 人工销毁 | `evidence:destroy` |
| 文件校验（可更新 MISSING/CORRUPT/AVAILABLE） | `evidence:read` |

菜单 `evidence` 只控制导航。未关联文件仅 `evidence:ingest` 可见；已关联文件还须至少能按数据范围看到其中一个关联对象。返回的 `links` 逐项按同一范围过滤。

## 关联对象

`subject_kind` 与 `evidence_link` 八列一一对应，恰有一个非空：

| kind | 列 | 表 |
| --- | --- | --- |
| `EVENT` | `event_id` | `uav_event` |
| `DEVICE` | `device_id` | `ops_device`（范围经 `device_business_scope`） |
| `TARGET` | `target_id` | `target` |
| `PLAN` | `plan_id` | `flight_plan` |
| `COMMAND` | `command_id` | `device_command` |
| `COMMISSION` | `commission_id` | `commission_task` |
| `CASE` | `case_id` | `punishment_case`（另需 `punishment:read`） |
| `AUTHORIZATION` | `authorization_id` | `disposal_authorization`（另需 `disposal:read`） |

关联前对象必须存在且对操作者可见；设备/指令/调测在映射表为空时视为不可见。案件与授权缺对应模块读权限或越权范围按 404 / 空列表处理，不泄露编号。重复 `(evidence, kind, 对象)` 409 `LINK_EXISTS`。不提供 `HANDOFF` 主体。

## 种类与状态

`kind_code`（页面中文由前端映射，本接口不接受中文种类名）：

`EO_VIDEO` `EO_STILL` `TRACK_SNAPSHOT` `NOTICE_RECEIPT` `COMMISSION_REPORT` `COMMAND_LOG` `SCENE_PHOTO` `PENALTY_DOCUMENT`

`PENALTY_DOCUMENT` 只表示文件种类，不表示处罚案件已建设。

`status`：`PENDING` → 写入并算完哈希后 `AVAILABLE`；对象丢失 `MISSING`；内容与 `sha256` 不符 `CORRUPT`；`DESTROYED` 仅保留元数据（人工销毁写入）。`status` 只描述文件实体，不表示留存届满。

### 平台缺省留存期

起点：`captured_at`，缺则 `stored_at`。日历按 UTC：`plusDays` / `plusYears`。客户端不得提交到期日。

| `kind_code` | 留存期 | 说明 |
| --- | --- | --- |
| `COMMISSION_REPORT` | 90 天 | 设备建设期记录，非案件证据 |
| `SCENE_PHOTO` | 1 年 | 辅助取证材料 |
| `EO_VIDEO` `EO_STILL` | 3 年 | 影像取证材料 |
| `TRACK_SNAPSHOT` `NOTICE_RECEIPT` `COMMAND_LOG` `PENALTY_DOCUMENT` | 5 年 | 与案卷同期（轨迹定性 / 通报凭据 / 处置审计 / 法律文书） |

`custody`（读时计算，不落库）：

| 值 | 条件 |
| --- | --- |
| `HELD` | 存在未释放冻结（优先于到期） |
| `DUE` | `retain_until <= now` |
| `NEARING` | `now < retain_until <= now + 30 天` |
| `KEPT` | 其余（含尚无法计算到期日） |

## 接口

```
GET    /api/v1/evidence-files
GET    /api/v1/evidence-files/export.csv
GET    /api/v1/evidence-files/{evidence_id}
GET    /api/v1/evidence-files/{evidence_id}/content
GET    /api/v1/evidence-files/{evidence_id}/access-logs
POST   /api/v1/evidence-files
POST   /api/v1/evidence-files/{evidence_id}/links
POST   /api/v1/evidence-files/{evidence_id}/verify
POST   /api/v1/evidence-files/{evidence_id}/holds
POST   /api/v1/evidence-files/{evidence_id}/holds/{hold_id}/release
POST   /api/v1/evidence-files/{evidence_id}/destroy
```

### 列表

Query：`page,size,kind_code,status,subject_kind,subject_id,q`。`subject_kind` 与 `subject_id` 必须成对。排序固定 `captured_at DESC NULLS LAST, stored_at DESC, evidence_id DESC`。

列表项：`evidence_id,evidence_no,kind_code,original_name,content_type,size_bytes,status,captured_at,stored_at,held,link_count,retain_until,retain_label,custody`。`sha256` 只在详情。

### 详情

固定字段：列表项 + `sha256,source_mode,owner_org_id,district_id,version,created_at,updated_at,links[],holds[],retain_note`。
已销毁时另有 `destroyed_at,destroyed_by,destroy_reason,destroy_approval?`。
`retain_until` 能算出则返回（含历史空列的读时推算）。不返回 `object_key` / 存储路径 / 公开 URL。
`links[]`：`link_id,subject_kind,subject_id`（及可见时的 `subject_no`）。
`holds[]`：`hold_id,reason,held_by,created_at,released_at?,released_by?`。

### 摄取 `multipart/form-data`

字段：`file`（必填）、`kind_code`（必填）、`owner_org_id`、`district_id`、`captured_at?`、`subject_kind?`、`subject_id?`、`source_mode?`。
`subject_*` 成对；有关联时归属必须与对象归属一致，可省略 org/district 并由对象回填。无关联时 org/district 必填且须在操作者数据范围内。
`source_mode` 缺省为配置 `app.source-mode`，只允许 `mock|replay|live`。`captured_at` 不得晚于 `AppClock`。
成功 201：详情 DTO。幂等重放 409 `IDEMPOTENCY_REPLAY`。

### 下载

`evidence:download`；`AVAILABLE` 才 200。`PENDING` 409 `EVIDENCE_NOT_READY`；`MISSING`/`CORRUPT` 409 `EVIDENCE_UNAVAILABLE`；`DESTROYED` 409 `EVIDENCE_DESTROYED`。响应为文件流，`Content-Disposition: attachment`。每次尝试写入 `evidence_access_log`（成功 `GRANTED` / 拒绝在已定位到对象且缺下载权时 `DENIED`）。

### 校验

读取存储对象，重算 SHA-256：一致则保持或恢复 `AVAILABLE`；文件不存在 → `MISSING`；不符 → `CORRUPT`。返回 `{evidence_id,status,sha256,matches}`。`DESTROYED` 不改变状态。

### 冻结

Body：`{"reason":"..."}`（1–500 字）。已有未释放冻结 409 `HOLD_ACTIVE`。解冻 body 可空。冻结事实除释放列外不改写。`DESTROYED` 不能再冻结，409 `EVIDENCE_DESTROYED`。

### 人工销毁

`POST /api/v1/evidence-files/{evidence_id}/destroy`，权限 `evidence:destroy`，`Idempotency-Key` 必填。
Body：`{"reason":"...","approval_no":"..."}`。`reason` 1–500 字必填；`approval_no` 可省略，填写时 1–64 字，只作为操作员手填依据，不是审批流。

闸门：未到期 409 `DESTROY_NOT_DUE`；未释放冻结 409 `HOLD_ACTIVE`；已销毁 409 `EVIDENCE_DESTROYED`。通过后删除存储对象，行保持 `DESTROYED`，哈希与元数据留下。下载 409 `EVIDENCE_DESTROYED`。不按到期日自动执行。

### CSV

与列表同筛选、同范围；UTF-8 BOM。不含文件内容。列含 `retain_until,retain_label,custody`。成功审计 `evidence_exported`。

## 给协作者 B 的应用服务

`com.uav.lowaltitude.modules.evidence.application.EvidenceAssociationService`

- `ingest(...)` / `link(evidenceId, subjectKind, subjectId)` / `listBySubject(subjectKind, subjectId)` / `get(evidenceId)`
- B 不直接写 `evidence_*` 表，不把文件字节写入自己的模块。

## 存储

`ObjectStoragePort` 本地目录 `app.evidence-dir`。数据库只存 `storage_backend=local`、`object_key`、`sha256`、大小。同哈希不是同一保管来源，不合并。
