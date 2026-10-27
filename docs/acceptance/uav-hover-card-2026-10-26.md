# 融合感知无人机悬浮卡片移除

2026-10-26，用户要求只保留点击无人机后的统一详情卡。

- 业务前端：`D:/沉积岩/demo-ronghe/dongying-vue`。
- 融合感知对 UAV 不再生成悬浮内容；共享地图对空内容持续隐藏浮层，缓存命中也不会出现空壳。
- 目标命中与点击选择、统一 Vue 详情卡、视频入口保持；设备、任务、其他目标及其他页面默认提示保持。
- 两级 AGENTS.md 已同步此次交互约定。

## 验证

- `node tools/mapOverlayFrame.test.cjs` 通过：重复命中、新观测、换目标、设备/任务提示和其他页面默认提示。
- `node --test tools/situationPredictionPopup.test.mjs tools/situationTargetCard.test.mjs tools/situationSetup.test.mjs tools/situationRiskSelection.test.cjs`：24 项通过。
- `npm run build` 通过；已有大包体积提示仍存在。
- `check-local-startup.mjs`：7 项通过，报告 `artifacts/local-runtime/uav-hover-card-20261026.json`。
- 使用现有已登录 Chrome 前台与模拟器，本地启动已加载的流程 1，产生模拟目标 `20261026-007`。实际点击目标打开统一详情、通过键盘关闭详情，并将指针停留在目标图标上确认没有悬浮卡。再次点击可重新打开统一详情，实时字段持续更新。
- 此轮只验证卡片交互，没有提交人工核实或反制操作；结束时通过模拟器“停止场景上报”停止本次场景。

浏览器截图：`artifacts/uav-hover-card-20261026/hover-no-card.png` 与 `artifacts/uav-hover-card-20261026/click-selected-card.png`。
