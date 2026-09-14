# T02 雷达只读后端：数据与 REST 契约

版本：Stage 1；日期：2026-09-03。

本文是 T02 雷达只读后端阶段 2–5 的唯一数据与 REST 命名基线。它只固化后续实现所需的迁移批次、表子集、权限、查询接口和兼容语义，不表示数据库、权限或接口已经实现或验收。本文遵循[后端开发基线](../后端开发基线.md)、[数据库设计文档](../数据库设计文档.md)和[后端规则](../../server/AGENTS.md)。

## 1. 范围与当前事实

### 1.1 本契约包含与排除的能力

- 首批 T02 数据来源固定为 `replay`。既有 `mock/replay/live` 来源枚举仍作为共享持久化词汇保留，但本契约不建立真实连接、自动回退、设备控制或反制能力。
- 只定义 9 个读取接口。本契约不新增任何写方法（POST/PUT/PATCH/DELETE）；认证已有的登录、退出接口不在本契约内。
- 目标、轨迹和轨迹点的写入绝不触发告警。`alarm` 只保存具有明确来源、已实际入库的告警事实；没有告警来源消息时，查询必须返回空结果，不能根据轨迹、高度、位置或前端规则自动生成告警。
- 不创建 `uav_event`，`alarm` 也不含 `uav_event_id`。后续只有在 `uav_event` 已由独立迁移创建后，才能用新的迁移增加该关系。
- 不提交或复制设备协议原件、现场报文、真实地址、账号、凭据和生产数据。

### 1.2 `origin/main` 的代码与迁移事实

当前只有以下 6 张表，且只能通过新迁移扩展：

| 历史迁移 | 已有表 | 与本契约有关的事实 |
| --- | --- | --- |
| `V1__init.sql` | `app_user`、`app_session`、`audit_log` | `app_user` 只有无外键的 `role_code/org_id`，没有权限关系、组织/区域范围和权限版本；会话 ID 是 Bearer `session_id`，不是 JWT |
| `V2__outbox_inbox.sql` | `outbox_event`、`inbox_message`、`idempotency_request` | `inbox_message` 目前只有 ID、来源命名空间、来源消息 ID 和 epoch 毫秒接收时间，唯一键为 `(source, source_msg_id)` |

历史迁移 `V1__init.sql`、`V2__outbox_inbox.sql` 禁止修改、重命名或重排。`app_user` 与 `inbox_message` 的变化必须写入下文规定的新迁移。

当前 [`DeviceController`](../../server/src/main/java/com/uav/lowaltitude/modules/device/api/DeviceController.java) 和 [`AlarmController`](../../server/src/main/java/com/uav/lowaltitude/modules/alarm/api/AlarmController.java) 只调用 `AuthContext.require()`，再返回固定空分页；它们会把非法 `page/size` 静默夹到允许范围。阶段 2 实现必须改为本文的权限、数据范围、真实查询和显式分页错误语义。

当前 `AuthUser` 只有 `userId/account/name/roleCode`。它不携带组织、区域、权限或 `permission_version`，因此不能作为“范围已完成”的依据。后续授权服务必须从有效会话对应的当前用户读取角色、角色状态、权限关系、`scope_mode` 和范围元组；若使用缓存，缓存键必须包含 `permission_version`，权限变化后不得继续使用旧决定。

## 2. 统一物理约定

### 2.1 类型与公共列

| 名称 | PostgreSQL 定义 | 约束 |
| --- | --- | --- |
| `ID` | `varchar(36)` | 服务端生成 UUID 字符串；API 始终按字符串返回 |
| `CODE(n)` | `varchar(n)` | 去除首尾空白后必须非空；大小写按各字段字典处理 |
| `TIME` | `timestamptz` | 新业务时间使用 UTC 时刻语义，Java 使用 `Instant`；REST 转为 epoch 毫秒 |
| `LOCATION` | `geometry(Point,4326)` | 只保存已确认的 WGS-84 经度、纬度；不能只改 SRID 冒充转换 |
| 可修改公共列 | `created_at TIME NOT NULL`、`updated_at TIME NOT NULL`、`version bigint NOT NULL DEFAULT 0` | `version >= 0`，应用按旧版本条件更新并递增；时间由统一应用时钟写入 |
| 追加公共列 | `created_at TIME NOT NULL` | 追加事实不附加可被普通业务改写的更新时间 |
| 来源公共列 | `source_mode varchar(8) NOT NULL` | 只允许小写 `mock/replay/live`；本 T02 首批实现只能创建 `replay` 业务记录 |
| 归属公共列 | `owner_org_id ID NULL`、`district_id ID NULL` | 分别外键到 `app_org/app_district`；任一未知时，本文 9 个接口对所有普通读取者均不可见 |

`mock` 仅允许 local/test 的显式合成数据使用。`live` 是兼容已有 `SourceMode` 的保留值，不在本契约中获得连接、启用、回退或“已联调”语义。阶段 2 的 T02 来源、设备、目标、轨迹和告警数据必须保持同一 `source_mode=replay`；不一致时拒绝业务入库并保留 Inbox 失败事实。

### 2.2 空值、未知值和完整性

- 数据库可空列的 `NULL` 表示该事实未知、未上报或不可可靠换算，绝不表示数值 `0`、`false`、在线、成功或全域范围。
- 空字符串不是未知值；所有代码、名称、外部 ID 和原因码在写入前去除首尾空白，空结果按无效输入拒绝。
- 没有设备状态事实时不创建虚假的 `device_state` 行；只有已记录状态行才能使用 `connectivity=UNKNOWN`，并应填写 `unknown_reason`。
- `has_alarm=NULL` 表示来源没有提供可判定值；`false` 只表示来源明确报告“无设备告警”。它与业务 `alarm` 表是否存在记录没有推导关系。
- `observed_at=NULL` 表示来源事件时间未知或不可信；`received_at` 始终保存平台实际接收时刻，但只能用于接收事实、历史查询筛选和稳定展示排序，绝不替代事件时间参与 latest 覆盖判断。
- `device_state` 与 `target_latest_state` 只有在新事实具有可信、非空的事件时间，且该时间严格晚于现有 latest 的 `observed_at` 时才更新；latest 不存在时也只有可信事件时间才能创建。事件时间相等时保持首次接受的 latest，事件时间更早或未知时只追加历史/保留 Inbox（按对应对象是否有历史表决定），不得用后到的 `received_at` 打破并列。
- 未知或非 WGS-84 坐标不写 `LOCATION`，也不写轨迹点；原始消息只保留在 Inbox。禁止生成 `POINT(0 0)`。
- `altitude_amsl_m` 与 `height_agl_m` 是不同基准，缺少安装高度、地形或基准依据时分别保持 `NULL`，不得互相代填。设备的 `altitude_m` 必须与 `altitude_datum` 同时存在或同时为空。
- 新表的外键删除行为默认采用 `RESTRICT`。除明确列出的唯一键外，不根据接近时间、接近位置、序列号或展示编号自动合并对象。
- JSONB 只保存原始/扩展事实，不承载主键、权限范围、排序字段或接口的稳定必填字段；本文明确为内部字段的 JSONB 不直接返回给客户端。

