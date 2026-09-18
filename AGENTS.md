# AGENTS.md

本仓库包含管理前端与平台唯一后端。`ruoyi-ui/` 使用 Vue 3、Vite、Pinia、Element Plus 和 npm；`server/` 使用 Java 17、Spring Boot 3.4.5、MyBatis、Flyway 与 PostgreSQL/PostGIS。

- npm 命令只在 `ruoyi-ui/` 运行，Maven 命令只在 `server/` 运行。
- 后端公共契约保持 `/api/v1`、Bearer 数据库会话、snake_case、`{ok,data,error}` 和字符串 ID。
- 管理端只承载运维管理、系统管理和个人资料/改密，不加入若依演示、代码生成、缓存或定时任务菜单。
- 不修改已应用 Flyway 脚本；新增迁移必须追加版本并在 PostgreSQL/PostGIS 验证。
- 不提交 `node_modules`、`dist`、`target`、运行数据、日志、凭据或本地环境文件。

后端详细约束见 [server/AGENTS.md](server/AGENTS.md)。

## 直接反制权限（2026-09-17）

- `disposal:direct` 独立于申请、审批和普通执行权限；仅显式 OP 有效，默认不授予任何角色，ROLE-ADMIN 不从目录自动继承。既有内置角色锁定规则保留，自定义角色配置“无/允许”。
- DIRECT 免逐次审批但必须继续校验主体、范围、策略、设备权限、有效期与急停条件；记录真实发起人，不伪造审批人。排队设备指令和自动续链重新检查当前资格，不超出原直接授权窗口。
- 普通申请的审批约束、旧审批历史和冻结材料保持原样；新移送材料携带授权方式，真实执行结果以实际回执为准。

## 单位资料入口（2026-09-17 用户确认）

- 单位资料统一在用户管理左侧单位机构维护，不设独立单位档案页面。查看单位弹窗承接基本资料、联系人及既有业务关联；保留单位 ID 和历史通知快照，不新增按区域、风险类型分发的职责单位配置。
