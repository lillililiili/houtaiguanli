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