## 3. 顺序迁移批次

实现时先重新列出 `server/src/main/resources/db/migration`，取当时下一个可用 Flyway 版本作为批次 A，再取紧随其后的版本作为批次 B。不得在本文预占 `V3/V4`，不得把两个批次倒序，也不得把其中一部分补进 V1/V2。

### 3.1 批次 A：最小只读权限

依赖顺序固定为：组织/区域/角色/权限目录 → 角色权限 → 用户范围 → `app_user` 增量列与兼容约束。

#### `app_org`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `org_id` | `ID NOT NULL` | 主键 |
| `parent_id` | `ID NULL` | 自引用 `app_org.org_id`；根组织为空；禁止自指和层级循环 |
| `org_code` | `CODE(64) NOT NULL` | 唯一；使用确认后的组织代码，不从名称生成 |
| `name` | `CODE(128) NOT NULL` | 展示名，不参与授权匹配 |
| `enabled` | `boolean NOT NULL DEFAULT true` | 停用组织不产生有效范围 |
| 公共列 | 可修改公共列 | 无来源列、无归属列 |

索引：主键和 `UNIQUE(org_code)`；普通索引 `(parent_id)`。层级不自动包含下级，本阶段授权只按显式元组匹配。

#### `app_district`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `district_id` | `ID NOT NULL` | 主键 |
| `parent_id` | `ID NULL` | 自引用 `app_district.district_id`；根区域为空；禁止自指和层级循环 |
| `district_code` | `CODE(32) NOT NULL` | 唯一；必须来自确认后的区域资料 |
| `name` | `CODE(128) NOT NULL` | 展示名，不参与授权匹配 |
| 公共列 | 可修改公共列 | 无来源列、无归属列 |

索引：主键和 `UNIQUE(district_code)`；普通索引 `(parent_id)`。本阶段不把行政区层级解释为自动继承。

#### `app_role`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `role_code` | `varchar(32) NOT NULL` | 主键；保留现有 `ROLE-*` 值，不使用前端演示角色替换 |
| `name` | `CODE(64) NOT NULL` | 唯一 |
| `enabled` | `boolean NOT NULL DEFAULT true` | 停用角色不授予任何权限 |
| `system_role` | `boolean NOT NULL DEFAULT false` | 仅标识是否系统内置，不隐含管理员权限 |
| 公共列 | 可修改公共列 | 无来源列、无归属列 |

索引：主键和 `UNIQUE(name)`。为给 `app_user.role_code` 增加外键，迁移可按现存不同 `role_code` 创建同码、默认 `enabled=false`、无权限映射的兼容角色行；名称只作技术占位且不得解释为业务授权。local/test 合成数据必须显式启用所需角色并建立下文权限关系。生产角色资料未确认时保持禁用和无映射。

#### `app_permission`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `permission_code` | `varchar(96) NOT NULL` | 主键 |
| `module_code` | `CODE(48) NOT NULL` | 本批次分别为 `device/target/alarm` |
| `permission_kind` | `varchar(16) NOT NULL` | 本批次固定为 `ACTION` |
| `action_code` | `CODE(32) NOT NULL` | 本批次固定为 `read` |
| `name` | `CODE(128) NOT NULL` | 权限展示名 |
| 公共列 | 追加公共列 | 无来源列、无归属列 |

键与数据：`UNIQUE(module_code, permission_kind, action_code)`；只建立 `device:read`、`target:read`、`alarm:read` 三条权限目录记录。创建目录记录不等于授予任何角色。

#### `app_role_permission`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `role_code` | `varchar(32) NOT NULL` | 外键到 `app_role.role_code` |
| `permission_code` | `varchar(96) NOT NULL` | 外键到 `app_permission.permission_code` |
| 公共列 | 追加公共列 | 无来源列、无归属列 |

复合主键为 `(role_code, permission_code)`；反向查询索引为 `(permission_code, role_code)`。生产环境不自动插入任何角色映射。

#### `app_user_data_scope`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `user_id` | `ID NOT NULL` | 外键到 `app_user.user_id` |
| `org_id` | `ID NOT NULL` | 外键到 `app_org.org_id` |
| `district_id` | `ID NOT NULL` | 外键到 `app_district.district_id` |
| 公共列 | 追加公共列 | 无来源列；本身就是明确范围元组 |

复合主键为 `(user_id, org_id, district_id)`；反向管理索引为 `(org_id, district_id, user_id)`。组织与区域必须按同一行组成授权元组，禁止把用户的组织集合和区域集合做笛卡尔积。

#### `app_user` 增量扩展

| 新增项 | 类型/约束 | 兼容规则 |
| --- | --- | --- |
| `scope_mode` | `varchar(16) NOT NULL DEFAULT 'NONE'`；CHECK `NONE/ASSIGNED/ALL` | 迁移后所有既有用户默认 `NONE`，不会因旧 `org_id` 或角色名称获得范围 |
| `permission_version` | `bigint NOT NULL DEFAULT 0`；CHECK `>= 0` | 角色权限或用户范围变化时，在同一事务递增受影响用户版本 |
| 角色外键 | `app_user.role_code` → `app_role.role_code` | 先建立禁用兼容角色行，再加外键；不改变已有角色代码 |
| 查询索引 | `(role_code, scope_mode)` | 支持授权解析；不把索引视为权限实现 |

现有 `app_user.org_id` 暂不作为范围授权真源，也不在本批次强行增加组织外键，因为历史值没有已确认的组织目录。其值未知或无法映射时不回填虚构组织；实际读取范围只取 `scope_mode` 与 `app_user_data_scope`。

### 3.2 批次 B：雷达只读数据

依赖顺序固定为：`integration_source` → 扩展 `inbox_message` → `device` → 设备状态表 → `target` → 来源映射/最新状态 → `track` → `track_point` → `alarm`。所有业务表的归属外键依赖批次 A。

