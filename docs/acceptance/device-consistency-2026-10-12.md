# 设备一致性与类型清理验证（2026-10-12）

## 后续纠正：恢复反制设备

用户指出原反制设备不属于要求移除的六类，并要求恢复。原“15 台业务设备 + 1 台仅在全部台账可见”的结果撤回。修正统一读取规则：允许模拟环境内，对成功明确登记、关联模拟协议来源且已有上报事实的本机 QA 设备计数，模拟标识保留。无新增设备、运行库数据修改或动作授权变化。

本节之后的“15 台”及浏览器受限记录描述前次实施经过，不代表本次纠正后的结果。

本次纠正已验证：

- 真实浏览器中融合感知显示“反制 1 台”、总数 16；后台设备管理在“业务设备／启用”条件下为 16，实时监测为 16，接入调测为 16 且包含原本机四通道模拟设备。模拟来源和范围说明保留。截图位于忽略的 `server/target/countermeasure-panel-restored.jpg`。
- `mvnw.cmd -Dtest=DeviceConsistency*Test package`：H2、隔离 PostgreSQL/PostGIS 各 14 项，共 28 项通过，0 失败／错误／跳过，打包成功。新场景更换随机设备／来源／外部编号及类型，覆盖明确登记、未登记、来源不匹配、未上报、生产隔离、权限范围与模拟标识；已有六类禁用测试继续通过。
- `StatisticsScopeTest` 4 项、`DashboardSnapshotApiTest` 9 项、`ReportingFormalFactsApiTest` 9 项通过。合计 50 项相关测试通过。首次新增夹具缺少与 source_id 成对的 external_device_id，修正夹具后 H2 与 PG 重跑通过。
- 当前服务已加载修复，浏览器刷新后直接生效；未额外重启服务、未读取登录凭据、未修改运行库设备或登记审计。开发种子保持关闭。
- 本轮仅修改后端读取规则、回归及说明，没有前端代码变更，因此未重复前端构建。日志为 `server/target/countermeasure-visibility-tests.log` 和 `countermeasure-visibility-final.log`。

## 交付范围

- 运维消息只读待办，处理中、已办结不进入消息列表、分页总数及角标。
- 业务前台感知清单、大屏、运行统计和后台设备管理、实时监测、接入调测默认使用启用业务设备范围，保留权限和真实／模拟来源隔离。
- 设备清单和汇总共用筛选；监测、调测完整分页；前台不按类型白名单或缺坐标丢弃设备；设备资料和状态来自同一台账及状态表。
- 后台通过明确的“全部台账（含测试样例）”选择保留样例维护入口，不把样例计入前台业务数量。运维关联历史上下文仍读取原设备事实。
- AOA、驱鸟炮、协议破解、诱骗、干扰、RemoteID 六类从当前台账删除，新增登记、MQTT 接入及重新启用由后端拒绝。无新增迁移或种子修改。

## 当前运行库核对

通过既有停用、删除接口处理上述 7 台设备（干扰 2 台，其余各 1 台）。随后对运行库仅查询设备状态及审计：六类未删除记录为 0，已删除且停用为 7，`device_delete` 审计为 7。原协议材料、异常历史及审计保留。

当前未删除台账 16 台：雷达 7、光电 2、TDOA 4、5G-A 2、四通道反制 1。其中反制设备是本机测试样例，按既有 `StatisticsScope` 不纳入业务统计。前后台默认业务范围为 15 台；未扩大统计来源条件或把模拟记录标成真实。

浏览器曾实际读到后台设备管理汇总及列表总数 15。后续已登录标签页失去自动化连接，独立标签页进入登录页，因此未完成全部页面的最终浏览器逐项验收。临时登录标签页已关闭，未更改账号或权限。

运行中接口核对的会话提取步骤被自动审批拒绝，原因是从数据库提取活动登录令牌。未重试或绕过该步骤；后续用不含凭据的只读台账核对及隔离接口测试验证。

## 自动化验证

以下命令在相应项目目录执行；隔离 PostgreSQL 使用临时 `stage456_verify_*` 库，结束后删除。运行验收服务的开发种子保持关闭，测试夹具只在隔离测试上下文启用。

| 验证 | 结果 |
| --- | --- |
| `server`: `mvnw.cmd -Dtest=DeviceConsistency*Test package`，H2 + PostgreSQL/PostGIS | 24 项通过，0 失败、0 错误、0 跳过；185 项迁移在隔离 PG 成功；打包通过 |
| `DeviceOperationsApiTest,DeviceCatalogScopeApiTest,AuthApiTest,ReportingFormalFactsApiTest,DashboardSnapshotApiTest,MqttConnectionAdminApiTest,MqttStatusAccessTest` | 67 项通过，0 失败、0 错误、0 跳过 |
| `ruoyi-ui`: `npm run lint` | 通过 |
| `ruoyi-ui`: `npm test -- --minWorkers=1 --maxWorkers=2` | 48 文件、327 项全部通过 |
| `ruoyi-ui`: `npm run build` | 通过 |
| `dongying-vue`: `node tools/situationApiContract.test.cjs` | 116 项断言通过 |
| `dongying-vue`: `node --test tools/bigScreenStatScope.test.cjs` | 4 项通过 |
| `dongying-vue`: `node tools/scan.cjs` | 219 文件规则扫描通过 |
| `dongying-vue`: `npm run build` | 通过；保留已有大包体积提示 |

接口回归包含动态编号与范围、live/replay/mock/本机样例分支、未上报与缺位置、停用与删除、越权与撤权、101/501 台分页、ONLINE/ABNORMAL 状态及时间字段一致性、共享只读统计权限、六类类型（含大小写）登记／MQTT 接入／重新启用拒绝。

过程中的失败均已定位：新增夹具补全 `received_at` 非空与已删除必须停用约束；大屏旧夹具补充业务设备菜单授权；PostgreSQL 命令补齐正确的 `POSTGRES_TEST_USER` 环境变量。修正后重跑相应测试通过。初次 Vitest 的 worker 上下限冲突通过显式设置 min/max workers 解决。

日志位于忽略的 `server/target/device-consistency-*.log`，不纳入提交。此前其他功能的未提交变更保持原样，未进行提交或部署。
