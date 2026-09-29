# 页面基准与范围

- 管理端：ruoyi-ui/src/assets/styles/index.css；AdminLayout.vue；InterfacesView、DevicesView、ResponsePlansView、CommissionView、ReportsView。
- 管理端截图参考：../maintenance-flow/live-commission.png。
- 业务端：E:/rwurenji/_rong/dongying-vue/src；现有 tokens、business-theme、布局和组件为基础。
- 空域：AirspaceMonitorPanel、useAirspaceMonitor 有页面内模拟切换和帧播放。
- 视频：RiskOpticalPanel、TargetLiveVideo 当前引用 Canvas 模拟视频；需保留追踪并接入独立视频源。
- 接口：天气可保存 mock 配置，真实服务适配未接通，设计不能暗示保存即接通。
- 设备：回放通道选项仍在；历史模拟标识保留。
- 预案：正式、模拟、回放创建选项；设计移除后两者，原版本管理保留。
- 报表：正式数据口径拟由服务端落实，设计状态不代表当前已实现。
- 短信与录音电话不改；独立模拟器继续用于联调；本任务不删除历史。
- 浏览器现状为登录页，后端未在常见端口监听；不修改认证或启动业务后台来造样例。