#### `integration_source`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `source_id` | `ID NOT NULL` | 主键 |
| `source_code` | `CODE(64) NOT NULL` | 唯一、不可变；用于 API 精确筛选 |
| `name` | `CODE(128) NOT NULL` | 展示名 |
| `protocol_code` | `varchar(64) NULL` | 未确认时为空，不伪造协议名称 |
| `protocol_version` | `varchar(64) NULL` | 未确认时为空；T02 回放按已核验版本填写 |
| `enabled` | `boolean NOT NULL DEFAULT false` | 只表示该来源是否允许摄取，不表示在线 |
| `credential_ref` | `varchar(256) NULL` | 仅保存外部凭据引用；不得保存凭据正文，也不经本文 API 返回 |
| `source_mode` | 来源公共列 | T02 首批可启用行只能是 `replay` |
| 公共列 | 可修改公共列 | 无归属列 |

索引：主键和 `UNIQUE(source_code)`；普通索引 `(source_mode, enabled, source_code)`。`enabled=true` 不能绕过运行环境对来源模式的校验。

#### `inbox_message` 增量扩展

保留既有主键、`source varchar(128)`、`source_msg_id varchar(128)`、`received_at bigint` 以及 `UNIQUE(source, source_msg_id)`，不改类型或语义。`source` 是不可变来源命名空间，不是 `source_code` 的重复字段。

| 新增字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `source_id` | `ID NULL` | 外键到 `integration_source.source_id`；为兼容旧行可空，新 T02 行必须填写 |
| `payload_hash` | `varchar(64) NULL` | 新 T02 行必须填写小写十六进制 SHA-256；旧行可空 |
| `payload` | `jsonb NULL` | 保存协议契约规定的原始消息封装；不是业务 DTO，绝不由 9 个接口返回 |
| `status` | `varchar(16) NOT NULL DEFAULT 'RECEIVED'` | CHECK `RECEIVED/PROCESSING/DONE/FAILED` |
| `processed_at` | `bigint NULL` | 延续现有 Inbox 的 epoch 毫秒；只有完成或最终失败时填写 |
| `last_error` | `text NULL` | 内部脱敏错误摘要；不得包含凭据或完整敏感载荷 |
| `lease_token` | `ID NULL` | 处理租约标识 |
| `lease_until` | `bigint NULL` | epoch 毫秒；与 `lease_token` 同空或同非空 |

索引：保留现有唯一键；增加 `(source_id, status, received_at, inbox_id)` 和 `(status, lease_until)`。相同 `(source, source_msg_id)` 与相同哈希是幂等重放；相同键但哈希不同必须保留原行、隔离新载荷并报告 `SOURCE_MESSAGE_CONFLICT`，禁止覆盖。解析失败只把 Inbox 置为 `FAILED`，不写设备成功状态、目标、轨迹、轨迹点或告警。

#### `device`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `device_id` | `ID NOT NULL` | 主键 |
| `source_id` | `ID NULL` | 外键到 `integration_source.source_id` |
| `external_device_id` | `varchar(128) NULL` | 与 `source_id` 同空或同非空 |
| `device_no` | `CODE(64) NOT NULL` | 平台展示编号，唯一但不作为主键 |
| `name` | `CODE(128) NOT NULL` | 设备名称 |
| `device_type_code` | `varchar(32) NULL` | 未确认类型为空，不映射成普通雷达 |
| `model` | `varchar(128) NULL` | 未提供时为空 |
| `vendor` | `varchar(128) NULL` | 未提供时为空 |
| `location` | `LOCATION NULL` | 仅存已确认 WGS-84 点 |
| `altitude_m` | `numeric(10,2) NULL` | 安装高度；与 `altitude_datum` 同空或同非空 |
| `altitude_datum` | `varchar(16) NULL` | 只允许已确认基准代码，例如 `AGL/AMSL`；未知为空 |
| `enabled` | `boolean NOT NULL DEFAULT true` | 台账启用状态，不表示在线 |
| `source_mode` | 来源公共列 | 必须与关联来源一致 |
| `owner_org_id/district_id` | 归属公共列 | 缺任一归属时不经本文 API 返回 |
| 公共列 | 可修改公共列 | — |

键与索引：`UNIQUE(device_no)`；部分唯一索引 `(source_id, external_device_id) WHERE source_id IS NOT NULL`；范围及排序索引 `(owner_org_id, district_id, device_no, device_id)`；筛选索引 `(source_id, device_type_code, enabled, device_id)`；`location` GiST 索引。经纬度还须校验经度 `[-180,180]`、纬度 `[-90,90]`。

#### `device_state`

兼容决议（P1-001）：[既有数据库设计稿](../数据库设计文档.md)第 6 节曾使用 `ONLINE/OFFLINE/ABNORMAL/UNKNOWN`，本文按阶段 1 计划将唯一目标字典替代为 `ONLINE/OFFLINE/DEGRADED/UNKNOWN`。阶段 2 的数据库 CHECK、查询筛选和全部 DTO 均不得接受或输出 `ABNORMAL`；现有仓库尚无 `device_state` 表或业务数据，因此不需要伪造历史数据迁移。若外部来源原码为 `ABNORMAL`，只能先保存在内部来源字段；没有经确认的映射时平台 connectivity 写 `UNKNOWN` 并记录原因，不能静默改写为 `DEGRADED`。

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `device_id` | `ID NOT NULL` | 主键且外键到 `device.device_id` |
| `connectivity` | `varchar(16) NOT NULL` | 只允许 `ONLINE/OFFLINE/DEGRADED/UNKNOWN`，无数据库默认值 |
| `work_state_code` | `varchar(32) NULL` | 保存已归一化或来源原码；未知为空 |
| `has_alarm` | `boolean NULL` | 三值语义，不能用默认 false |
| `health_code` | `varchar(32) NULL` | 未确认健康字典时保存可追溯代码，不推导 connectivity |
| `observed_at` | `TIME NOT NULL` | latest 行必须具有可信来源事件时间；未知时间的事实不得创建或更新本表 |
| `received_at` | `TIME NOT NULL` | 平台接收时刻 |
| `last_heartbeat_at` | `TIME NULL` | 只有实际心跳事实才填写 |
| `source_seq` | `bigint NULL` | 来源序号未知时为空，不自行生成协议序号 |
| `metrics` | `jsonb NULL` | 内部扩展指标，值必须保留单位和来源；当前 REST 不直接返回 |
| `unknown_reason` | `varchar(64) NULL` | connectivity 为 `UNKNOWN` 时必填；其他状态时必须为空 |
| 公共列 | 可修改公共列 | 无独立来源/归属列，访问范围继承 `device` |

