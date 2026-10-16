# 模拟器 HTTPS 访问入口

## 当前部署（2026-10-15 用户确认）

用户确认移除 `https://8.217.47.137:8446` 的独立访问验证。该入口直接代理模拟器，`/_gateway/*` 返回 303 跳转至 `/`；不再调用 `forward_auth`。原平台登录、HTTPS、非同源 Origin 拒绝、模拟器内部 Host/Origin 保护和内部端口隔离保留。

此次只修改 Caddy 入口配置。网关程序与凭据文件保留用于恢复，不删除、不重置密码。原配置备份位于服务器 `/srv/dongying/backups/simulator-access-removal-20261015T1144/`。用户已明确知悉：模拟器平台会话由服务端共享，移除外层验证后，公网访问者可能调用暂停、停止等控制接口。

## 可选访问验证服务

`simulator-gateway.py` 是 Caddy 后方的独立访问验证服务。用于浏览器无法显示 HTTP Basic 密码弹窗的部署环境；不替代模拟器内的原平台登录、模拟权限或来源隔离。

- Python 3 标准库，无第三方依赖。只监听 `127.0.0.1:8770`。
- 凭据文件使用 `Username: ...` 和 `Password: ...` 两行，文件权限 `0600`，置于网站目录之外。沿用已有访问凭据，不在仓库、日志或命令行写密码。
- 启动参数：`--credentials <私有文件路径> --origin https://<服务器>:<端口>`。
- 启用此验证服务时，Caddy 必须先拒绝非空且不等于该 HTTPS origin 的 Origin，再代理 `/_gateway/login` 和 `/_gateway/logout` 到 8770；其他路径先 `forward_auth 127.0.0.1:8770 { uri /_gateway/verify }`，通过后才能代理模拟器。
- Caddy 应将 `X-Gateway-Client` 覆盖为 `{remote_host}`，不能沿用客户端传入值；不对公网开放网关或模拟器内部端口。
- 会话使用随机令牌，Cookie 设置 Secure、HttpOnly、SameSite=Strict、Path=/，最长 8 小时。网关重启即失效；不保存原平台密码。
- 同一来源连续失败 10 次后限流 5 分钟。POST 必须有精确匹配的 Origin；登录页使用 `Referrer-Policy: same-origin`，避免浏览器表单发送 `Origin: null`。
- Caddy 原有 HTTPS、来源校验和模拟器 Host/Origin 保护必须保留。恢复此验证服务前保存当前入口配置，再恢复原认证配置；当前服务器按上面的用户确认配置运行，不自动恢复访问验证。

运行 `python deploy/test_simulator_gateway.py` 验证登录、错误密码、来源拒绝、令牌失效、退出、限流和表单响应；之后必须在实际 HTTPS 浏览器中完成登录与模拟器界面读回。
