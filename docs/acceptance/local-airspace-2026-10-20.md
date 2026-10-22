# 本地立体空域维护验收

> 后续范围调整：用户已要求上级下发空域也可编辑、删除，最新实现和验收见 [上级空域维护验收](upstream-airspace-maintenance-2026-10-20.md)。本文保留初次验收事实，其中“上级只读”不再代表当前行为。

验收完成：2026-10-21 10:47（北京时间）。实现本地空域新增、查询、三维编辑和删除；经纬度使用 WGS84，垂直范围使用海拔 AMSL 米值。日常地图继续平面展示。原上级空域下发/更新/撤销接口保留，上级下发及来源无法确认的旧空域不允许本地修改。

## 实际页面验收

地址：`http://127.0.0.1:5173/#/airspace`。用户完成登录后，在 Edge 真实业务页面使用超级管理员会话操作，没有绕过鉴权或开启开发种子。

1. 从“新增空域”打开三维表单，填写四个边界点、海拔 50–250 米、适飞区和授权单位/区县；生效时间设置为次日 2026-10-22 10:00:00，避免参与当前空间判断。
2. 旋转、缩放立体视图；拖动上限手柄，海拔上限同步变为 274.4 米。拖动第一个顶点，坐标同步变为 `118.5999187,37.3998116`。
3. 保存后列表增加一条本地空域，详情明确待生效。重新打开三维编辑器，坐标及海拔与保存前一致。
4. 将上下限设为相同，页面拒绝保存并提示“海拔上限必须高于海拔下限”。随后改为 -20.5–300.25 米，修改名称，使用键盘移动第二个顶点至 `118.6030113,37.4`，保存第二版。
5. 展开历史，第二版显示 -20.5–300.25 米，第一版仍显示 50–274.4 米，两版分别保留变更原因。再次进入编辑器，第二版坐标和高度正确回读。
6. 通过“删除空域”与确认弹窗删除本次验收空域。成功提示“空域已删除，历史版本保留”，清单从 19 条恢复 18 条。历史和审计依约保留，不物理清除数据。
7. 选择已有上级下发空域，三维编辑和删除按钮禁用，同时明确显示只读原因。定位后的平面地图边界正常。浏览器本次记录的 error 日志为空。

验收条目标记：`LOCAL-VOLUME-QA-20261021`，初始名称“立体空域维护验收-1021”，修改后加“已修改”。这是一条经授权接口创建、未来生效、随后逻辑删除的验收记录，不是自动演示种子。

截图：
- `E:/houtaiguanlii/artifacts/local-runtime/local-airspace-3d-proof.png`：第二版重新读取后的三维编辑器。
- `E:/houtaiguanlii/artifacts/local-runtime/local-airspace-planar-proof.png`：验收记录删除后的列表、上级只读详情及平面边界。

## 自动化、构建与运行证据

- 后端完整相关回归：94 项，0 失败、0 错误、0 跳过；最终海拔精度补丁后局部回归 19 项，全部通过。包括真实 PostgreSQL/PostGIS 的增删改查、删除当前/未来空域、历史时点、并发修改、非法拓扑、迁移升级及原上级接口；权限和身份分支有隔离测试。
- 独立 Maven package 成功。详细日志与环境见 `artifacts/local-runtime/local-airspace-backend-report.md`。构建使用独立 target，避免同工作区其他 Maven 构建干扰。
- 前端最终 `node --test tools/airspaceModuleScope.test.mjs tools/airspaceVolume.test.mjs`：8 项通过；覆盖多区域/内孔保留、不同坐标及旋转角的投影反解、非法边界/海拔及精度。
- `npm.cmd run build` 成功。仍有既有 bundle 超过 500 kB 的体积提醒；没有构建错误。`node tools/scan.cjs` 通过，原有 flights/riskNoticeState.js 待办未改动。
- 两仓库 `git diff --check` 通过；其他任务文件的 CRLF 提醒不属于本次差异错误。
- 本地运行后端由同工作区另一任务恢复，路径为 `server/target/airspace-risk-recovery/low-altitude-server-0.1.0-SNAPSHOT.jar`。逐项比较本次空域相关 64 个类/迁移资源，与独立已验证构建全部一致；因此复用现有后端，没有重复重启。运行参数保持 `local,qa` 和 `app.dev-seed.enabled=false`。
- 强制启动检查命令 `node scripts/check-local-startup.mjs --business-dir E:/rwurenji/_rong/dongying-vue` 退出 0，报告时间 `2026-10-21T02:40:00.994Z`，七项均通过。报告自身的 `account_login=NOT_TESTED` 仅表示自动检查不测试账号；上述人工登录后的真实页面验证另行记录。
- 前端独立审查发现 LAN HTTP 的 `crypto.randomUUID()` 兼容问题，已复用原 API 客户端幂等键生成器修复；最终集成审查及海拔精度复核未发现 P1/P2 问题。

本次为本地功能验收，没有服务器部署、真实 UM 联调或全仓业务验收。未提交 Git；两仓库既有反制、地图坐标及空域风险页面调整等并行改动保留，不计入本次交付。

## 代码变更说明

后端路径前缀：`E:/houtaiguanlii/server/src/main/java/com/uav/lowaltitude/`。前端路径前缀：`E:/rwurenji/_rong/dongying-vue/`。