索引：主键；筛选索引 `(connectivity, received_at DESC, device_id)`。每个已归一化设备状态事实先按去重规则追加 `device_state_history`；随后仅当 incoming `observed_at` 非空，且 latest 不存在或 incoming `observed_at > device_state.observed_at` 时创建/条件更新 latest。相等、更早或未知事件时间均不覆盖；并发更新必须以该严格大于条件作为原子守卫，`received_at/source_seq` 不参与胜负判断。

#### `device_state_history`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `state_id` | `ID NOT NULL` | 主键 |
| `device_id` | `ID NOT NULL` | 外键到 `device.device_id` |
| `inbox_id` | `ID NULL` | 外键到 `inbox_message.inbox_id`；非消息来源的受控合成记录可空 |
| `connectivity` | `varchar(16) NOT NULL` | 同 `device_state.connectivity` 字典 |
| `observed_at` | `TIME NULL` | 来源时刻 |
| `received_at` | `TIME NOT NULL` | 平台接收时刻 |
| `snapshot` | `jsonb NOT NULL` | 归一化状态快照对象；可含 `work_state_code/has_alarm/health_code/last_heartbeat_at/source_seq/unknown_reason/metrics`，不得含凭据 |
| 公共列 | 追加公共列 | 来源和范围均继承设备 |

索引：`(device_id, (COALESCE(observed_at, received_at)) DESC, received_at DESC, state_id ASC)`；`(inbox_id)`。历史只追加，不因新状态删除或改写。这里的 `COALESCE` 仅服务历史查询的筛选和稳定排序，不得复用于 `device_state` 的 latest 覆盖判断。

#### `target`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `target_id` | `ID NOT NULL` | 主键 |
| `target_no` | `CODE(64) NOT NULL` | 平台展示编号，唯一 |
| `object_type_code` | `varchar(32) NULL` | 未确认目标类别为空，不默认为无人机 |
| `subtype` | `varchar(64) NULL` | 未提供时为空 |
| `uav_sn` | `varchar(128) NULL` | 不全局唯一，不作为主键或合法性结论 |
| `first_seen_at` | `TIME NULL` | 最早可信事件时间；尚无可信时间时为空，不用接收时间代填 |
| `last_seen_at` | `TIME NULL` | 最新可信事件时间；与 `first_seen_at` 同为空或满足前者不晚于后者 |
| `source_mode` | 来源公共列 | T02 首批固定 `replay` |
| `owner_org_id/district_id` | 归属公共列 | 缺任一归属时不可见 |
| 公共列 | 可修改公共列 | — |

索引：`UNIQUE(target_no)`；范围及排序索引 `(owner_org_id, district_id, last_seen_at DESC NULLS LAST, target_id ASC)`；筛选索引 `(object_type_code, last_seen_at DESC NULLS LAST, target_id ASC)`。`uav_sn` 不建唯一约束。新目标可在事件时间未知时建立身份与来源 link，但 `first_seen_at/last_seen_at` 保持 NULL；后续只用可信事件时间更新最小/最大值，`received_at` 不参与。该摘要时间规则不改变下文 latest 必须严格晚于才覆盖的规则。

#### `target_source_link`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `link_id` | `ID NOT NULL` | 主键 |
| `target_id` | `ID NOT NULL` | 外键到 `target.target_id` |
| `source_id` | `ID NOT NULL` | 外键到 `integration_source.source_id` |
| `device_id` | `ID NULL` | 外键到 `device.device_id`；只有来源能明确关联设备时填写 |
| `source_session_key` | `CODE(128) NOT NULL` | 来自协议/回放契约的真实会话边界，不使用固定占位 |
| `external_target_id` | `CODE(128) NOT NULL` | 只在同一来源会话内唯一 |
| `protocol_version` | `varchar(64) NULL` | 未确认时为空 |
| 公共列 | 追加公共列 | 来源模式和范围继承 `target`；不得跨模式关联 |

键与索引：`UNIQUE(source_id, source_session_key, external_target_id)`；`(target_id, link_id)`；`(device_id, target_id) WHERE device_id IS NOT NULL`。应用事务必须验证关联设备属于同一 `source_id/source_mode`，且 link 的目标与来源模式一致。

#### `target_latest_state`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `target_id` | `ID NOT NULL` | 主键且外键到 `target.target_id` |
| `location` | `LOCATION NULL` | 无有效 WGS-84 点时为空 |
| `altitude_amsl_m` | `numeric(10,2) NULL` | AMSL 高度；缺基准不计算 |
| `height_agl_m` | `numeric(10,2) NULL` | AGL 高度；缺基准不计算 |
| `speed_mps` | `numeric(10,3) NULL` | 非负；单位固定 m/s |
| `heading_deg` | `numeric(6,2) NULL` | 范围 `[0,360)` |
| `classification_confidence` | `numeric(6,5) NULL` | 范围 `[0,1]`；不可由融合置信度代填 |
| `fusion_confidence` | `numeric(6,5) NULL` | 范围 `[0,1]`；不表示算法已验收 |
| `observed_at` | `TIME NOT NULL` | latest 行必须具有可信来源事件时间；未知时间的事实不得创建或更新本表 |
| `received_at` | `TIME NOT NULL` | 平台接收时刻 |
| `unknown_fields` | `jsonb NOT NULL DEFAULT '[]'` | 下文 `FieldIssueDto` 的数组形状；仅记录不可用字段及原因，不放业务值 |
| 公共列 | 可修改公共列 | 来源和范围继承 `target` |

索引：主键和 `location` GiST 索引。只有 incoming `observed_at` 非空，且 latest 不存在或 incoming `observed_at > target_latest_state.observed_at` 时，才创建或原子条件更新 latest。事件时间相等时保留首次接受的 latest；更早或未知时，有效位置可按去重规则追加 `track_point`，其余事实保留 Inbox，但都不得覆盖 latest。`received_at`、`point_seq` 和帧序号均不参与胜负判断。

