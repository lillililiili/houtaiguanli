# 管理前端开发约定

- 使用 npm 与仓库内 `package-lock.json`，不要引入若依默认 Java 接口或 JWT 响应适配。
- API 固定遵循 `/api/v1`、Bearer 数据库会话、`{ok,data,error}`、snake_case、字符串 ID、epoch 毫秒、幂等键和版本号约定。
- 页面路由由 `/auth/me` 返回的 `menu_keys` 与 `permission_codes` 共同生成；按钮权限只负责界面体验，后端仍是最终授权边界。
- 登录状态仅写入 `sessionStorage`，不得与业务前台共享浏览器会话。
- 提交前至少执行 `npm run lint`、`npm test` 和 `npm run build`。
