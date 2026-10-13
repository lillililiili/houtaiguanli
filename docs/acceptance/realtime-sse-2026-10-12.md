# 前后台统一 SSE 更新（2026-10-12）

## 实现

前台 `demo-ronghe/dongying-vue` 与管理端 `ruoyi-ui` 各自在当前登录会话内共享一条 `/api/v1/realtime/events` 连接。数据库提交后经 LISTEN/NOTIFY 发出主题，SSE 通知页面按现有权限、筛选和分页重读。继续使用 Bearer 数据库会话；设备侧 MQTT/TCP 协议、来源范围及业务动作授权不变。

删除后台异常工作台、运维消息、调测，以及前台设备检查、办理进度、融合感知和大屏的常驻重复数据轮询。自动读取使用 quiet 模式，不驱动手动刷新按钮的 loading，保留已读内容、筛选、分页、选中项和未保存表单。瞬时失败保留内容并提示错误；权限失效仍清除不可见数据。首次读取、人工查询和提交保持正常进度反馈。

隐藏页面释放连接，恢复可见或重连后补读；网络故障退避重连，45 秒无消息或心跳判为停滞。SSE 不可用或数据库监听中断时，每 15 秒统一触发静默兜底，恢复后停止。读取失败退避重试，卸载后迟到响应不重建计时器；增加跨网络分块的 CRLF 解析验证。

后端增加 `status {listening,at}` 事件，避免 SSE 仍有心跳但数据库监听失效时页面停止更新。ready 与监听状态切换串行，避免新连接收到倒序状态。追加 `V202610129001__realtime_workflow_topics.sql`，补齐设备配置、处置单关联和通知、自动设备检查（`plan_check`）、融合配置（`fusion`）通知，不改旧迁移。

## 保留的时间任务

| 场景 | 原因 |
| --- | --- |
| 后台异常恢复核验、心跳/指令超时、研判、通知调度 | 推进既有业务事实，不属于页面刷新 |
| 告警观测有效期、短信/电话 WATCHING 阶段 | 时间经过可能改变当前结论；页面只回读，不自动确认或执行 |
| 任务起止、有效空域版本、北京时间跨日 | 可能没有写库事件；融合感知最多每 60 秒只补读计划/空域/当前风险投影 |
| 连续目标上报中的当前航线风险 | 保持已有每 5 秒核对当前影响约定 |
| 已打开的航线风险、设备趋势窗口 | 保留 30 秒、60 秒的静默时间投影更新 |
| 活跃光电追踪、展开视频的关联与媒体有效性 | 部分状态在进程内或外部服务中，无数据库事件；保留 5 秒检查，闲置追踪不轮询 |
| 急停请求结果未知、读取失败 | 保留只读确认和重试，不重复发送受控命令 |

未注册到前台路由的旧 DevicesPage/MonitorPage/CommissionPage 不作为现用页面改造；设备协议所需查询定时器不变。

## 验证结果

- 管理端：`npm test -- --maxWorkers=2 --minWorkers=1`，48 文件、331 项全部通过。覆盖静默刷新、备注保留、SSE 合并、断线兜底、权限失效及卸载清理。最后设备详情补充后另跑 deviceAccess/deviceDeletion，共 23 项通过。`npm run lint`、`npm run build` 通过。
- 前台：realtimeRecovery、realtimeVisibility、situationApiContract、advisoryRecovery、targetVideoContract、flightPlanNavigation、flightDetailTime、airspaceRiskDetailSync、riskTodayKpis、bigScreenStatScope 共 10 个测试文件由 `node --test` 运行，51 个顶层子测试通过。其中融合感知脚本包含 118 条断言；补充后 realtimeRecovery 41 条检查及 advisoryRecovery 的观察期检查通过。`node tools/scan.cjs`、`npm run build` 通过。
- 前台全量初次执行共 209 个顶层子测试、9 项失败；其中本次改造相关的 4 项已修正并单独复验。剩余 5 项已用改动前代码对照复现：alarmEscalation 旧文案断言、pilotContactMissing 两条文案/源码断言、riskNotificationAccess 的 riskGroups 测试依赖、statsDateRange 的源码提取。没有改动这些无关业务或降低断言。
- 后端：AuthApiTest 9 项通过；RealtimeHubTest 2 项、RealtimePostgresTest 3 项通过，验证通知格式、提交合并、回滚不通知、零行操作不通知、11 张新增绑定表、监听断线及恢复。独立 PostgreSQL 16/PostGIS 3.5 数据库完整应用 186 个迁移，包含 public 下真实触发器。最终 `mvnw.cmd -Dtest=RealtimeHubTest,RealtimePostgresTest package` 通过。
- 大屏最终补充回归：`node --test tools/bigScreenStatScope.test.cjs` 5 项通过，覆盖首次请求失败后静默重试保留错误提示、成功后替换内容、后续请求期间保留快照，以及统计尾部事件补读。最后一次前台构建通过。
- 首轮浏览器检查：检查后台设备/异常工作台和前台融合感知的读取及展开状态。曾观察到离线地图加载超时及大屏接口超时，首轮未将大屏端到端验收标为通过；后续重启与补验结果见下节。

## 重启后的补充验收

