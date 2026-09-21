# 设备模块参考对齐 · 2026-09-18

参考：https://perform-browser-harbour-supplied.trycloudflare.com/ 的 devices、commission、monitor 页面。按用户要求使用白色 UI，保留管理端外壳。

| 页面 | 参考布局 | 实现与数据来源 |
| --- | --- | --- |
| 设备管理 | 六项统计、组合筛选、台账与右侧预览 | device overview/list/options/detail；区域、供应商筛选送到服务端；位置、参数、接口、区域标签；完整档案与厂家资料保留 |
| 设备调测 | 顶部流程、设备选择/配置/结果三栏、联调记录 | commission information/list/events/report；保留现有五步状态机、权限、版本与真实报告；不虚构校准步骤或网络质量 |
| 设备监测 | 统计、设备分类树、指标、告警、日志 | overview/tree/state/history/incidents/events；真实指标按资源/信号/链路分类；维修入口保留到下方 |

## 范围与状态

仅前端三个页面和其专用组件/样式。未更改后端、权限、已应用迁移或其他未提交工作。
设备台账维护 → 按设备 ID 查看档案/运行状态/调测；调测创建/连接/配置/执行 → 持久化任务和事件 → 结果报告/联调记录。
任务未完成期间锁定设备；只有 CONNECTED 可编辑和保存本次配置，READY 可执行；终态保留报告与历史。MQTT 等不支持主动调测的协议展示接入诊断。
缺失参数保持未登记/未上报，零值正常显示。运行信息失败清除旧读数，暂停轮询支持手动刷新。

## 资产

无外部图片或生成资产。统计图标来自现有 @element-plus/icons-vue（Monitor、CircleCheck、CircleClose、Warning、Bell、OfficeBuilding）；位置图标为 Location。位置卡展示登记坐标和地址；不伪造地图底图。
参考站中的模拟计数、天气、品牌、全站导航、数据源切换控件不迁入后台。暂无后端能力的批量导入、校准、时间同步及感知质量聚合未新增假按钮。

## 验证记录

- 参考与实现截图：1280 × 720；另检查 768 × 900，三页 document.scrollWidth 均为 768，无整页横向溢出。内表格允许水平滚动。
- 第一轮：六项统计在中等屏幕偏挤；监测告警栏受全局 1360px 断点干扰，掉入下方。
- 第二轮：统计在 1400px 以下三列排布；局部覆盖监测布局，1280px 三栏同排；标题卡改为纯白。图标采用现有组件，无字符替代；标签、输入、动作与选中状态已查看。
- `*-round1.png`、`*-round2.png`：两轮截图；`*-compare1/2.png`：与参考并排；`*-overlay1/2.png`：叠加核对。颜色、外壳、五步调测、真实数据和无地图底图为明确适配差异，不按像素一致宣称复刻。
- 浏览器实际验证：河口区筛选返回 2 台设备，重置恢复 92 台；详情基础参数/位置标签；调测 NORMAL 搜索得到 12 台并保持当前对象；监测暂停出现继续刷新按钮；控制台未发现错误。
- 未在现场设备执行调测、停用、删除等写操作。
- `npm run lint` 通过；`npm run build` 通过。
- 首轮原有 80 测试通过。加入 4 项回归测试后的并发执行受资源竞争发生 5 秒超时；`npm test -- --maxWorkers=1 --minWorkers=1` 重跑，20 个测试文件、84 项测试全部通过（37.65 秒）。既有 monitorInformation 测试输出未 mock 维修接口的 jsdom 网络日志，但断言通过。

## 代码变更说明

| 文件 | 方法 / 计算属性 | 作用与本次变化 |
| --- | --- | --- |
| DevicesView.vue | filters、reset、metrics、rate、healthText | 区域/厂家筛选送到现有 list 接口；六项统计；多行台账列；调用专用详情预览 |
| DeviceCatalogPreview.vue | device、fields、archive、设备 ID watch | 位置/基础/接口/区域标签，保留完整档案厂家资料；切换设备恢复位置标签；来源和零值保留 |
| CommissionView.vue | deviceGroups、selectionLocked、stepIndex、loadRecords、viewReport、duration | 三栏调测界面，区域类型搜索，状态约束，真实历史记录与报告；任务操作后刷新记录 |
| MonitorView.vue | metrics、typeGroups、visibleMetrics、filteredIncidents、loadAggregate | 通道/类型树，分类指标，分级告警及 detected_at 时间，列表截断提示；日志和维修模块下移 |
| OperationMetrics.vue | 无具体方法；图标映射与展示模板 | 六项统计的白色卡片及响应式布局 |
| operations-reference.css | 无具体方法；局部样式 | 三页白色标题、卡片、表格、日志和焦点状态；不影响其他管理页面 |
| operationReference.test.js | 四项 it 场景 | 搜索无副作用、历史记录切换/报告、活动任务锁定、零坐标与缺失信息 |
