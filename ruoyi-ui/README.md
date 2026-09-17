# 无人机平台后台管理前端

Vue 3、Vite、Pinia 与 Element Plus 实现的若依风格管理端，默认运行在 `5175`。它与业务前台共用 `../server` 的 `/api/v1` 接口，但会话仅保存在当前标签页的 `sessionStorage` 中。

```powershell
npm install
npm run dev
```

质量检查：

```powershell
npm run lint
npm test
npm run build
```

开发环境通过 `/dev-api` 代理到本仓库新后端 `http://127.0.0.1:8081/api`；可用 `ADMIN_API_PROXY_TARGET` 显式覆盖。生产静态站点通过 `/api` 反向代理到统一后端。切换后端后，旧后端的会话不通用，需要重新登录。


## 单位与通知配置（2026-09-16）

系统管理新增两个入口，需要登录返回对应 `menu_keys` 与读取权限，维护另需授权权限；后端迁移及角色配置更新后重新登录生效。

- **单位档案** `/system/organizations`：菜单 `organizations`、读取 `organizations.read`、维护 `organizations.auth`。选择单位后，在单位资料、联系人、来源映射、计划关联四个页签维护。联系人无需账号，可设置用途、有效期及联系方式核实依据；停用联系人保留已有历史，不提供单位停用。
- **通知对象配置** `/system/notification-settings`：菜单 `notificationSettings`、读取 `notificationSettings.read`、维护 `notificationSettings.auth`。风险固定“通知上级”；飞手短信与语音从关联计划读取已核实飞手，不选固定单位联系人。其他用途分别选择报送单位来源映射、处罚接收单位或运维责任单位。
- 保存及“校验已保存配置”都不试发。页面显示渠道可用性、阻断原因、模板版本、回执要求及受影响待发任务；正式通道未接通时明确显示未接通。版本冲突或提交结果未知时，先重新读取并核对，避免覆盖现有资料或重复提交。
- 设备管理原有运维待办详情，在后端返回相关字段时显示通知接收快照、配置版本及投递/回执事实；已反馈待办不代表设备已恢复。

以上为代码入口说明，不能据此认定迁移已在业务数据库应用或正式通道已接通。
