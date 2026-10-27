# 航班避让：反制前查附近民航航班（2026-10-26 用户确认）

## 用户确认的规则

1. 范围数字在后台「规则管理 → 航班避让」里设，默认：迫降 禁止反制 10 公里、提醒 30 公里、3000 米以下；驱离 提醒 5 公里、1000 米以下（驱离没有禁止范围）。修改需要 `responsePlans.auth`，带版本校验、幂等键与审计，变更记录可查。
2. 禁止反制范围内有航班：不能反制，没有人工放行。
3. 收不到航班信息（接收机 30 秒没有数据，或反制设备没有位置）：人工可以勾选「仍要反制」后继续；自动反制不启动。
4. 反制进行中有航班飞进禁止反制范围：系统自动停止反制。
5. 地图默认显示接收范围 350 公里内的航班。

## 怎么判断

- 只检查四通道反制设备上的反制（`COUNTERMEASURE`、历史 `JAMMING`）。反制方式以授权上的 `countermeasure_mode` 为准；旧授权 `JAMMING` 按驱离、其余按迫降。自动反制按迫降检查。
- 高于该方式规则高度的航班不算；没有高度的按低空算。
- 按航班现在的速度和航向，300 秒内会飞进某个范围的，按那个范围算（取直线外推的最近距离）。
- 没有在用的航班接收机（未登记、真实设备待接入、模拟接收机已停用或 10 分钟没数据）时不检查，反制照旧。

| 结果 | 人工申请 / 直接反制 / 下发前 | 自动反制 | 反制中 |
|---|---|---|---|
| CLEAR 附近没有航班 | 通过 | 通过 | 继续 |
| WARN 提醒范围有航班 | 勾选「已确认附近航班，继续反制」才通过 | 不启动 | 继续 |
| NO_DATA 收不到航班信息 | 勾选「仍要反制」才通过 | 不启动 | 继续 |
| BLOCK 禁止反制范围有航班 | 拒绝（409 `AIRCRAFT_NEARBY`） | 不启动 | 自动停止 |

- 申请、直接反制：提交体 `flight_check_ack`（布尔）。未确认时 409 `AIRCRAFT_CONFIRM_REQUIRED`。
- 执行（下发前一刻）：再查一次；本次 `flight_check_ack` 或该授权申请时已确认都算确认。拦下时授权保持 APPROVED，记事件 `AIRCRAFT_NEARBY`，事务提交后返回 409。
- 自动反制：`AlarmRuleCounter` 在创建授权前和排队下发前都查；不启动的原因记一条 `AUTO` 检查，结果不变不重复记。
- 反制中：`DisposalFlightGuard` 每 5 秒查一次（`app.disposal.flight-guard.*`）。BLOCK 时 `EmergencyStopService.stopForAircraft`：授权转 STOPPED，`result_code=STOPPED_BY_AIRCRAFT`，急停记录 origin `SYSTEM_AIRCRAFT_STOP`，以执行人身份下发全关，回执按原急停流程跟踪。

每次检查记在 `counter_flight_check`（阶段 REQUEST / DIRECT / EXECUTE / AUTO / RUNNING、结果、是否确认、确认人、当时的航班），告警详情「附近航班记录」读 `GET /api/v1/flight-avoidance/checks?event_id=`（需 `alarm:read`）。

## 接口

- `GET/PUT /api/v1/flight-avoidance/policy`、`GET …/policy/history`：规则（PUT 只限后台）。
- `GET /api/v1/flight-avoidance/check?device_id=&countermeasure_mode=`：反制弹窗提示用，需任一处置权限（申请、直接反制、执行、审批或查看）。
- `GET /api/v1/flight-avoidance/traffic`：融合感知「民航航班」图层，需 `target:read`。
- `/api/v1/adsb-receivers`：后台登记航班接收机，可填机身识别码 `station_guid`（16 位十六进制，唯一）；`GET …/unmatched-stations` 给登记页提示本服务的 UDP 端口和最近送来数据但没登记的识别码。登记后为「待接入」（停用），数据对上识别码就先收着；管理员看到「最近收到数据」后在设备台账启用（没填识别码不能启用），启用后参与检查。
- 真实接收机：客户的 pingStation3（uAvionix ICD Rev F）在它的网页里把发送地址填成本服务地址和 `app.adsb.pingstation.udp-port`（默认 30021），JSON 一架一条通过 UDP 推送，每 30 秒一条状态报文（只更新「最近收到数据」）。`PingStationUdpListener` 收、`PingStationMessage` 换算（毫米→米、百分之一度→度、厘米/秒→米/秒，航班号前三位对常见航空公司），没登记的识别码一律不收。端口打不开只记日志。
- 设备模拟器（仅 local+qa 且 `app.qa.device-setup.enabled`）：`POST /api/v1/local-interface-simulator/adsb-receiver` 登记（带固定识别码 `a1b2c3d4e5f60001`），航班按 pingStation3 格式通过 UDP 推给上面同一个端口（和真实设备同一条路），`…/{id}/aircraft` 仍可直接送航班，`…/{id}/release` 停用。航班数据只留 10 分钟。

前台没有新增前台动作，FrontendActionPolicy 白名单未改。

## 还没定的

- 真实接收机现场联调：接收机的网络地址、能否连到本服务、识别码（2026-10-27 已按 pingStation3 资料接好，等正式设备）。
- 反制设备 900M 等频段对航班的实际影响范围；现在的默认值是用户确认的保守值，以后按厂家资料在规则管理里调整。