#### `track`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `track_id` | `ID NOT NULL` | 主键 |
| `target_id` | `ID NOT NULL` | 外键到 `target.target_id` |
| `link_id` | `ID NOT NULL` | 外键到 `target_source_link.link_id` |
| `external_track_id` | `CODE(128) NOT NULL` | 只在 link 内唯一 |
| `started_at` | `TIME NULL` | 可信轨迹开始事件时间；来源时间未知时为空，禁止用接收时间代填 |
| 公共列 | 追加公共列 | 来源和范围继承 `target` |

键与索引：`UNIQUE(link_id, external_track_id)`；`(target_id, started_at DESC NULLS LAST, track_id ASC)`。应用事务必须验证 `track.target_id` 等于 link 指向的目标。未知 `started_at` 不阻止保存可追溯的轨迹身份和历史点，但不产生 latest 更新资格。

#### `track_point`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `point_id` | `ID NOT NULL` | 主键 |
| `track_id` | `ID NOT NULL` | 外键到 `track.track_id` |
| `inbox_id` | `ID NULL` | 外键到 `inbox_message.inbox_id` |
| `point_seq` | `bigint NOT NULL` | `>= 0`；轨迹内稳定持久化次序，重放不得重新编号 |
| `observed_at` | `TIME NULL` | 来源时刻未知时为空 |
| `received_at` | `TIME NOT NULL` | 平台接收时刻 |
| `location` | `LOCATION NOT NULL` | 只有完成可信 WGS-84 转换后才允许建点 |
| `altitude_amsl_m` | `numeric(10,2) NULL` | 缺 AMSL 依据时为空 |
| `height_agl_m` | `numeric(10,2) NULL` | 缺 AGL 依据时为空 |
| `raw_position` | `jsonb NULL` | 内部溯源值，不经本文 API 返回；不得包含凭据 |
| 公共列 | 追加公共列 | 来源和范围继承 track → target |

键与索引：`UNIQUE(track_id, point_seq)`；稳定查询索引 `(track_id, (COALESCE(observed_at, received_at)) ASC, point_seq ASC, point_id ASC)`；`(inbox_id)`；`location` GiST。无有效位置的报文只留 Inbox，不能插入空位置或零点。表达式中的 `COALESCE` 只定义历史点的查询次序，不赋予接收时间事件时间语义，也不参与 `target_latest_state` 覆盖判断。

#### `alarm`

| 字段 | 类型/可空 | 规则 |
| --- | --- | --- |
| `alarm_id` | `ID NOT NULL` | 主键 |
| `target_id` | `ID NULL` | 外键到 `target.target_id`；来源未提供可靠关联时为空 |
| `source_id` | `ID NOT NULL` | 外键到 `integration_source.source_id`；阶段 2 告警必须有明确来源 |
| `source_alarm_id` | `CODE(128) NOT NULL` | 来源范围内的稳定告警 ID |
| `alarm_type` | `CODE(64) NOT NULL` | 来源明确给出的类型或经确认映射后的平台代码 |
| `severity` | `varchar(16) NOT NULL` | `LOW/MEDIUM/HIGH/CRITICAL/UNKNOWN`；无确认映射时只能为 `UNKNOWN` |
| `occurred_at` | `TIME NULL` | 来源发生时间未知或不可信时为空 |
| `received_at` | `TIME NOT NULL` | 平台接收时刻 |
| `inbox_id` | `ID NULL` | 外键到 `inbox_message.inbox_id` |
| `detail` | `jsonb NULL` | 内部来源详情，不经本文 API 返回 |
| `source_mode` | 来源公共列 | 必须与来源、目标（若有）一致 |
| `owner_org_id/district_id` | 归属公共列 | 缺任一归属时不可见 |
| 公共列 | 追加公共列 | 告警事实只追加 |

键与索引：`UNIQUE(source_id, source_alarm_id)`；范围及排序索引 `(owner_org_id, district_id, received_at DESC, alarm_id ASC)`；`(target_id, received_at DESC, alarm_id ASC)`；`(inbox_id)`。本批次没有 `uav_event_id` 字段或外键。轨迹、轨迹点、设备 connectivity 和前端状态均不得触发 `alarm` INSERT。

### 3.3 归属与来源传播规则

- `device` 的归属来自已确认台账；不能从用户当前组织、来源名称或坐标反推。
- `target` 只有在来源/设备到归属元组存在单一、明确映射时才复制该元组；缺失或冲突时保持未知并从普通读取接口隐藏。
- `device_state/history` 通过设备继承范围；`target_source_link/latest_state/track/track_point` 通过目标继承范围。子表不能另设更宽范围。
- `alarm` 保存入库时确认的归属元组；若关联 target，二者元组必须相同。无法确认时告警可以为内部隔离事实，但不能被本文接口返回。
- 任何跨表关系都必须校验 `source_mode`。`replay` 记录不得关联 `live` 或 `mock` 根对象；数据库 CHECK 负责单行词汇，应用事务负责跨表一致性。

## 4. 权限与数据范围

### 4.1 权限码与端点映射

| 权限码 | 覆盖接口 |
| --- | --- |
| `device:read` | 设备列表、设备详情、设备状态历史 |
| `target:read` | 目标列表、目标详情、目标轨迹、轨迹点 |
| `alarm:read` | 告警列表、告警详情 |

有效权限的必要条件是：会话有效、当前用户状态有效、`app_role` 存在且 `enabled=true`、角色与权限有显式 `app_role_permission` 记录。角色名、`system_role=true`、旧 `org_id`、前端菜单或登录成功均不隐含权限。生产没有角色映射时返回 `403 FORBIDDEN`；只有 local/test 合成数据可显式建立角色权限和范围。

### 4.2 范围决策

1. 先校验动作权限；缺少动作权限返回 `403 FORBIDDEN`，不执行对象查询。
2. `scope_mode=NONE` 返回 `403 FORBIDDEN`。
3. `scope_mode=ASSIGNED` 时，至少存在一条用户范围记录才有效；缺少记录返回 `403 FORBIDDEN`。可见条件为业务根表 `(owner_org_id, district_id)` 与某一授权行完全相等。
4. `scope_mode=ALL` 只能由显式受控配置产生，可查看所有归属完整的记录；它仍不能查看任一归属列为 NULL 的记录。本契约不定义未知归属数据质量入口。
5. 父组织、父区域、同名组织和相邻区域均不自动扩张范围。请求中的 `owner_org_id/district_id` 只能进一步收窄有效范围。
6. 列表 `items`、`total`、详情、历史和嵌套资源必须复用同一范围谓词。禁止先算全局 total 再过滤 items，也禁止通过父对象可见性绕过子对象所属根对象的检查。
7. 已有动作权限但目标对象不存在或超出有效范围时，统一返回对应的 404 错误，隐藏对象是否存在；不能用 403 暴露存在性。

