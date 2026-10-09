# 告警与处置页面原型 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变现有深蓝监控台视觉结构的前提下，制作告警页与处置页联动的可点击原型，验证自动进入处置、风险信息展示、`暂不处置` 和上级通知按钮的页面关系。

**Architecture:** 新增一个自包含 HTML 原型，不依赖后端数据和第三方 CDN。原型用单页状态切换模拟现有告警页与新增处置页，保留左侧导航、顶部统计卡、告警列表、地图占位和右侧详情结构；交互只模拟已核实告警自动进入处置以及处置结果回写。

**Tech Stack:** HTML5、CSS3、原生 JavaScript；PowerShell `python -m http.server`；真实浏览器回读验证。

## Global Constraints

- 保留现有深蓝监控台、左侧导航、顶部统计卡、告警列表、地图和详情布局。
- 告警页不增加第二次人工核实；原型由合法性页核实事件触发进入处置页。
- 处置页必须显示 `有风险` 或 `无风险`、风险原因和依据时间。
- 固定按钮文字为 `暂不处置`、`通知上级`、`无需通知`。
- 不新增反制算法、通知规则、处罚流程或真实后端写入。
- 不修改已有用户改动；不提交 `dist`、日志、运行数据或凭据。

---

### Task 1: 创建基于现有截图的可点击原型

**Files:**
- Create: `deliverables/prototypes/alarm-disposal-redesign.html`

**Interfaces:**
- Consumes: 原有截图中的深蓝页面结构与本设计文档的页面状态规则。
- Produces: `window.location.hash` 为 `#alarm` 或 `#disposal` 的双视图原型；处置结果显示在页面时间线上。

- [x] **Step 1: 建立页面骨架**

  创建固定顶栏、左侧导航、告警统计卡、告警筛选/列表、地图占位和详情面板。默认 `#alarm` 展示告警页，选中一条已由合法性页核实的高风险告警。

- [x] **Step 2: 添加处置页结构**

  添加 `#disposal` 视图，按“事件摘要—风险依据—处置进度—上级通知—操作记录”排列，风险徽标只能从原型状态读取 `有风险` 或 `无风险`。

- [x] **Step 3: 实现最小交互**

  实现以下原生 JavaScript 行为：

  ```js
  function enterDisposal() {
    window.location.hash = '#disposal'
  }

  function chooseDisposal(value) {
    state.disposal = value
    render()
  }

  function chooseNotification(value) {
    state.notification = value
    render()
  }
  ```

  告警详情的“进入处置”模拟合法性页核实成功后的自动进入；处置页按钮分别写入当前页面状态，刷新同一页面时保留视觉结果。

- [x] **Step 4: 添加响应式和状态样式**

  在 1280px 宽度下保持现有截图的左右分栏；窄屏时详情区移到列表下方。状态颜色沿用蓝/黄/红/青四类现有语义，不添加新的视觉主题。

- [x] **Step 5: 运行文件与脚本检查**

  Run: `python -c "from pathlib import Path; p=Path('deliverables/prototypes/alarm-disposal-redesign.html'); assert p.exists() and p.stat().st_size > 10000; print('prototype file ok')"`

  Expected: 输出 `prototype file ok`；脚本语法和交互行为由真实浏览器验收覆盖。

### Task 2: 本地服务与真实浏览器验收

**Files:**
- Read: `deliverables/prototypes/alarm-disposal-redesign.html`
- Verify: 浏览器实际页面和交互状态

**Interfaces:**
- Consumes: Task 1 的 HTML 文件。
- Produces: 可访问的本地原型页面以及自动进入处置、按钮回读证据。

- [x] **Step 1: 启动静态服务器**

  Run from `E:\houtaiguanlii`:

  ```powershell
  python -m http.server 8765 --directory deliverables/prototypes
  ```

  Expected: `http://127.0.0.1:8765/alarm-disposal-redesign.html` 返回 HTTP 200。

- [x] **Step 2: 浏览器读取告警页**

  打开原型，确认左侧导航、六张统计卡、告警列表、地图占位和右侧详情同时可见，视觉基线与现有截图一致。

- [x] **Step 3: 实际点击进入处置**

  在真实浏览器点击“进入处置”，回读 URL hash 为 `#disposal`，并确认处置页显示同一告警编号、风险状态和来源依据。

- [x] **Step 4: 实际点击三个业务按钮**

  分别点击 `暂不处置`、`通知上级`、`无需通知`，回读按钮状态和操作记录文本，确认三者互不覆盖，且页面没有出现第二个告警核实按钮。

- [x] **Step 5: 检查控制台与交付文件**

  确认浏览器控制台无页面脚本错误，原型文件可直接通过绝对路径打开；不将临时服务器日志或下载物复制进仓库。

### Task 3: 原型交付说明

**Files:**
- Modify: `docs/superpowers/specs/2026-10-08-alarm-disposal-page-redesign-design.md`（仅在原型验证发现设计歧义时更新）

**Interfaces:**
- Consumes: Task 2 的浏览器回读结果。
- Produces: 原型路径、验证步骤和明确的未接入真实 API 边界。

- [x] **Step 1: 回读原型状态**

  对照设计文档确认“自动进入处置、风险状态、暂不处置、通知上级、无需通知”五项均能在页面中找到并操作。

- [x] **Step 2: 汇报原型入口**

  提供原型的绝对路径和本地访问 URL；明确说明这是基于当前截图的前端原型，尚未替换业务前台源码或写入后端状态。
