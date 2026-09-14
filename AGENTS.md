# AGENTS.md

本仓库包含管理前端与平台唯一后端。`ruoyi-ui/` 使用 Vue 3、Vite、Pinia、Element Plus 和 npm；`server/` 使用 Java 17、Spring Boot 3.4.5、MyBatis、Flyway 与 PostgreSQL/PostGIS。

- npm 命令只在 `ruoyi-ui/` 运行，Maven 命令只在 `server/` 运行。
- 后端公共契约保持 `/api/v1`、Bearer 数据库会话、snake_case、`{ok,data,error}` 和字符串 ID。
- 管理端只承载运维管理、系统管理和个人资料/改密，不加入若依演示、代码生成、缓存或定时任务菜单。
- 不修改已应用 Flyway 脚本；新增迁移必须追加版本并在 PostgreSQL/PostGIS 验证。
- 不提交 `node_modules`、`dist`、`target`、运行数据、日志、凭据或本地环境文件。

后端详细约束见 [server/AGENTS.md](server/AGENTS.md)。
