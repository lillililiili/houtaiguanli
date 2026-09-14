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

开发环境通过 `/dev-api` 代理到 `http://127.0.0.1:8080/api`；生产静态站点通过 `/api` 反向代理到统一后端。
