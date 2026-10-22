# 上级下发空域允许平台编辑、删除

验收时间：2026-10-21 11:35（北京时间）。按用户后续明确要求，上级下发空域也可以在本平台编辑和删除。本记录替代初次本地空域验收中“上级只读”的结论；原本地新增、查询、三维编辑及平面展示功能继续保留。

## 实现行为

- LOCAL、UPSTREAM 空域在具备 `airspace:read/manage` 和原单位/区县范围时均可维护；来源无法确认的 LEGACY 仍不能写入，已删除空域不能再修改。
- 平台编辑上级空域时追加 MANUAL 版本，保留 `source_id`、`source_mode`、原下发回执和历史版本，不把上级资料改标为本地来源。
- 原上级下发接口及路由保留。平台改名后，后续上级名称身份依据最近一次 UPSERT 回执校验；同来源的原名称可以继续下发，冒用平台新名称仍被拒绝。下发版本和生效时间仍遵守原递增约束。
- 删除使用已有逻辑删除流程：退出当前和未来空域判断，历史继续保留。删除后同对象新 UPSERT/WITHDRAW 返回 `409 AIRSPACE_DELETED`，旧消息原样重放仍返回原回执，不自动恢复空域。
- 三维编辑使用 WGS84 经纬度及 AMSL 海拔米值。原 AGL、未知基准或缺少海拔的资料可以打开编辑器，但海拔字段留空并要求补填，不自动换算或改标。

## 真实浏览器验收

用户在独立 Edge 页签 `http://127.0.0.1:5173/#/airspace` 完成登录后，使用实际超级管理员会话操作。原业务页签正在由另一任务使用，本轮未操作该页签。

1. 选择上级下发“流程验收禁飞区”：三维编辑与删除空域均启用，来源仍显示上级下发。其当前没有生效版本，定位按既有条件禁用，不影响维护。
2. 实际打开三维编辑器，读取最新的四点边界和海拔 0–150 米；页面说明本次修改保存在本平台、原来源及历史保留。
3. 在未保存草稿中将海拔改为 -20.5–300.25 米并旋转视图，立体体积、手柄和厚度同步更新。对边界点使用键盘微调，经度从 `118.62` 变为 `118.6200113`，坐标输入框同步回读。随后取消草稿，未改写此业务空域。
4. 点击删除空域，实际出现包含空域名称、当前/未来不再参与判断、来源版本历史保留的确认弹窗；随后取消，没有删除已有业务空域。
5. 改选另一条生效中的上级空域“东营模拟适飞区”，实际打开三维编辑。该版本未提供明确海拔，两个字段为空，页面明确提示重新填写；输入 25–200 米后立体图和 175 米厚度出现。取消后详情仍为原高度、原版本，列表仍为 18 条。
6. 本次页签捕获的浏览器 error 日志为空。

本轮浏览器验证的是入口、真实加载、草稿交互与删除确认；没有把取消操作记为保存/删除成功。实际持久化、后续下发和删除已由下述隔离 PostgreSQL/PostGIS 接口测试验证。初次本地空域的真实页面保存、回读及删除证据见 [初次验收记录](local-airspace-2026-10-20.md)。

截图：

- `E:/houtaiguanlii/artifacts/local-runtime/upstream-airspace-edit-proof.png`：上级空域草稿的真实三维编辑及经纬度/海拔联动。
- `E:/houtaiguanlii/artifacts/local-runtime/upstream-airspace-delete-proof.png`：上级空域删除确认弹窗。
- `E:/houtaiguanlii/artifacts/local-runtime/upstream-airspace-missing-altitude-proof.png`：缺少海拔时的空字段及明确提示。

## 自动化与运行验证

