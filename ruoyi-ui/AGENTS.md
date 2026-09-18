# 管理前端开发约定

- 使用 npm 与仓库内 `package-lock.json`，不要引入若依默认 Java 接口或 JWT 响应适配。
- API 固定遵循 `/api/v1`、Bearer 数据库会话、`{ok,data,error}`、snake_case、字符串 ID、epoch 毫秒、幂等键和版本号约定。
- 页面路由由 `/auth/me` 返回的 `menu_keys` 与 `permission_codes` 共同生成；按钮权限只负责界面体验，后端仍是最终授权边界。
- 登录状态仅写入 `sessionStorage`，不得与业务前台共享浏览器会话。
- 提交前至少执行 `npm run lint`、`npm test` 和 `npm run build`。

## 用户管理与单位资料

- 单位树点击继续筛选用户；查看单位打开只读弹窗，同一弹窗切换编辑。新增下级单位带入上级；来源映射及计划关联在关联信息内维护。
- 原单位资料菜单与读取权限可进入合并入口，但不授予用户列表读取权限；账号权限与单位资料维护权限独立，后端继续校验。仅有原 users.auth 时保留名称/层级维护，扩展资料按 organizations.auth 控制。
- 移除独立单位档案菜单，旧地址兼容到用户管理；不删除单位、联系人、映射、计划关联或历史快照。
