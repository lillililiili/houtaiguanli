# 光电自动追踪一期前端实施报告

## 范围与修改前快照

- 仅修改 `E:/rwurenji/_rong/dongying-vue` 的前端代码；没有提交或回退原有未提交改动。
- 修改前已将本次涉及的六个原文件复制到系统临时目录 `C:/Users/ADMIN~1/AppData/Local/Temp/eo-frontend-before-20260928-225006`，便于按本次起点独立审查。

## 代码变更说明

| 文件/组件 | 方法/函数 | 方法作用 | 本次代码作用 |
| --- | --- | --- | --- |
| `src/components/video/TargetTrackingPanel.vue` | `refresh()`、`invalidate()`、`perform()` | 读取目标统一状态、清理旧请求与轮询、执行允许的人工操作 | 三处入口共享同一追踪状态；严格按 `allowed_actions` 显示按钮；操作后回读，失败不推断为空闲或成功；身份、目标、页面切换清理旧状态。 |
| `src/services/deviceApi.js` | `eoTrackingStatus()`、`pauseEoTracking()`、`resumeEoTracking()` | 统一状态读取与幂等暂停、恢复请求 | 对接一期 `/targets/{id}/eo-tracking-*` 接口。 |
| `src/pages/AlarmsPage.vue` | `detailActionsHtml()`、`loadTarget()` | 告警详情操作和目标读取 | 移除旧 `eoTrackActions()`、`loadEoTask()`、`beginEoTrack()` 重复逻辑，详情直接使用共享面板。 |
| `src/pages/LegalityPage.vue` | 详情模板 | 显示所选研判关联目标 | 在原视频位置使用共享面板。 |
| `src/pages/flights/components/RiskOpticalPanel.vue` | `visible`、`unavailableReason` | 判断风险场景是否显示追踪区 | 保留气象隐藏和缺目标说明，其余状态与动作交给共享面板。 |

视频播放仍由原 `TargetLiveVideo` 组件只读查询，默认展开且可收起。跟踪状态与视频状态分别展示；页面挂载和轮询只发起 GET，人工点击才发起 POST。没有添加镜头控制、抢占或设备切换。

## 验证

- `npm run build`：通过。首次运行发现告警页移除旧函数后多余的右花括号，修正后再次构建成功。
- `node tools/scan.cjs`：通过，173 个文件，全部规则通过。
- `node --check src/services/deviceApi.js`：通过。
- `git diff --check --` 针对四个原有已修改前端文件：通过；Git 提示风险面板 LF 将在下次触碰时转 CRLF，不影响本次检查。新组件未纳入已跟踪文件的 Git diff，已由 Vite 构建解析。
- 浏览器三入口、视口、控制台、实际后端接口和硬件视频联调：未在本子任务验证，由主任务统一执行。后端接口仍由并行任务实施。

### 追加修正与组件挂载验证

- 将 `LOST` 显示为“跟踪信号中断”，避免把光电状态误说成全源目标失联；暂停按钮 `title` 说明同目标三个页面同步生效。
- 在项目外系统临时目录编写并执行 Vue 挂载脚本，使用现有 Vite、Playwright 和本机 Edge；完成后删除脚本，没有新增项目测试文件或依赖。
- 挂载测试通过：挂载时只有状态 GET、没有 POST；按钮严格跟随 `allowed_actions`；暂停与恢复各发一次 POST 且随后回读；状态 GET 失败隐藏操作按钮；切换目标后旧响应不覆盖当前目标；`LOST` 文案正确；浏览器页面无异常。
- 修正后再次运行 `npm run build`：通过。`git diff --check` 针对新组件路径执行，退出码 0（新文件尚未跟踪，Git 不输出该文件差异）；新组件语法由构建及挂载测试验证。

### 浏览器联调反馈后的暂停文案

- `TargetTrackingPanel.vue` 增加 `pauseLabel`：统一状态为 `STARTING`、`TRACKING`、`LOST`、`ENDING`、`END_UNCONFIRMED` 时显示“暂停并结束”；其他状态若后端仍允许 `PAUSE`，显示“暂停自动追踪”。没有改动按钮资格、接口或操作行为。
- 此次局部模板与 computed 变更后执行 `npm run build`：通过。此前挂载测试没有针对新文案重跑；真实浏览器复核由主任务继续。

## 已知边界

- `BEGIN`、`RETRY` 复用原人工发起接口，操作结果均重新读取统一状态；新 `PAUSE`、`RESUME` 使用幂等键。
- 状态响应目标不匹配、缺 `status` 或缺 `allowed_actions` 时视为读取失败，隐藏操作按钮。请求结果未知时不提供前端盲目重试入口。
