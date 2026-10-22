# 前后台字体开源许可检查（2026-10-20）

检查并修正了管理前端 `ruoyi-ui`、业务前台
`D:\沉积岩\demo-ronghe\dongying-vue`、统一后端的 PDF/Excel 导出，以及当前
`D:\沉积岩\demo-ronghe\map-data\dongying-dev` 地图字体资源。

原应用存在微软雅黑、苹方、Arial、Helvetica、Segoe UI、Consolas、Menlo、
Bahnschrift、DIN Alternate 等系统字体声明。这些字体不能仅因随系统提供就认定为
开源字体。本次移除应用代码对这些字体及系统通用字体栈的显式依赖。
系统已有字体的调用与字体文件再分发也不是同一种许可行为，原声明本身不等于侵权。
参见 [Microsoft 字体许可说明](https://learn.microsoft.com/en-us/typography/fonts/font-faq)。

| 使用位置 | 当前字体 | 许可与处理 |
| --- | --- | --- |
| 前后台正文、表单、登录、按钮、图表、地图中文和画布标签 | Noto Sans SC | SIL OFL 1.1；本地 WOFF2；完整保留源字体字符集 |
| 编号、时间、KPI 数字、代码、日志等宽区 | Noto Sans Mono；中文回退 Noto Sans SC | SIL OFL 1.1；本地 WOFF2，保留 100–900 字重 |
| 当前地图拉丁字形及其他语言字形 | Noto Sans Regular/Medium/Italic、Noto Sans Devanagari Regular v1 | 原 PBF 字形包保留，附带 OFL |
| 当前地图附带的中文 OTF | Noto Sans SC Regular 2.004 | 原资产及 Adobe 版权/OFL 保留；页面中文绘制复用应用已加载的 Noto Sans SC |
| PDF | Noto Sans SC，400 字重 | 已随后端打包、嵌入 PDF，原字体及许可保留 |
| XLSX | Noto Sans SC | 修正默认字体、样式字体和页眉页脚，清除 Calibri 主题方案；不嵌入字体程序 |

官方授权来源：

- [Noto Sans SC OFL](https://github.com/google/fonts/blob/main/ofl/notosanssc/OFL.txt)
- [Noto Sans Mono OFL](https://github.com/google/fonts/blob/main/ofl/notosansmono/OFL.txt)
- [Noto CJK 2.004 OFL](https://github.com/notofonts/noto-cjk/blob/Sans2.004/LICENSE)
- [Protomaps Noto 字形包 OFL](https://github.com/protomaps/basemaps-assets/blob/main/fonts/OFL.txt)

以上 OFL 1.1 字体允许免费商用、嵌入、修改及随软件分发，须保留版权和许可证，
不得将字体本身单独出售。字体许可证不会把使用字体制作的报表变成 OFL 文档。

## 实现与交付

两个前端各有完整的 `public/fonts/`，包含字体、两份对应版权/OFL 原文、
`fonts.css`、说明和 `manifest.json`。清单记录官方来源、源文件/交付文件 SHA-256
及转换方式。构建会将整个目录复制到 `dist/fonts/`；运行时不连接外部字体 CDN，
不使用 `local()` 从用户系统选择同名字体。

Noto Sans SC 网页版从后端已有的合法 400 字重实例转换为 WOFF2，修正静态实例
内部名称为 Regular；粗体由浏览器合成。Noto Sans Mono 固定宽度轴为 100，
保留可变字重，使用 fontTools 4.67.0 转换。两者均未按本次页面文字裁剪字符集，
因此更换业务名称或编号仍可使用完整字体覆盖。

应用挂载前通过 Font Loading API 等待两个字体文件加载，避免 Canvas/MapLibre
首次绘制缓存系统替代字形；缺文件或加载失败时显示重试提示。Element Plus、
Naive UI、MapLibre 控件、ECharts 正文/浮层/富文本、Canvas 和原生等宽元素
均显式使用这些字体。第三方库中保留的默认字体字面量由上述接入层覆盖，
没有修改第三方库源码。

后端共用 `ReportWorkbookFonts` 在两种 XLSX writer 写出前统一全部字体记录
和页眉页脚。PDF 原有字体、报表内容和本次工作前已存在的 PDF 用户修改均未改动。
用户随后要求重启后端，已于 2026-10-20 使用本次源码快照构建的独立 JAR 启动，
包含上述 XLSX 字体修改；保持原数据库、地图目录和 `app.dev-seed.enabled=false`。
独立构建目录避免并行编译覆盖运行文件，构建与运行均未使用测试 classpath。

## 验证记录

- `node scripts/check-font-licenses.mjs --business-dir D:\沉积岩\demo-ronghe\dongying-vue`：
  退出 0；检查前后台交付字体清单、SHA-256、OFL、已知系统字体引用及外部字体链接。
  它是回归检查，不是对任意新字体许可证的自动法律判定。
- 管理前端 `npm run lint`：通过。完整 `npm test` 480 项通过，3 项代理检查受到
  沙盒 localhost EACCES 阻断，并出现一次测试异步清理异常；解除执行限制后重跑
  `previewApiProxy` 与 `deviceOverviewRefresh`，10 项全部通过且无异步异常。
  新增字体加载测试覆盖成功、任一字体缺失和下载失败。
- 业务前台 `node --test tools/fontPolicy.test.mjs`：4 项通过，覆盖 Naive 字体、
  首次/复用图表、自定义浮层以及不同尺寸环图；`node tools/scan.cjs` 退出 0，
  保留一条原有 `riskNoticeState.js` 待办。
- 两个前端 `npm run build`：退出 0；`dist/fonts/` 与对应 `public/fonts/` 逐文件
  哈希一致，含许可证。
- 后端 `.\mvnw.cmd '-Dtest=ReportWorkbookFontsTest' test`：退出 0，4 项通过、
  0 失败/错误/跳过。覆盖两种 writer、正式和模拟来源、不同日期和随机标题，
  回读 XLSX 验证字体、主题、加粗、数值和模拟标识。较广的既有
  `BusinessReportExportTest` 在 Mockito 自附加阶段无进展，已中止，不计为通过。
- 隔离 Edge 浏览器在 1440×900 和 1280×800 检查两端登录页：文字正常、未截断。
  FontFace 状态均为 loaded；字体请求均同源；浏览器实际字形程序检查显示
  中文使用 Noto Sans SC，等宽拉丁数字使用 Noto Sans Mono，中文回退仍为 Noto
  Sans SC，全部 `isCustomFont=true`。此次未使用任何真实账号登录。
- 完整启动检查退出 0，无跳过：后端就绪、前后台页面/目录/资源/API 代理、
  两端地图及其配置一致性、模拟器首页和状态均通过。
  报告时间 `2026-10-20T10:10:13.253Z`（用户时区 America/New_York：06:10:13）。
- 后端重启后再次执行完整启动检查，退出 0，七项全部通过，无跳过；
  报告时间 `2026-10-20T10:35:10.103Z`（用户时区 06:35:10），详见
  `artifacts/local-runtime/startup-check-after-backend-restart.json`。
  数据库 202 项迁移校验通过。重新刷新两端登录页并完成字体浏览器检查；
  真实账号登录和登录后的实际导出操作未验证。模拟器服务可访问，场景保持停止。

临时检查证据位于忽略提交的 `artifacts/font-audit/`，包括
`browser-result.json`、两端两种视口截图和 `startup-check.json`。

## 使用边界

XLSX 指定字体名称但不嵌入字体。接收电脑须安装官方 Noto Sans SC，才能避免
Excel/WPS 自行选择系统替代字体；需要固定展示效果时可使用已嵌入字体的 PDF。
不要直接安装后端现有 TTF 作为桌面字体，其旧内部家族名称仍带 Thin，
虽然实际字重为 400；网页转换文件已修正名称。

本结论针对本次应用代码、随包字体和当前地图包。未来导入的地图包或附件需要
另行检查；图片/视频内已栅格化的文字无法从字体声明反推出原字库许可。
浏览器自身界面、用户自定义字体设置以及字体字符集之外的特殊字符替代，
不属于应用能够强制指定的字体范围。
