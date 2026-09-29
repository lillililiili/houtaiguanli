# 运维消息与设备调测衔接实施计划

**目标：** 将已确认的现有页面设计接入真实后端，消息定位待办设备，处理过程留痕、恢复核验后完成。

**架构：** 在现有设备域追加运维生命周期及个人已读记录；既有任务和通知历史保持。管理端复用设备调测页，服务端返回状态和 allowed_actions，前端不自行推定恢复。Java 17 / Spring Boot / PostgreSQL / Vue 3 / Element Plus。

## 约束

- 不修改已应用迁移；新版本检查并行工作后追加；不覆盖空域、规则管理等已有未提交内容。
- `/api/v1`、Bearer 数据库会话、snake_case、字符串 ID、epoch 毫秒、幂等键、版本检查保持。
- 历史 HANDLED 是旧反馈，展示为 LEGACY_HANDLED，不升级为恢复完成。
- 点击消息只标记当前用户已读，不创建调测、不下发设备指令。消息仅来自当前账号有权处理的范围。
- 保存进展不能结束任务；模拟、真实和未知状态区分。核验不直接修改设备遥测数据。

## 接口与状态

GET `/device-maintenance-tasks/{id}/workflow` 返回 task、state、version、assigned_to_name、events、commission_tasks、recovery、allowed_actions、blocked_reason。

POST 同路径 `/actions` 接收 action、expected_version、note、commission_id，带 Idempotency-Key。动作 START / SAVE_PROGRESS / SUBMIT_VERIFICATION / VERIFY_RECOVERY / COMPLETE / RESUME / LINK_COMMISSION。

状态：PENDING → PROCESSING → PENDING_VERIFICATION → COMPLETED；核验失败可 RESUME，历史只读 LEGACY_HANDLED。

GET `/device-maintenance-messages` 返回当前范围的 items、分页和 unread_count；POST `/{taskId}/read` 只保存当前用户已读时间。

## 工作与验证

- [x] 后端：先增加状态迁移、幂等、权限与恢复条件测试，再实现 DTO、服务、持久化、追加迁移及消息接口。关联报告检查设备与来源一致。
- [x] 前端：API 模块追加 workflow/action/messages/read；增加 `useMaintenanceWorkflow` 处理竞态、并发冲突与不确定提交，API 错误不降级为成功。
- [x] 前端：新消息铃铛按权限加载与轮询、查看标记已读、深链定位；旧待办面板改为多状态和“去处理”，原通知详情只读保留。
- [x] 前端：调测页增加关联待办条和处理记录；路由切换丢弃旧请求，错设备/无权限不自动回退选中第一台；已有调测任务复用和报告关联。
- [x] 验证：管理端 lint/test/build；后端受影响测试、认证测试及 package；隔离 PostgreSQL/PostGIS 新迁移和状态/锁查询。运行中业务库不用于测试夹具。
- [x] 实际服务重启/可达性检查，浏览器检查消息入口、待办深链及无数据/权限状态；没有真实设备回执不能声称现场核验通过。

测试命令在各自目录执行：`npm run lint`、`npm test`、`npm run build`；`./mvnw.cmd test -Dtest=...` 与 `./mvnw.cmd package`。精确测试类以新增回归文件和本次实际执行记录为准。

## 实际验证结果

全部实施项已执行。运维、权限、消息、PostgreSQL专项已通过；全量后端仍有未触碰模块的失败，不能标为全量通过。完整计数、打包方式、重启与真实页面核对见 [变更验证记录](../../运维消息与调测闭环-变更验证-2026-09-27.md)。
