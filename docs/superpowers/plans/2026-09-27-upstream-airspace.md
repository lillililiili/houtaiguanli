# 上级空域下发实施计划

用户已确认：空域由上级接口下发，设备模拟器补充下发模拟。

## 设计与边界

- 平台接收空域新增、版本接替和撤销；业务页面只读，不再提供本地导入入口。旧数据及历史版本不改写来源。
- `POST /api/v1/integrations/airspaces/messages` 为正式接收契约，必须配置绑定来源及专用账号，并校验现有 Bearer 会话、接口操作权限、空域管理权限与精确数据范围。未配置明确不可用。
- `POST /api/v1/local-interface-simulator/airspaces` 仅 local/test 且非 production/prod 注册，固定 mock 来源，共用接收服务，不接受调用方指定真实来源。
- 请求为 snake_case：message_id（1~64 字母数字下划线横线）、revision（正整数）、action（UPSERT/WITHDRAW）、airspace_no。UPSERT 携带现有新建字段 name/kind_code/boundary/min_altitude_m/max_altitude_m/altitude_datum/valid_from/valid_to/change_reason/owner_org_id/district_id；WITHDRAW 只携带 effective_at/change_reason。
- 消息按来源+消息编号去重，相同内容返回原回执，不同内容冲突；同空域 revision 必须递增；更新和撤销不得回溯到最新版本生效之前，不改历史几何；来源、归属和名称不允许偷偷切换。
- 回执为 message_id/airspace_id/airspace_no/airspace_version_id/revision/action/state(ACCEPTED)/source_mode/received_at。`GET /api/v1/local-interface-simulator/airspaces/context` 返回 scopes（owner_org_id/owner_org_name/district_id/district_name）和 items（各模拟空域最新回执及 payload 原始表单）。读取和重放也重新校验范围。
- 模拟器在资料输入区增加“空域下发”：表单、地图草稿区域选择/GeoJSON、提交与回执；共享已有登录，401 清空读取结果、保留草稿，不触发 MQTT。
- 正式供应商协议、网络和账号未提供，本次交付平台接收契约及模拟联调，不宣称供应商已对接。

## 执行清单

- [x] 后端：先新增接口测试，验证尚缺入口；追加来源及消息迁移、接收服务、事务去重/版本保护；旧人工写入口默认关闭。
- [x] 模拟器：增加空域输入与地图区域导入、白名单代理和交互测试；保留当前其他未提交改动。
- [x] 业务页：移除本地导入操作，统一上级下发说明，保留只读历史和现有监测。
- [x] 验证：受影响后端测试、认证测试、package，独立 PostgreSQL/PostGIS schema 验证迁移与行为；模拟器测试、业务前端构建/扫描及浏览器检查。
- [x] 交付：更新接口/运行说明，检查差异，重启相关服务并检查端口与 HTTP 返回。

完成证据及文件/方法清单见 [变更与验证](../../上级空域下发-变更与验证-2026-09-27.md)。复核中发现原空间查询会把模拟空域用于真实业务，已补充来源隔离回归并修正全部相关生产调用。
