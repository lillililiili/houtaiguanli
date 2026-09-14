# 阶段 13（处置授权域）自动决策记录

| 编号 | 决策 | 理由 |
| --- | --- | --- |
| 13-1 | `disposal_policy demo-v1` 全部参数 `schema_status=DEMO`，客户 Q5 答复前不得标 CONFIRMED | 授权条件、审批依据、时限均未确认 |
| 13-2 | 两人规则、时限、是否要求已核实事件、同主体并发授权上限全部走 `disposal_policy.params`，代码无裸阈值 | 与规则引擎/融合参数同一原则 |
| 13-3 | 执行只经协作者 A 的 `LingyunControlService.enqueue`（协议 B）；四通道反制适配器无执行能力时返回 409 `DEVICE_CONTROL_UNAVAILABLE`，授权保持 APPROVED，不伪造回执 | 不在 A 的协议面之外另开通道；不猜厂家指令 |
| 13-4 | 停止 = 撤销授权 + 尝试设备急停；急停不可用只记事件 `DEVICE_STOP_UNAVAILABLE`，不阻塞撤销 | A 的急停接口现返回"设备协议未提供" |
| 13-5 | 授权编号 `AUTH-YYYYMMDD-NNNN`，由 `disposal_no_counter` 行锁递增生成 | 可读、按日唯一、并发安全 |
| 13-6 | `HandoffRules` 的处罚交接前提改为"该事件存在 COMPLETED 授权" | 原"一律 409"是因为没有可信完成事实，现在有了 |
| 13-7 | 前端只启用既有禁用按钮、填充既有"未建设"区块、新增一个授权弹窗；不改页面结构 | 用户约束 |
| 13-8 | B 线迁移按实际日期编号，今日用 `V202609070101–0199` 段 | 避开 A 同日的 0081–0084（决策 10-14） |
| 13-9 | 经协议 B 自动执行时，执行人需同时持有 `disposal:execute` 与 A 的 `devices.op`（`LingyunControlService.enqueue` 自校验） | 不绕开 A 的设备控制面权限；角色矩阵里给"处置执行"角色同时配两项 |
| 13-10 | 授权 DTO 增只读字段 `device_stop_result ∈ NOT_ATTEMPTED|EXECUTED|UNAVAILABLE`（由事件流推导），前端在 `STOPPED` 旁必须带限定语："授权已撤销；设备急停已执行 / 未执行（协议未提供）" | 审查 13 第 1 轮 P2-1：仅靠一条事件区分"设备真停了"与"授权撤了设备可能还在动"，对有法律后果的动作是误读源 |
| 13-11 | 停止路径只捕获 A 急停端点的 `CONTROL_NOT_ENABLED`（能力不存在）记 `DEVICE_STOP_UNAVAILABLE`；`LingyunControlService` 的同码"设备未登记凌云 MQTT"是可补救的配置遗漏，作为 `DEVICE_NOT_BOUND` 单独记事件并在 DTO/页面区分（`device_stop_result` 增 `NOT_BOUND`） | 审查 13 第 2 轮 P2-1：同一错误码两种含义，压成一句"协议未提供"会把可补救原因显示成不可补救 |
| 13-12 | 本期 LINGYUN_B 的四种处置指令码（60002/60003/70001/5000x）被 A 的 `LingyunControlEnvelope.family()` 有意拒绝（`PROTOCOL_UNSUPPORTED`，厂家未确认设备类型缩写）：执行仍从 `command_map` 取码调 A，A 拒绝时返回 409 `DEVICE_CONTROL_UNAVAILABLE` + 同名事件，授权保持 APPROVED；DTO 增 `execution_block_reason ∈ DEVICE_CAPABILITY|PROTOCOL_NOT_OPENED|NOT_BOUND`（由事件 note 里 A 的原始码推导），页面分别显示"设备不支持自动执行 / 指令码未开放（等厂家确认）/ 设备未登记凌云连接" | E1 报告：LINGYUN_B 本期一条都发不出；口径与 13-3 一致，A 开通映射后不改代码自动生效；三种阻塞原因补救方不同，必须能区分 |
| 13-13 | `HandoffRules.requireKindSupportsType` 放行 `(UAV_EVENT, UAV_PUNISHMENT)`，与 `requirePrerequisite`（存在 COMPLETED 授权）一起构成处罚交接的前提 | 只改前提不放行组合仍是 400，交接实际走不通 |
| 13-14 | `execution_block_reason` 增 `DEVICE_OFFLINE`（A 的 `DEVICE_NOT_OPERABLE`：设备未启用或不在线，补救=等上线/去启用），事件 `DEVICE_OFFLINE`；`DEVICE_NOT_FOUND` 与指令码/设备类型不匹配（`VALIDATION_ERROR`）属调用方错误，原样透出为 404/400，不落事件也不改授权状态；判定按 A 的错误码 + 调用点，不按 HTTP 状态码（`PROTOCOL_UNSUPPORTED` 是 400） | 审查 13 第 4 轮 P2-1：设备离线是真实使用中最常见的失败，塞进其它取值会把操作者引向错误的下一步 |
| 13-15 | 迁移里 JSON/JSONB 列的插入表达式只要含 `||`、`CONCAT` 或函数调用，必须显式 `CAST(... AS JSON)`；字面量可不 CAST | 助手在真实 PG 复现 0102 的 `disposal_policy.params` 用 `||` 拼接被拒（H2 宽松不报），整条迁移失败、PG 库起不来 |
| 13-16 | `DisposalExpiryJob` 是正式能力不是演示设施：生产隔离测试只断言"缺省不注册"与"开关打开时恰好注册一个"，不再强开开关要求不注册；执行时限的安全闸门在 `execute` 校验 `valid_until`（过期 409），到期任务只做状态卫生，部署文档建议生产打开 | 助手指出与 9-16 同理；强行要求生产不注册等于关掉时限执行 |
| 13-17 | 到期任务不建 `disposal_lease` 表：并发保护做进条件更新（`rowcount==1` 才写 EXPIRE 事件，同事务），多实例不会产生第二条事件 | 租约只省无用功，正确性来自条件更新；不往阶段 7 的租约表伸手 |
| 13-18 | 告警页两项 KPI 口径为"当前进行中"（`EXECUTING` 计数，副标题"另有 N 起已批准待执行"），不是当日计数 | 列表接口无日期参数；KPI 标签本就是"反制中/干扰中" |
| 13-19 | 信号干扰不新增按钮：与"发起联动反制"共用同一入口，弹窗内选择动作类型（反制/干扰） | 13-7 禁止改页面结构 |
| 13-34 | `COUNTERMEASURE` 进入 `COMPLETED` 后（设备回执或人工成功）自动生成一条 `JAMMING` 授权：复制设备/通道，沿用原申请人与批准人，状态直接 `APPROVED`，不再二次审批；`chained_from_authorization_id` 唯一。设备通道尝试用原执行人下发，受阻则停在已批准；人工通道等人登记结果。已有任一 `JAMMING` 或已经链式过则跳过。失败不回滚反制完成。告警列表 `state` 仍是核实结论，展示列按授权/交接推导反制中/已反制/干扰中/已干扰/已移送处罚 | 产品主线「一次联动反制 → 先反制再自动干扰」；不改 `uav_event` 状态机 |
| 13-20 | 编号：当日计数行在独立事务（REQUIRES_NEW）用 `INSERT … SELECT … WHERE NOT EXISTS` 建行，主事务里 `SELECT … FOR UPDATE` + `UPDATE +1`；号段允许空缺 | H2 PG 兼容模式不解析 `ON CONFLICT`；PG 下并发首插撞唯一键会把整个事务打成 aborted，独立事务只废内层；编号要求不重复不要求连续 |
| 13-21 | `allowed_actions` 按状态逐一断言且按权限裁剪（只有执行权只得 EXECUTE、只有停止权只得 STOP） | E1 注入证伪发现原用例只测 REQUESTED 状态，漏掉权限判断也能绿；否则只读用户会在 APPROVED 详情页看到可点的"执行" |
| 13-22 | 事件词表加 `PROTOCOL_NOT_OPENED`（A 返回 `PROTOCOL_UNSUPPORTED`），`DEVICE_CONTROL_UNAVAILABLE` 只表示设备无自动执行能力（4CH，B 侧预检）；四种 `execution_block_reason` 与事件一一对应：DEVICE_CONTROL_UNAVAILABLE→DEVICE_CAPABILITY、PROTOCOL_NOT_OPENED→PROTOCOL_NOT_OPENED、DEVICE_NOT_BOUND→NOT_BOUND、DEVICE_OFFLINE→DEVICE_OFFLINE | 审查 13 第 7 轮补充：由 `event_kind` 推导时一种事件不能对两种原因；一一对应最不易被改回二分 |
| 13-23 | `device_stop_result=EXECUTED` 本期不可达（设备协议无急停，平台拿不到"确已停止"的正面证据）；非设备通道恒 `NOT_ATTEMPTED`，设备通道无证据为 `UNAVAILABLE`；`EXECUTED` 保留给 A 日后实现急停时配套的正面事件 `DEVICE_STOP_EXECUTED` | E1 发现原推导会让人工执行的授权显示"设备已停"，正是 13-10 要防的误读 |
| 13-24 | 主体支持：`UAV_EVENT` 全部动作；`TARGET` 只允许 `DISPERSAL`（策略 `requires_confirmed_event=false`，校验目标存在且在调用者范围元组内）；`RISK` 不支持（400 `SUBJECT_KIND_NOT_SUPPORTED`；决策 18-14 定死，见下） | 态势页"派发驱离"以目标为主体；风险主体无可信状态来源 |
| 13-25 | 处罚交接：前提（存在 COMPLETED 授权）通过后，本期返回 409 `HANDOFF_MATERIALS_NOT_DEFINED`（"处罚交接的材料包尚未定义"），不硬塞风险形状的快照；材料包内容归下一阶段"处罚案件"与客户 Q4/Q7 | 送交公安的材料包是业务决定；诚实的 409 好过落到"源对象不存在"的 404 |
| 13-26 | 授权 DTO 回填 `requested_by_name / approved_by_name`（联表 `app_user`） | 页面显示审批人姓名而非 ID |
| 13-24（补充） | TARGET 主体的前置条件：目标存在、在调用者范围元组内、经 `target_current_alias` 解析到存活目标（被合并的历史 ID 解析到幸存者）、`target_latest_state` 在 C03 `fresh_seconds` 内（否则 409 `TARGET_NOT_ACTIVE`）；注释里"没有可信归属"的说法已过时（阶段 8/9 起 `target` 有 `owner_org_id/district_id`），改为只保留"状态来源"这一半的前置 | 审查 13 第 9 轮：元组校验只解决可见性，不覆盖目标是否仍活跃/已被合并 |
| 13-27 | 演示种子在库里没有已启用设备时把 LINGYUN_B 样例降为 MANUAL 通道，不留 null `device_id` | 助手发现无设备行的库启动即撞 `ck_stage13_authorization_device`；演示少一个样例好过起不来 |
| 13-28 | 并发用例的判据：审批并发看 APPROVE 事件恰一条（不只看状态）；编号并发看唯一性 + 连续无洞（不看 count）；并发夹具的连接池要大于并发数，否则测的是连接池 | 助手第三轮方法说明，记为后续并发用例的标准写法 |
| 13-29 | 执行受阻的四种原因在调 A 之前用 A 的公开判据自检（family 映射 / `mqtt_device_binding` / `ops_device` 启用与在线 / 4CH 预检），不捕获 A 的异常；顺序：协议未开通 → 未绑定 → 离线，多个同时成立时报"其补救是其它补救前提"的那条 | E1 实测：A 的 `enqueue` 带 @Transactional，同事务内抛异常把事务标 rollback-only，"捕获后记事件"提交不了变 500；自检还绕开 `CONTROL_NOT_ENABLED` 一码两义 |
| 13-30 | 阶段 8 回放种子启动时先查 `alreadyLoadedV2()`，已灌过（含旧构建灌的）就跳过并告警，不重灌 | 升级路径实测：`uav_stage10_verify` 由 `1692e10` 之前的构建灌过数据集，新构建同键载荷哈希不同，启动撞 `SOURCE_MESSAGE_CONFLICT`；用户本地库同样会中招。领导修 `LocalStage8FusionReplaySeeder` + 用例 `skipsReloadWhenDatasetAlreadyLoadedByAnOlderBuild`（4/4） |
| 13-31 | TARGET 主体新鲜度用 C03 `fresh_seconds`（生效规则集），不在 `disposal_policy` 另放一份；取不到阈值或目标无观测记录一律 409 `TARGET_NOT_ACTIVE`；落库 `subject_id` 用别名解析后的存活目标 | 同一事实只留一个来源，避免"合法性说失效、处置说还能打"的分歧；缺证据往安全方向倒；旧 ID 落库会让 `max_active_per_subject` 查不到自己（E1 实测） |
| 13-32 | `R__stage13_disposal.sql` 的触发器与 CHECK 包进 `to_regclass` 守卫，表不存在时跳过 | 升级回归（Stage9PostgresTest 只迁到 073.5/074）会先于 0102 跑本 R__；后续凡引用"晚于既有升级用例目标版本"的表的 R__ 都照此写 |
| 13-33 | 两人规则的第二个账号本轮不由 B 线代建：角色矩阵接口只接受 18 个 MODULE 码且要求整矩阵，ACTION 码（含 `disposal:*`）没有授予入口，超级管理员只能有一个（`SUPER_ADMIN_ASSIGNMENT_FORBIDDEN`）；直接往验收库插用户/会话被本会话的操作策略拦下 | 属既有缺口（阶段 4/5/7/9 的 ACTION 码同样如此），列入"小接线"待办：角色矩阵增加动作权限授予。浏览器验收只走单人可达的路径（申请/撤销/处罚页记录/交接 409），审批→执行→四值显示以 `Stage13PostgresTest` 9/9 与 H2 用例为证据；给用户留一份夹具 SQL 自行执行后可补全 |
| 13-32（修订） | 触发器与 CHECK 从 R__ 移到 PG 专属版本化迁移 `V202609070103__stage13_disposal_pg.sql`；R__ 只留无表依赖的函数 | E1 与审查第 13 轮：R__ 在"先停在中间版本再前进"的库上会被记为已应用而不再重跑，`to_regclass` 守卫会让只增保证静默丢失；版本化迁移按序只跑一次且表必已存在。规则：PG 专属的触发器/约束一律用版本化迁移，R__ 只放幂等且无表依赖的定义 |