- 先运行新增回归，确认旧实现失败：上级 `editable=false`、修改请求返回 409。日志：`server/target/upstream-maintenance-red.log`。
- 第一轮后端相关回归共 74 项，其中 70 项通过、4 项因 PostgreSQL 读测试的独立环境变量未设置而跳过，0 失败、0 错误。没有把跳过记录为通过。日志：`server/target/upstream-maintenance-regression.log`。
- 补齐读测试所需 PostgreSQL 环境变量，并增加范围不足的断言后，最终运行上级接口、上级 PostGIS 接口和 PostGIS 读取测试，共 25 项全部通过、0 跳过；包括前述跳过的 4 项。两轮覆盖的 74 个不同测试均已实际通过，25 项不与第一轮简单相加。日志：`server/target/upstream-maintenance-final.log`。
- 覆盖上级下发→平台改名及海拔修改→重复幂等请求→错误身份拒绝→正确后续下发→撤销→再下发→平台删除→新消息拒绝→旧消息回执保留；另覆盖缺动作权限、缺数据范围、不同来源模式、逻辑删除后不可编辑、原来源及各版 origin 保留。
- 隔离数据库为 `127.0.0.1:25432/airspace_verify_local_20261020`，与运行库分开。PostGIS 测试实际执行，不使用运行库业务记录作写入夹具。测试清理后调度线程偶有已移除隔离表的日志，不影响断言结果；不将此日志视为运行服务故障。
- 前端 `node --test tools/airspaceVolume.test.mjs tools/airspaceModuleScope.test.mjs`：9 项通过、0 跳过，包含 AGL/未知高度不转换、AMSL 负值和精度保留。
- `npm.cmd run build` 成功，保留已有大于 500 kB 的体积提示；`node tools/scan.cjs` 通过，原 `flights/riskNoticeState.js` 待办未改变。两仓库 `git diff --check` 通过。
- 独立 Maven package 成功，日志 `server/target/upstream-maintenance-package.log`。构建来自当前工作区，保留其他任务的地图及反制等改动；本次不声称对那些模块重新验收。
- 在核对进程 PID、启动时间、命令行、8081 监听归属并取得本地操作锁后，仅替换已登记的 API 服务。当前运行 JAR：`server/target/upstream-maintenance-runtime-20261021-033323-289/low-altitude-server-0.1.0-SNAPSHOT.jar`，SHA256 `97310B083F3CE3972FFF7672BF0B4158EAE3F3F128226D426D0702F1E251FDEE`，PID 43208。服务状态已更新，原状态备份保留。
- 运行库仍为 `127.0.0.1:25432/houtaiguanli`，保持 `local,qa`、`app.dev-seed.enabled=false`；未修改密码、启用演示种子或重启其他服务。
- 强制检查 `node scripts/check-local-startup.mjs --business-dir E:/rwurenji/_rong/dongying-vue` 退出 0，报告时间 `2026-10-21T03:33:48.148Z`，7 项通过。自动报告的 `account_login=NOT_TESTED` 与上文用户登录后的实际页面验证分别记录。

本次仅本地实现和验收，未部署服务器、未进行真实 UM 回写联调、未提交或推送 Git。

## 代码变更说明

本表只列本轮“允许维护上级空域”新增修订；原本地 CRUD 及三维编辑实现详见初次验收记录。后端 Java 前缀为 `E:/houtaiguanlii/server/src/main/java/com/uav/lowaltitude/`，前端前缀为 `E:/rwurenji/_rong/dongying-vue/`。

| 改动文件/类 | 改动方法/函数 | 方法作用 | 本次修改的代码作用 |
| --- | --- | --- | --- |
| modules/airspace/application/AirspaceWriteService.java | updateLocal/deleteLocal、requirePlatformWritable/requireNotDeleted、addVersionInternal | 维护校验、版本写入与逻辑删除 | 允许有权限的 LOCAL/UPSTREAM，保留原来源，阻断 LEGACY 和已删除对象 |
| modules/airspace/application/AirspaceReadService.java | editable | 计算服务端可编辑性 | 对未删除且有维护权限的上级空域返回可编辑 |
| modules/airspace/application/UpstreamAirspaceService.java | receive | 接收上级消息、验证身份与幂等 | 名称身份取原下发事实；删除后拒绝新消息、保留原消息回执 |
| modules/airspace/infrastructure/UpstreamAirspaceRepository.java | latestUpsert | 查询最近一次 UPSERT | 在撤销和平台改名后仍能校验原上级名称 |
| platform/audit/AuditLabels.java | 无具体方法，动作标签映射 | 审计动作显示 | 修改、删除标签不再限定“本地空域” |
| src/pages/airspace/AirspacePage.vue | maintenanceBlocker/deleteAirspace、页面说明 | 控制维护入口与删除确认 | 采用后端可编辑性，不再因 UPSTREAM 禁用，明确删除保留来源历史 |
| src/pages/airspace/AirspaceEditorModal.vue | load、说明模板 | 读取版本并初始化草稿 | 上级空域可进入三维编辑，显示保留来源说明，非 AMSL 高度要求补填 |
| src/pages/airspace/airspaceVolume.js | editableSeaLevelBounds | 初始化海拔上下限 | 只复用明确 AMSL 值，其他基准返回空字段 |
| server/src/test/java/com/uav/lowaltitude/modules/airspace/api/LocalAirspaceApiTest.java | linkedExternalSourceCanBeMaintainedWithoutChangingItsProvenance | 来源与维护回归 | 覆盖上级可改可删而来源不变，并由 PostGIS 测试继承执行 |
| server/src/test/java/com/uav/lowaltitude/modules/airspace/api/UpstreamAirspaceApiTest.java | localEditRetainsSourceAndLaterPushesWorkUntilLocalDeletion、maintainingUpstreamStillRequiresPermissionAndOriginalScope | 下发/平台维护闭环及权限验证 | 覆盖平台改名后的继续下发、撤销后再下发、删除和幂等，以及动作/范围拒绝 |
| tools/airspaceVolume.test.mjs | 海拔初始化测试 | 校验高度基准边界 | 覆盖 AGL、未知、缺失及负海拔，保证不修改原对象 |
| 两仓库 AGENTS.md、server/AGENTS.md、空域接口/基线/设计计划及验收文档 | 无具体方法 | 记录当前契约和验证边界 | 显式替代旧只读约定，保留原验收历史并链接本轮证据 |

本地运行辅助脚本 `artifacts/local-runtime/restart-upstream-maintenance-api.ps1` 只承担本次已登记 API 的身份核验、运行包复制和隐藏重启，属于未提交运行材料；没有改动通用启动器。
