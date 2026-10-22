# 本地立体空域 Implementation Plan

后续用户确认覆盖：上级下发空域也可编辑、删除；本计划下方的“上级只读”是首轮已验收口径，现由后续维护实现替代，最新验收另记。

> **For agentic workers:** Use subagent-driven-development for the backend task and task review; integrate the frontend and verify in this session. Track completion below.

**Goal:** 本地空域增删改查、三维编辑、平面日常展示，保留原下发接口。

**Architecture:** 复用空域、版本、来源、审计和授权；独立本地维护用例与编辑器，服务端拒绝上级来源写入。前端业务页接入同一读取与保存模型。

**Tech Stack:** Java 17 / Spring / PostgreSQL PostGIS；Vue 3 / Naive UI；原离线地图及 SVG 三维编辑投影，无新增依赖。

## Global Constraints

- 保留现有空域下发接口；不做 UM 对接改造。
- 本地空域可维护，上级下发空域保持只读。
- 经纬度为 WGS84，海拔上下限为 AMSL 米值，不与 AGL 混用。
- 历史几何、来源及引用保留；禁止硬删业务资料及修改已应用迁移。
- airspace:read、airspace:manage、归属范围、并发版本、幂等、审计必须服务端校验。
- app.dev-seed.enabled=false；测试只用隔离库，不创建演示种子。
- 保留两仓库已有无关改动，不提交或覆盖他人的改动。

## Task 1: 后端本地维护

Files: server/modules/airspace API/application/infrastructure、FrontendActionPolicy、相关测试和追加迁移（如需）。实际目录为 server/src/main/java/com/uav/lowaltitude/modules。

Interfaces:
- POST /api/v1/airspaces：现有 create 字段，完整 AMSL 高度，返回 airspace_id、airspace_version_id、version_no、version。
- PUT /api/v1/airspaces/{id}：name、kind_code、boundary、min_altitude_m、max_altitude_m、altitude_datum、valid_from、valid_to、change_reason、expected_version；返回同一保存结果结构。编号和归属固定，元资料名称允许修改并审计。
- DELETE /api/v1/airspaces/{id}：JSON expected_version、change_reason；退出当前使用且保留历史。
- GET /api/v1/airspaces/management-context：scopes [{owner_org_id,owner_org_name,district_id,district_name}]，按可授权完整元组返回。
- 现有 list/detail 返回 management_source=LOCAL/UPSTREAM/LEGACY、editable、deleted_at（若有）；来源须依据真实来源和版本来源关系。历史版本读取保持可用。

- [x] 先写 API 失败测试，涵盖完整 CRUD、AMSL、上级只读、权限范围和版本冲突。
- [x] 复用已有写服务的几何、版本及幂等规则，实现严格本地来源操作。
- [x] 删除必须退出当前和未来生效空间判断，历史读取及历史时点保持；证明所有直接消费者一致。
- [x] 在隔离 PostgreSQL/PostGIS 验证迁移和空间事实；运行相关认证/角色/空域测试与 package。

## Task 2: 业务前台三维编辑及 CRUD

Files in E:/rwurenji/_rong/dongying-vue: src/pages/airspace/AirspacePage.vue、AirspaceEditorModal.vue、AirspaceVolumeEditor.vue、airspaceVolume.js、src/services/airspaceApi.js、tools/airspaceVolume.test.mjs。

- [x] 几何投影与反投影测试：不同坐标、旋转角、AMSL 负值、上下界不混淆、闭环和多面保留。
- [x] 三维体以经纬度和海拔生成，可旋转缩放、拖边界点、拖高度；表单同步、清晰轴线和高度标签。
- [x] 编辑器使用 Naive UI 表单控件、归属完整元组、经纬度点列表和海拔范围；校验后调用上述接口。
- [x] 本地操作入口按服务端 editable 及当前权限；上级只读显示原因；写入后重读列表详情，删除确认并刷新地图。
- [x] node --test tools/airspaceModuleScope.test.mjs tools/airspaceVolume.test.mjs（8 项）；npm.cmd run build；源码扫描。
- [x] 用户登录后，浏览器实际操作新增、三维旋转/缩放/拖点/拖高度、编辑、两版回读、删除、上级只读和平面定位；结果见 docs/acceptance/local-airspace-2026-10-20.md。

## Task 3: 集成审查与交付

- [x] 审查本次差异及上述约束，修复实际发现的问题（LAN 幂等键、海拔存储精度）。
- [x] 运行 scripts/check-local-startup.mjs --business-dir E:/rwurenji/_rong/dongying-vue，7 项通过，2026-10-21T02:40:00.994Z，退出 0；账号登录单列未验证。
- [x] 更新接口文档与 AGENTS 的本次确认，并汇总具体文件/方法及验证证据。
