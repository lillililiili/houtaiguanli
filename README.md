# 无人机融合感知与低空安全管理平台后台管理系统

本仓库统一维护平台后端、Vue 3 若依风格管理端、数据库/API 文档与本地部署编排。业务前台位于同级目录 `../demo-ronghe/dongying-vue`，两套前端共用本仓库中的一个后端和一套 PostgreSQL 数据。

## 目录

```text
├── ruoyi-ui/   Vue 3 + Element Plus 管理端
├── server/     Java 17 / Spring Boot 3.4.5 平台后端
├── deploy/     PostgreSQL/PostGIS、MQTT、API 与 Web 部署示例
├── docs/       后端、数据库、系统管理和设备运维文档
└── scripts/    本地初始化与健康检查脚本
```

## 本地启动

```powershell
cd deploy
docker compose up -d db mosquitto
# 已有旧 pgdata 卷时，先按下文创建新后端专用数据库。

cd ..\server
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"

cd ..\ruoyi-ui
npm ci
npm run dev
```

- 管理端：http://localhost:5175
- 本仓库平台 API：http://localhost:8081
- 业务前台默认：http://localhost:5173

本地合成管理员为 `admin1`。密码由后端 local profile 的开发配置提供；生产环境必须关闭开发 Seeder，并通过部署环境初始化唯一超级管理员。

本仓库不复用 `dongyiwurenji/server` 进程。local 默认使用独立 `houtaiguanli` 数据库，管理端代理指向 8081；原 `uav` 库数据不会自动迁入。新建 Compose 数据卷会初始化该库；已有旧数据卷在 `deploy/` 执行一次 `docker compose exec db createdb -U uav houtaiguanli`，然后执行 `docker compose exec db psql -U uav -d houtaiguanli -c "CREATE EXTENSION IF NOT EXISTS postgis"`。自定义数据库账号时使用相应账号，并设置 `DB_URL/DB_USER/DB_PASSWORD`。Windows 的 `scripts/bootstrap-dev.ps1` 会检查并补建默认库。

旧后端代码迁入范围、冲突处理及验证见 [新后端迁移记录](docs/新后端迁移记录.md)。

详细接口见 [系统管理接口](docs/系统管理接口.md)、[设备运维接口契约](docs/设备运维接口契约.md) 和 [离线地图管理与部署](docs/离线地图部署说明.md)。

## 构建与部署

报表管理支持综合运行、设备运维、告警与风险、飞行计划与核验、事件处置五类业务报表，
类型与日/周/月周期独立选择，可下载完整明细 Excel 或图文 PDF。
新报表读取对应业务记录；模拟/回放记录继续明确标注，状态截至生成时。
旧运行统计接口保持兼容，详见 [运行统计接口契约](docs/运行统计接口契约.md)。

```powershell
cd ruoyi-ui
npm ci
npm test
npm run lint
npm run build

cd ..\server
.\mvnw.cmd test
.\mvnw.cmd package

cd ..\deploy
docker compose up -d --build
```

Compose 默认把同级业务前台目录 `../demo-ronghe/dongying-vue/dist` 挂载到 5173，并把 `../demo-ronghe/map-data` 作为 API 可写、业务 Nginx 只读的共享地图目录；可用 `BUSINESS_UI_DIST` 和 `MAP_PACKAGE_DATA_DIR` 覆盖。后台静态站点使用 5175，两个站点均反向代理到同一个 API 服务。


2026-09-15：新增业务前台设备异常通知 → 后台监测页运维待办。支持持久去重、范围控制与处理结果留痕；不改变设备恢复状态。接口和部署要求见[设备异常通知与运维待办](docs/设备异常通知与运维待办.md)。代码已实现，按用户要求未构建、未测试、未运行数据库迁移。

## 本机外部接口模拟（2026-09-24）

业务仓库 `../dongyiwurenji/tools/device-simulator` 的 `/external.html` 提供飞行计划、天气预报输入，以及风险通知上级、处罚移送的模拟接收和回执。启动本机模拟器后访问 `http://127.0.0.1:8766/external.html`，独立登录同一后端。详情见 `../dongyiwurenji/tools/device-simulator/README.md` 及 `../dongyiwurenji/docs/交付/外部接口模拟-20260924/实施与验证.md`。

后端新增 `/api/v1/local-interface-simulator`：`GET /context`，`POST /plans`、`/weather`、`/bindings`、`/messages/{id}/receipt`。仅 `local/test` 且排除 `prod/production`；要求接口及对应业务对象权限。新增迁移 `V202609240001` 保存消息、回执版本与最长20分钟的接收绑定，不修改旧记录。计划保持 mock 来源与原业务状态规则，天气按计划读取。通知使用原交接流程，明确绑定后才进入模拟收件箱；失效绑定保留待投递，不回退自动成功。

本轮已在8081本机服务应用迁移并完成模拟联调。真实厂商适配和通知通道状态不变；模拟回执不代表风险解除、飞离或处罚办结。收件箱与交接投递分段提交异常时显示结果未知并阻止补回执，需核对关联，尚无自动补偿重发机制。

后续页面调整：用户已在并发界面任务确认通知页为只读收件箱，计划/天气输入移到地图工作区；底层模拟接口及此处联调记录保留，当前页面入口以模拟器最新README为准。