| 改动文件/类 | 改动方法/函数 | 方法作用 | 本次修改的代码作用 |
| --- | --- | --- | --- |
| modules/airspace/api/LocalAirspaceController.java | create/update/delete/context | 接收本地空域维护请求 | 新增 POST、PUT、DELETE 及可维护范围查询 |
| modules/airspace/api/AirspaceDtos.java、AirspaceWriteDtos.java | DTO record 声明，无具体方法 | 定义读写契约 | 补充维护来源、可编辑性、删除时刻及保存/范围返回值 |
| modules/airspace/application/AirspaceWriteService.java | createLocal/updateLocal/deleteLocal/managementContext、requireLocalWritable/requireLocalAltitude、createInternal/addVersionInternal | 校验、保存与审计 | 本地来源、AMSL、数值精度、范围、并发、幂等校验；追加版本及逻辑删除，上级分支兼容 |
| modules/airspace/application/AirspaceReadService.java | airspace/summary/editable | 组装详情与清单 | 暴露服务端计算的来源、操作资格和生命周期 |
| modules/airspace/infrastructure/AirspaceWriteRepository.java | validBoundary/managementSourceSql/managementSource/upstreamManaged/deleted/scopeExists/managementScopes/renameAirspace/retireAirspace | 持久化、来源和目录查询 | PostGIS 拓扑预检、可信本地来源判断、授权目录范围、名称更新及逻辑删除 |
| modules/airspace/infrastructure/AirspaceReadRepository.java | listAirspaces/findAirspace/effectiveVersions、conflicts/hasAmbiguousEffectiveVersion、where/versionWhere/mapAirspace | 读取空域及空间关联 | 常规列表排除删除空域，历史查询保留，当前和未来关联按删除时间截断 |
| modules/assessment/engine/PostgisSpatialFactAdapter.java | 空间命中、有效版本歧义 SQL | 提供研判空间事实 | 删除后不再命中，历史时点继续按原空域判断 |
| modules/alarm/application/PostgisPilotDepartureWatch.java | 空域命中 SQL | 撤离观察的空间查询 | 应用与观测时间一致的删除边界 |
| modules/automationrule/infrastructure/AutomationRuleRepository.java | airspaceExists | 校验自动规则关联空域 | 新规则不能选择已删除空域 |
| modules/responseplan/infrastructure/ResponsePlanRepository.java | airspaces/activeAirspaceVersions | 预案空域选项和有效版本计数 | 排除已删除空域的新选项及当前适用性 |
| modules/identity/domain/FrontendActionPolicy.java | 动作白名单，无具体方法 | 前台角色动作目录 | 恢复 airspace:manage 可配置，不自动授权普通角色 |
| platform/audit/AuditLabels.java | 标签映射，无具体方法 | 审计动作中文名称 | 增加修改本地空域、删除本地空域 |
| server/src/main/resources/db/migration/V202610200002__local_airspace_lifecycle.sql | 无具体方法 | 追加数据库结构 | 新增 deleted_at 与范围索引，不修改历史迁移 |
| src/pages/airspace/AirspacePage.vue | editAirspace/deleteAirspace、canManage/maintenanceBlocker/localSourceLabel | 页面维护入口及刷新 | 增删改查入口、上级只读说明、保存回读、删除确认与清单刷新 |
| src/pages/airspace/AirspaceEditorModal.vue | load/buildBody/save、pickerTime、updatePoint/appendPoint/addPart/addHole/removeRing | 加载、编辑、校验并保存草稿 | 授权范围、经纬度、海拔、时间、变更原因；最新版本读写及不确定结果重试 |
| src/pages/airspace/AirspaceVolumeEditor.vue | down/move/up、pointKey/heightKey、投影渲染 | 三维视图和直接操纵 | 等比例立体体积、旋转缩放、顶点/海拔拖动及键盘微调 |
| src/pages/airspace/airspaceVolume.js | openBoundary/closeBoundary/validateVolume/makeFrame/project/unproject/heightFromDrag | 纯几何和校验 | 保留多面及内孔，真实坐标与三维视图双向同步，完整高度校验 |
| src/services/airspaceApi.js | managementContext/create/update/remove | 封装本地维护 API | 使用现有鉴权客户端提交请求 |
| src/services/apiClient.js | idempotencyKey | 生成幂等请求键 | 导出并复用原有 LAN HTTP 可用的回退实现 |
| tools/airspaceVolume.test.mjs | 五项 node:test 用例 | 检查数据与三维计算 | 多面孔洞、不同坐标投影、非法边界/高度及精度回归 |
| server/src/test/java/.../airspace/api/LocalAirspaceApiTest.java、LocalAirspacePostgresApiTest.java、LocalAirspaceMigrationPostgresTest.java | API、空间、迁移测试 | 隔离验证后端行为 | CRUD、权限来源、并发、生命周期、历史及 PostgreSQL 升级覆盖 |
| server/src/test/java/.../airspace/api/UpstreamAirspaceApiTest.java、.../identity/api/RoleActionPermissionApiTest.java | 现有 API/角色断言 | 兼容性回归 | 调整本地路由开放和 manage 可配置预期，保留上级分支保护 |

接口约定详见 `docs/本地空域维护接口-2026-10-20.md`。设计/计划、本仓库与 server 和业务前台 AGENTS、原下发接口说明及后端开发基线已补充本次确认，均为文档声明，无具体方法。
