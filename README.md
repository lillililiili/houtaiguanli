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

cd ..\server
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"

cd ..\ruoyi-ui
npm ci
npm run dev
```

- 管理端：http://localhost:5175
- 平台 API：http://localhost:8080
- 业务前台默认：http://localhost:5173

本地合成管理员为 `admin1`。密码由后端 local profile 的开发配置提供；生产环境必须关闭开发 Seeder，并通过部署环境初始化唯一超级管理员。

详细接口见 [系统管理接口](docs/系统管理接口.md)、[设备运维接口契约](docs/设备运维接口契约.md) 和 [离线地图管理与部署](docs/离线地图部署说明.md)。

## 构建与部署

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