## 5. REST 通用契约

### 5.1 认证、封装与命名

- 基础路径保持 `/api/v1`，认证保持 `Authorization: Bearer <session_id>`。
- 成功：`{"ok":true,"data":...}`；失败：`{"ok":false,"error":{"code":"...","message":"..."}}`。成功不含 `error`，失败不含 `data`。
- JSON 字段使用 snake_case；所有 ID 使用字符串；所有新增 REST 时间字段使用 epoch 毫秒 JSON number。
- 列表统一返回 `PageDto<T>`：`items: T[]`、`page: integer`、`size: integer`、`total: integer`。四个字段始终存在，`total` 是应用范围与全部筛选条件下的精确计数。
- `page` 默认 1，必须 `>=1`；`size` 默认 20，必须在 `[1,100]`。非法值返回 `400 INVALID_PAGE`，不再静默夹紧。合法页码超过最后一页返回 200、空 `items` 和真实 `total`。
- 所有标量参数只能出现一次；除分页和时间参数使用各自专用错误码外，空字符串、纯空白、类型错误、未知枚举或超长代码返回 `400 VALIDATION_ERROR`。
- 时间筛选为闭区间。成对参数必须同时出现且起点不晚于终点，否则返回 `400 INVALID_TIME_RANGE`。本阶段不臆造最大时间窗；所有结果仍受分页上限和固定排序约束。
- 本契约不提供客户端 sort 字段。服务端必须使用下文固定排序和最终唯一 ID 打破并列，禁止依赖数据库自然顺序。

### 5.2 DTO 复用类型

`SourceRefDto`：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `source_id`、`source_code`、`name`、`source_mode` | 是 | 来源身份和模式 |
| `protocol_code`、`protocol_version` | 否 | 数据库为 NULL 时省略；不返回 `credential_ref` |

`LocationDto`：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `longitude`、`latitude` | 是 | 十进制度，分别位于 `[-180,180]`、`[-90,90]` |
| `coordinate_system` | 是 | 固定字符串 `WGS84` |

`FieldIssueDto`：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `field` | 是 | 当前 DTO 中未返回的字段名 |
| `reason_code` | 是 | `NOT_REPORTED/INVALID_VALUE/REFERENCE_UNKNOWN/TIME_UNTRUSTED/NOT_APPLICABLE/UNSUPPORTED` 之一 |

`field_issues` 始终按 `field ASC` 返回。它只描述当前状态的不可用测量字段，不能承载数据值或自由文本；latest DTO 的 `observed_at` 必须可信且非空，因此不得用 field issue 掩盖缺失事件时间。

### 5.3 空值在线格式中的表现

现有 Jackson 全局配置忽略 null，本文不更改该行为。字段表中“否”表示数据库为 NULL 时省略：

- 可选元数据省略表示未提供，不能推断类别、厂商、型号、序列号或协议版本。
- `location` 省略表示没有可返回的可信 WGS-84 点；经纬度不能只返回一项。
- 高度字段省略表示对应基准高度不可判定；不适用和基准未知由目标状态的 `field_issues` 细分。
- 历史 DTO 的 `observed_at` 省略表示来源事件时间不可用，`received_at` 仍必填；latest DTO 的 `observed_at` 必填，因为未知事件时间不能创建或覆盖 latest。轨迹点另返回只用于展示排序的 `sort_time/time_basis`，告警排序始终使用必填的接收时间。
- `latest_state` 省略表示尚无具备可信事件时间的状态记录；不能根据 Inbox 或接收时间合成一条 latest，也不能合成一条 `UNKNOWN` 状态。已建立 latest 中的 `connectivity=UNKNOWN` 只表示连接状态不可判定，其 `observed_at` 仍是可信事件时间。
- 可选布尔 `has_alarm` 省略表示未知，显式 false 才表示来源报告为否。

## 6. 9 个读取接口

### 6.1 `GET /api/v1/devices`

权限：`device:read`。

参数：

| 参数 | 必填/默认 | 语义 |
| --- | --- | --- |
| `page`、`size` | 否；1/20 | 通用分页 |
| `source_code` | 否 | 大小写敏感精确匹配 |
| `device_type_code` | 否 | 大小写敏感精确匹配 |
| `connectivity` | 否 | `ONLINE/OFFLINE/DEGRADED/UNKNOWN`；只匹配实际存在的最新状态行 |
| `enabled` | 否 | `true/false` |
| `owner_org_id`、`district_id` | 否 | 可单独或同时收窄，但不能扩大用户范围 |

固定排序：`device.device_no ASC, device.device_id ASC`。

`DeviceSummaryDto`：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `device_id`、`device_no`、`name` | 是 | 平台身份与名称 |
| `device_type_code` | 否 | 未提供时省略 |
| `enabled`、`source_mode` | 是 | 台账启用事实与来源模式；不等于在线 |
| `owner_org_id`、`district_id` | 是 | 返回记录必有完整归属 |
| `latest_state` | 否 | `DeviceStateDto`；没有状态行时省略 |

`DeviceStateDto`：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `connectivity`、`observed_at`、`received_at` | 是 | 连接状态、可信事件时间和接收时刻；时间均为 epoch 毫秒 |
| `work_state_code`、`health_code`、`unknown_reason` | 否 | 已记录代码或未知原因 |
| `has_alarm` | 否 | 三值布尔语义 |
| `last_heartbeat_at` | 否 | epoch 毫秒 |
| `source_seq` | 否 | 来源序号 |

### 6.2 `GET /api/v1/devices/{device_id}`

权限：`device:read`。路径 ID 去除空白后必须为 1–36 个字符；格式非法为 `400 VALIDATION_ERROR`，不存在或越权为 `404 DEVICE_NOT_FOUND`。

响应 `DeviceDetailDto` 包含 `DeviceSummaryDto` 的全部字段，另含：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `source` | 否 | `SourceRefDto`；设备未关联来源时省略 |
| `external_device_id`、`model`、`vendor` | 否 | 未提供时省略 |
| `location` | 否 | `LocationDto` |
| `altitude_m`、`altitude_datum` | 否 | 成对出现；高度单位为米 |
| `created_at`、`updated_at` | 是 | epoch 毫秒 |

内部 `credential_ref`、状态 `metrics` 和原始消息不返回。

### 6.3 `GET /api/v1/devices/{device_id}/states`