2026-10-12 已部署经过测试的独立后端 JAR，运行于 8081，local、qa profile，保持 `app.dev-seed.enabled=false`。5173 业务前台与 5175 管理端已加载本次前端代码；服务就绪检查为 UP。运行库启动时成功校验 188 个迁移，版本为 `202610129003`。

补验修复了实际数据量下的大屏超时：

- 大屏趋势及侧栏统计显式传入 `include_observations=false`，不再计算页面未使用的全量轨迹时长、距离指标；完整运行统计及导出仍走原有计算。
- 追加 `V202610129003__realtime_assessment_read_indexes.sql`，补齐目标、计划最新研判及时间查询索引；最新研判的后继记录探测保留相关子查询，避免 PostgreSQL 对大量同主体历史进行高成本的哈希反连接比较。模式、时间、来源、权限及同时间 ID 排序规则保持不变。
- local 数据源连接关闭 JIT，避免交互式查询反复编译复杂权限谓词。Docker PostgreSQL 的共享内存默认从 64 MiB 调整为 256 MiB（`DB_SHM_SIZE` 可覆盖），解决并发查询的 `could not resize shared memory segment` 错误。重建的是原数据库容器，保留 `deploy_pgdata` 数据卷，没有清空或补写业务数据。

追加验证：

- `DashboardSnapshotApiTest,RealtimeHubTest,RealtimePostgresTest`：15 项通过；独立 PostgreSQL 16/PostGIS 3.5 完整应用 188 个迁移，打包通过。
- `LegalityReviewApiTest,DashboardSnapshotApiTest,RealtimeHubTest`：68 项通过（56 + 10 + 2）。初次重新打包被 Windows 运行中 JAR 文件占用阻止；停止原进程后使用相同已测试代码完成打包，运行文件另存，不再覆盖正在使用的构建产物。
- 隔离 PostgreSQL 另用 4,000 条生成数据验证最新研判查询，覆盖 TARGET/PLAN、ACTIVE/SHADOW、时间并列、不同编号和来源、空关联及时间/来源筛选；与独立窗口排名结果一致，得到四组共 156 条记录。
- 前台 `bigScreenStatScope.test.cjs,realtimeRecovery.test.cjs` 共 6 个顶层测试通过，构建通过。两仓库最终 `git diff --check` 无空白错误。首轮已记录的 5 项无关前台基线失败仍保留，不宣称全量测试全部通过。

浏览器和运行证据（下述时间为本机 UTC-04:00）：

- 大屏已显示真实统计和底图，无接口超时提示：今日目标 243、今日告警 30、今日待研判 105、设备 17；管理端设备总数同为 17，观察时在线 0、离线 17。数据来自既有验收记录，模拟标识保留，没有将离线设备伪装为在线。
- 05:36–05:42 的访问日志共记录 70 次大屏快照读取，全部 HTTP 200；耗时最短 3.59 秒、最长 7.19 秒、平均 4.81 秒，低于现有 15 秒客户端超时。访问日志仅记录时间、方法、路径、状态、耗时，不记录令牌、请求体或查询参数。
- 保持前后台页面打开并再次重启 API：05:40:51 大屏保留原快照并显示“将自动重新读取”，05:41:17 无人工刷新即恢复，错误提示消失。后台“刷新信息”“刷新”按钮在断线及恢复后均无 `is-loading`、未禁用，已打开内容保留。
- 数据库容器重建时，LISTEN 在 04:54:41 检测到断线，04:54:56 自动恢复；对应的状态通知、兜底与恢复补读另有自动化回归覆盖。
- 未执行接手、办结、通知、反制或其他设备控制。客户端主动中断 SSE 时仍可出现既有 IOException / 响应转换错误日志；观察到连接及页面能恢复，这一日志降噪未包含在本次修改中。

## 设备位置预览闪动补充修复

用户补充发现设备详情地图约每 3 秒重载。原因是详情 SSE 合并更新间隔为 3 秒，每次详情响应替换对象后，`devicePosition` 生成新数组；地图监听数组引用并调用销毁重建，即使实际坐标没有变化也会触发。此前仅检查刷新按钮未覆盖这一地图生命周期问题。

`DeviceLocationMap` 现仅在容器出现、恢复有效坐标或显式地图重试时创建底图。设备 ID、名称和规范化后的经纬度分别更新标记；同一设备的自动更新保留当前视角，坐标未变时不移动标记。更换设备及“回到设备”主动定位；包外坐标移除标记并提示，缺失或坐标系无效时释放地图，组件卸载取消异步加载和观察器。

回归先在旧实现复现：5 次详情对象更新触发 6 次地图资源加载。修复后两组不同位置和编号均只加载一次；测试同时覆盖名称变更、数值/字符串坐标等价、位置变化、覆盖范围、无效坐标恢复与卸载清理。地图专项 12 项通过；管理端完整 49 文件、342 项通过，`npm run lint`、`npm run build` 通过。浏览器在设备详情放大两级后重新读取详情，保留放大后的视角、无地图加载遮罩。

## 发布要求

配套发布两个前端构建及新后端包，后端启动由 Flyway 应用新增迁移。代理保持 SSE 不缓冲并允许长连接；服务返回 `Cache-Control: no-store`、`X-Accel-Buffering: no`。

本地后端已完成重启并加载上述版本；验收服务继续保持 `app.dev-seed.enabled=false`。其他部署环境仍需发布配套前后端构建并应用迁移，不能用本机验收替代其他环境发布验证。
