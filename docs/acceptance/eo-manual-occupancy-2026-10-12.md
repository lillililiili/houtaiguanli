# 人工光电任务占用修复

日期：2026-10-12（美国东部）；页面业务时间为北京时间。

## 问题和修正

截图对应原库 `houtaiguanli`：模拟光电 01 的人工任务关联目标 20261011-033，全量光电的人工任务关联 20261011-041。两条任务成功开始后停止回报，目标观测也过期，但仍为 OPEN。调度器此前仅维护 AUTO 任务，MANUAL 不进入持续性检查；新目标无法分配，原画面也因证据过期不能播放。

`EoTrackingRepository.openTasks()` 返回人工及自动 OPEN 任务；`EoTrackingScheduler.poll()` 对人工任务执行原持续性策略，即使自动启动关闭也维护。新鲜同任务回报可以维持旧融合位置下的观察；无自动需求或自动暂停不取消有效人工任务。需要停止时沿用指令、回执及受限重试，未确认前保持占用，不更改告警或风险结论。

`EoDispatchService.view()/occupationState()` 在原 message 字段中显示有权查看的设备名称、关联目标及状态。原请求、切换确认、处置保护、来源及数据范围保持；GET 不下发指令。前台共享 EoDispatchControls 直接消费，后台无独立消费者，接口结构未变。

## 验证

- 先在隔离 PostgreSQL/PostGIS 运行 5 项新回归：3 项按预期失败（2 个目标类别的 MANUAL 任务仍 OPEN、1 项占用文案缺失），2 项保持性用例通过。
- 修复后 EoTrackingPostgresTest 34、EoTrackingCommandSafetyTest 23、EoTrackingPolicyTest 6，共 63 项通过，无失败、错误或跳过。覆盖停止回执才释放、重复/迟到回执不能释放新任务、停止重试上限、权限/来源、并发、自动开关关闭、有效人工回报以及第一条回执等待期。
- 模拟器 `python3 -m unittest discover -s tools/device-simulator/tests -p 'test_eo*.py'`：47 项通过，包含模拟器重启后同任务 ENDING 停止回执恢复。
- `bash ./mvnw -q -DskipTests package` 通过；未改前台源码，无新增迁移。
- 初次测试遇到旧 target/classes 遗留的重复迁移资源，clean 后正常；没有修改历史迁移或关闭校验。

## 当前运行环境及浏览器

续接时运行目录已改为 `/Users/frank/git/houtaiguanli-latest/server`，实际数据库为 `houtaiguanli_fresh3`，与截图的原库不同。首次尝试在原库加载时被迁移校验阻止，未改迁移历史；随后按运行日志恢复到实际当前库，加载当前工作区构建的 JAR，显式保持 local,qa 与 dev-seed=false，readiness=UP。

后端重启期间模拟器因连接中断停止，随后通过原 `/api/start` 入口恢复原场景，批次 `sim-1012215914-eb16`；没有手写成功回执或清空任务。当前全量光电跟踪目标 20261013-010（任务 87c1bb92-9644-49fd-a8f3-661119782722），光电 01 跟踪目标 20261013-015（任务 f3202c46-0b90-4d1b-ac8d-0079c3ad8e8c），均有成功开始回执和持续回报、模拟器推流。

浏览器使用已有管理员账号正常重新登录并刷新融合感知。目标 010 展开实时视频后显示“测试视频·非现场”“直播中”，video.readyState=4、960×540、paused=false，播放时间从 26.708 秒增长至 35.541 秒。切换目标 009 后旧播放器销毁（video 元素为 0），等待提示明确显示两台设备各自关联 010、015；未自动抢占或触发核实、反制、通知。截图位于业务仓库 `outputs/eo-manual-20261012/video-playing.png` 与 `waiting-devices.png`。

当前保留用户原持续模拟场景运行。原库旧记录没有被批量修改；当前环境的历史及离线设备停止待确认记录保持。以上为本机模拟、真实 PostgreSQL/MQTT/媒体服务和浏览器验证，未进行真实光电硬件或现场视频验收。