权限：`device:read`。父设备不存在或越权为 `404 DEVICE_NOT_FOUND`。

参数：

| 参数 | 必填/默认 | 语义 |
| --- | --- | --- |
| `page`、`size` | 否；1/20 | 通用分页 |
| `time_from`、`time_to` | 否；成对 | 过滤历史展示排序时间 `COALESCE(observed_at, received_at)` 的 epoch 毫秒闭区间 |
| `connectivity` | 否 | 四个固定连接状态之一 |

固定排序：`COALESCE(observed_at, received_at) DESC, received_at DESC, state_id ASC`。该表达式只用于历史查询，绝不复用于 latest 覆盖判断。

`DeviceStateHistoryDto`：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `state_id`、`device_id`、`connectivity`、`received_at` | 是 | 历史身份、状态与 epoch 毫秒接收时刻 |
| `observed_at` | 否 | 来源时刻 |
| `work_state_code`、`health_code`、`unknown_reason` | 否 | 从受控 snapshot 映射，不能透传任意 JSON |
| `has_alarm` | 否 | 三值布尔语义 |
| `last_heartbeat_at`、`source_seq` | 否 | 从受控 snapshot 映射 |

### 6.4 `GET /api/v1/targets`

权限：`target:read`。

参数：

| 参数 | 必填/默认 | 语义 |
| --- | --- | --- |
| `page`、`size` | 否；1/20 | 通用分页 |
| `source_code` | 否 | 通过来源映射精确筛选；同一目标只返回一次 |
| `device_id` | 否 | 通过来源映射精确筛选；仍执行目标范围过滤 |
| `object_type_code` | 否 | 精确匹配；未知类别不匹配任意代码 |
| `seen_from`、`seen_to` | 否；成对 | 过滤非空 `last_seen_at` 的 epoch 毫秒闭区间；未知时间目标不匹配该筛选 |
| `owner_org_id`、`district_id` | 否 | 仅收窄有效范围 |

固定排序：`target.last_seen_at DESC NULLS LAST, target.target_id ASC`。涉及来源映射的筛选使用 `EXISTS`，不得因多条 link 重复目标或抬高 total。

`TargetSummaryDto`：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `target_id`、`target_no` | 是 | 平台目标身份，ID 为字符串 |
| `first_seen_at`、`last_seen_at` | 否 | 可信事件时间，epoch 毫秒；尚无可信事件时间时同时省略 |
| `object_type_code`、`subtype`、`uav_sn` | 否 | 未提供时省略，不形成无人机或合法性结论 |
| `source_mode`、`owner_org_id`、`district_id` | 是 | 来源与完整归属 |
| `latest_state` | 否 | `TargetStateDto`；没有状态行时省略 |

`TargetStateDto`：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `observed_at`、`received_at`、`field_issues` | 是 | 可信事件时间、接收时刻均为 epoch 毫秒；问题列表可为空数组 |
| `location` | 否 | `LocationDto` |
| `altitude_amsl_m`、`height_agl_m` | 否 | 米；分别对应 AMSL/AGL |
| `speed_mps`、`heading_deg` | 否 | m/s 与度 |
| `classification_confidence`、`fusion_confidence` | 否 | `[0,1]`，互不替代 |

### 6.5 `GET /api/v1/targets/{target_id}`

权限：`target:read`。路径 ID 规则同设备；不存在或越权为 `404 TARGET_NOT_FOUND`。

响应 `TargetDetailDto` 包含 `TargetSummaryDto` 全部字段，并增加：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `source_links` | 是 | `TargetSourceLinkDto[]`；无映射时为空数组 |
| `created_at`、`updated_at` | 是 | epoch 毫秒 |

`TargetSourceLinkDto` 字段为：必填 `link_id/source_id/source_code/source_mode/source_session_key/external_target_id`；可选 `device_id/protocol_version`。数组固定按 `source_code ASC, source_session_key ASC, external_target_id ASC, link_id ASC` 排序。只返回与目标同模式且调用者可见的 link；异常跨模式关系不透传。

### 6.6 `GET /api/v1/targets/{target_id}/tracks`

权限：`target:read`。父目标不存在或越权为 `404 TARGET_NOT_FOUND`。

参数：

| 参数 | 必填/默认 | 语义 |
| --- | --- | --- |
| `page`、`size` | 否；1/20 | 通用分页 |
| `started_from`、`started_to` | 否；成对 | 过滤非空 `started_at` 的 epoch 毫秒闭区间；未知开始时间轨迹不匹配该筛选 |
| `source_code` | 否 | 通过 link/source 精确筛选 |
| `device_id` | 否 | 通过 link 精确筛选 |

固定排序：`track.started_at DESC NULLS LAST, track.track_id ASC`。

`TrackSummaryDto`：必填 `track_id/target_id/link_id/external_track_id/source_id/source_code/source_mode`；`device_id/started_at` 可选。`started_at` 为可信事件 epoch 毫秒，未知时省略。

### 6.7 `GET /api/v1/tracks/{track_id}/points`

权限：`target:read`。轨迹不存在、其目标越权或关系异常均返回 `404 TRACK_NOT_FOUND`。

参数：

| 参数 | 必填/默认 | 语义 |
| --- | --- | --- |
| `page`、`size` | 否；1/20 | 通用分页 |
| `time_from`、`time_to` | 否；成对 | 过滤历史展示排序时间 `COALESCE(observed_at, received_at)` 的 epoch 毫秒闭区间 |

固定排序：`COALESCE(observed_at, received_at) ASC, point_seq ASC, point_id ASC`。该表达式只保证历史点展示和分页稳定，不参与 target latest 覆盖判断。

`TrackPointDto`：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `point_id`、`track_id`、`point_seq` | 是 | 稳定身份与轨迹内次序 |
| `sort_time`、`time_basis`、`received_at` | 是 | `sort_time=COALESCE(observed_at,received_at)`，只用于历史展示；`time_basis` 为 `OBSERVED/RECEIVED`；时间均为 epoch 毫秒 |
| `observed_at` | 否 | 来源时刻未知时省略 |
| `location` | 是 | `LocationDto`；轨迹点永远不返回伪坐标 |
| `altitude_amsl_m`、`height_agl_m` | 否 | 米，基准不足时省略 |

内部 `raw_position` 和 Inbox 标识不返回。

### 6.8 `GET /api/v1/alarms`

权限：`alarm:read`。

参数：

