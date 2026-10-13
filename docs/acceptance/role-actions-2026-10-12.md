# 角色动作权限与当前前台功能对齐

用户要求清理前台已没有功能入口的动作权限。本次以业务前台 `src/config/navModel.js`、`src/pages/registry.js` 的可达页面、页面实际调用和后端授权依赖为依据；仅有旧服务函数、标签字典或遗留页面不视为仍有入口。

## 从前台角色配置移除

| 动作码 | 核对依据 |
| --- | --- |
| `airport:read`、`airport:manage` | 当前导航和页面未调用机场服务；机场数据及历史保留 |
| `flight:verify` | 飞行任务已读取设备检查及核验结果，无手动执行核实/反馈入口；读取仍用 `flight:read`、`route:read` 等原权限 |
| `airspace:manage` | 空域页读取外部同步空域、版本与差异，无本地新增/维护入口 |
| `fusion:revise`、`fusion:manage` | 融合感知没有人工改类、合并/分裂或维护融合配置入口 |
| `risk:evaluate` | 当前风险界面查看、核验已有风险，不提供手动触发规则评估；后台自动评估不依赖该前台动作 |
| `evidence:link` | 当前证据界面无手动追加关联入口；光电证据采集的关联沿用 `evidence:ingest` 用例 |
| `punishment:file`、`punishment:decide`、`punishment:review`、`punishment:close` | 当前移送与处罚页只承接移送和读取处罚结果，不办理立案、裁量、复核、结案 |
| `map:read` | 前台无地图资源管理入口，业务地图不调用地图包管理接口；后台地图管理保留 |

上述为本次额外移除的 13 项。`map:upload`、`map:activate`、`map:delete`、`rule:manage` 原已不在前台角色页展示，本次同时从可配置目录和隐藏动作草稿中排除。

## 继续保留的依赖

- `punishment:read`：`PunishmentOutcome.vue` 读取已移送案件和决定文书。
- `fusion:read`：融合感知读取来源在线状态，证据链读取融合依据。
- `assessment:evaluate`：合法性页面重新研判；`rule:read` 读取规则版本和效果依据。
- `device:read`：大屏设备汇总和证据设备关联的读取守卫；共享 `devices.read/op` 仍用于设备列表和光电/反制控制。
- 证据读取、预览、采集、下载、冻结和销毁：现用证据页及光电视频采集入口。
- 告警/风险核验、移送、反制申请/审批/直接反制/执行/停止：保留独立动作和原有范围、时效、回执及安全守卫。

## 生效与回归

服务端目录、角色授予和两套动作鉴权共用同一可配置集合。已有前台会话不能凭旧数据库授权调用下线动作；后台固定身份的权限不扩张。角色编辑仅提交可见目录，保存后清除旧动作授权、撤销旧会话，并追加授权前后审计。原权限目录记录、业务历史及超级管理员固定权限不删除。

回归覆盖完整 30 项现用目录、17 项目录外动作拒绝赋权、不同角色及旧 READ/OP 授权在两套鉴权路径均被拒绝、现用动作仍可用、保存清理及审计/会话失效；同一 API 用例在 H2 与独立 PostgreSQL/PostGIS 库执行。管理前端验证不将隐藏的旧授权重新提交，并保留共享设备控制和直接反制原有规则。

实际验证：管理端 `npm run lint`、`npm test`（50 个文件、350 项通过）、`npm run build` 均通过；后端在独立源码副本运行 `./mvnw.cmd -B package -Dtest=AuthApiTest,RoleActionPermissionApiTest,RoleActionPermissionPostgresTest,BackendUserApiTest,SystemManagementApiTest,Stage*AccessControlServiceTest -DargLine=-Xmx1024m`，79 项通过，0 失败、0 错误、0 跳过，其中 PostgreSQL 为 16 项。测试仅在 H2 和独立 `role_actions_20261012` 库中启用测试夹具，未修改业务库、验收种子配置或运行中的服务。上线需部署新后端后重新加载角色页面。