| 参数 | 必填/默认 | 语义 |
| --- | --- | --- |
| `page`、`size` | 否；1/20 | 通用分页 |
| `severity` | 否 | `LOW/MEDIUM/HIGH/CRITICAL/UNKNOWN` |
| `alarm_type` | 否 | 大小写敏感精确匹配 |
| `target_id` | 否 | 精确匹配；不扩大 target 权限 |
| `source_code` | 否 | 精确匹配 |
| `received_from`、`received_to` | 否；成对 | 过滤必填 `received_at` 的 epoch 毫秒闭区间 |
| `owner_org_id`、`district_id` | 否 | 仅收窄有效范围 |

固定排序：`alarm.received_at DESC, alarm.alarm_id ASC`。

`AlarmSummaryDto`：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `alarm_id`、`alarm_type`、`severity`、`received_at` | 是 | 告警身份、明确类型/等级和 epoch 毫秒接收时间 |
| `target_id`、`occurred_at` | 否 | 无可靠目标或来源时刻时省略 |
| `source_id`、`source_code`、`source_mode` | 是 | 明确来源；不能由目标/轨迹推导 |
| `owner_org_id`、`district_id` | 是 | 返回记录必有完整归属 |

### 6.9 `GET /api/v1/alarms/{alarm_id}`

权限：`alarm:read`。路径 ID 规则同设备；不存在或越权为 `404 ALARM_NOT_FOUND`。

响应 `AlarmDetailDto` 包含 `AlarmSummaryDto` 全部字段，另含必填 `source_alarm_id/created_at` 和可选 `source_protocol_code/source_protocol_version`；时间为 epoch 毫秒。内部 `detail`、Inbox 标识和凭据引用不返回。

## 7. 错误码

| HTTP | 错误码 | 触发条件 |
| --- | --- | --- |
| 400 | `INVALID_PAGE` | `page<1`、`size` 不在 `[1,100]` 或分页值不能解析 |
| 400 | `INVALID_TIME_RANGE` | 时间参数只给一端、不能解析或起点晚于终点 |
| 400 | `VALIDATION_ERROR` | 路径 ID、布尔、枚举、代码或重复标量参数不合法 |
| 401 | `UNAUTHENTICATED` | Bearer 缺失、无效、过期，或会话对应用户已停用 |
| 403 | `FORBIDDEN` | 缺动作权限、角色不存在/停用、`scope_mode=NONE`，或 `ASSIGNED` 无范围元组 |
| 404 | `DEVICE_NOT_FOUND` | 设备不存在或不在有效范围；也用于设备状态历史的父资源 |
| 404 | `TARGET_NOT_FOUND` | 目标不存在或不在有效范围；也用于目标轨迹的父资源 |
| 404 | `TRACK_NOT_FOUND` | 轨迹不存在、所属目标越权或关系异常 |
| 404 | `ALARM_NOT_FOUND` | 告警不存在或不在有效范围 |
| 500 | `INTERNAL_ERROR` | 未分类服务端错误；返回脱敏固定消息，不回退为空分页或 `ok:true` |

`SOURCE_MESSAGE_CONFLICT` 是摄取/Inbox 的稳定内部错误码，不是本文 9 个 GET 的正常响应。所有错误消息可本地化，调用方只依赖稳定 code；日志和响应均不得包含会话 ID、凭据引用或原始敏感载荷。

## 8. 兼容与实现守卫

- 保留 `/api/v1`、Bearer `session_id`、`ApiResponse.ok/data/error`、snake_case、字符串 ID 和 `items/page/size/total`。新业务数据库时间为 `timestamptz`，响应统一转换为 epoch 毫秒；既有会话和 Inbox BIGINT 时间不原位改型。
- 设备和告警列表从固定空分页切换为真实查询；无匹配数据仍是 200 空分页。非法分页从原先静默夹紧改为 `INVALID_PAGE`，这是阶段 2 必须同步测试和消费者的有意收紧。
- 详情和嵌套资源先做动作授权，再在同一 SQL/查询边界应用范围条件。禁止先查询实体再在 Controller 层隐藏。
- `total` 与 `items` 必须共享完全相同的来源、筛选和范围条件；来源 link 查询使用 `EXISTS` 防止重复计数。
- 新 DTO 使用明确类型，不直接序列化持久化对象，也不返回 `Map<String,Object>`、Inbox payload、原始位置、告警 detail、设备 metrics 或凭据引用。
- `ONLINE/OFFLINE/DEGRADED/UNKNOWN` 是平台连接状态，不是厂商原码；映射缺少确认时使用有原因的 `UNKNOWN`，不能猜测。未知来源状态码保存在受控内部字段，不映射为在线。
- latest 写入只比较可信事件时间并使用严格大于守卫：相等保持首次，更早或未知只进入历史/Inbox。查询中的 `COALESCE(observed_at, received_at)` 只为稳定展示与分页存在，严禁抽成可被 latest 更新复用的“统一时间”。
- T02 回放只可创建 `replay` 数据。配置、适配器或数据不匹配时明确失败，不降级到 mock，不冒充 live。
- 数据库 CHECK、FK、唯一键和应用事务共同保障完整性；涉及 PostgreSQL/PostGIS 的约束、表达式索引和迁移必须在阶段 2 的隔离数据库验证，H2 结果不能替代。

## 9. 后续实现验收要点

- 从空库和含 V1/V2 脱敏结构的升级库分别执行两个新迁移；证明 V1/V2 未变化、批次顺序正确、角色兼容回填默认拒绝。
- 覆盖无会话、无权限、角色停用、NONE、ASSIGNED 无元组、精确元组、显式 ALL、未知归属，以及详情越权统一 404。
- 对每个分页接口验证默认值、边界、非法值、超末页、固定排序并列项和同范围 `total`。
- 验证重复来源映射、重复轨迹点、重复告警被唯一约束阻止；相同 Inbox 键不同哈希不覆盖原事实。
- 分别验证 device/target latest：首个可信事件时间可创建、严格更新可覆盖、相等时间保持首次、较早时间只追加历史、未知事件时间只追加历史或保留 Inbox；即使后到记录的 `received_at` 更晚也不得覆盖。
- 验证历史查询的 `COALESCE` 筛选与稳定排序不会改变 latest；验证空 latest 与显式 UNKNOWN、null 与 0、false 与未知、observed/received 时间、AGL/AMSL 及无效坐标均按本文语义返回。
- 验证仅插入目标、轨迹或轨迹点不会产生 `alarm`；本阶段没有真实连接、控制、反制或业务写接口。
