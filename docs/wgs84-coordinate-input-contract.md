# WGS-84 经纬度写入契约

2026-10-20：业务坐标采用 WGS-84，经度在前、纬度在后，单位为度。WGS-84 不等同于 CGCS2000；输入校验不进行坐标转换，也不能仅凭经纬度数值证明来源实际使用的基准。

## 人工及外部资料输入

下列平铺经纬度或坐标数组请求在提供位置时须同时提交 `coordinate_system: "WGS84"`（兼容 `WGS-84` 拼写）。缺失、空白、GCJ-02、BD-09、CGCS2000 等声明会返回参数错误，不能通过重新标记其他坐标系的原数值来完成转换。

| 写入入口 | 坐标字段及声明位置 |
| --- | --- |
| 设备新增、编辑及 TCP / MQTT / 光电接入 | 顶层 `longitude`、`latitude`、`coordinate_system`；无位置设备可以不填，台账统一保存 `WGS-84` |
| QA 反制、雷达及模拟观测源/气象设备登记 | 顶层经纬度与 `coordinate_system`；同样受环境、权限及来源约束 |
| `POST /api/v1/airports` | 顶层 `longitude`、`latitude`、`coordinate_system` |
| `POST /api/v1/airports/{id}/protected-targets` | 顶层 `longitude`、`latitude`、`coordinate_system` |
| `POST /api/v1/evidence-files` | multipart 的 `capture_longitude`、`capture_latitude`、`coordinate_system`；不填写采集位置时可不填声明 |
| 本地模拟计划新建及资料补录 | `filing` 内的起飞/降落经纬度与 `coordinate_system`；每组经纬度须成对，均不填写时可不填声明 |
| `POST /api/v1/local-interface-simulator/weather` | 提供顶层 `polygon` 时必须填写顶层 `coordinate_system`；仅行政区域预报可不填 |
| 已有 QA 气象风险输入 `/local-interface-simulator/weather-risks` | 顶层 `polygon` 与 `coordinate_system` |

本地模拟入口的环境、来源、权限、幂等和版本限制继续有效。增加坐标声明不允许前台业务账号使用后台管理接口，也不改变已有事实、风险规则或历史材料。

新写计划资料把已校验的声明规范保存为 `flight_plan.filing_coordinate_system = WGS84`，业务详情与模拟器补录详情返回 `filing.coordinate_system`，支持读取后再次补录。追加迁移只增加可空列，不为旧起降点推定或回填坐标系；历史声明缺失仍为未知。天气预报声明随原始模拟报文保存、回读。

## 设备上报

- MQTT `StaticData` 带设备经纬度时须声明 `coordinate_system`；`SenseData` 的目标坐标和遥控器坐标须在报文、对应目标或对应扩展节点声明 WGS-84。兼容 `coordinateSystem` 字段拼写；多个声明必须全部有效，内层其他坐标系不能被外层声明覆盖。
- 光电 `BeginTracking` 的带位置观测在报文、`metadata` 或 `aiStatus` 中声明；无声明或其他坐标系不进入融合，也不续期有效观测。真实成功/失败回执仍按原关联关系结算，`EndTracking` 停机回执不依赖坐标声明。
- 规范化模拟目标和气象观测同样须声明；目标项可以继承报文声明。模拟器自产的 WGS-84 位置在生成时附带声明，原始外部报文不能被直接重标。
- 二进制雷达按既有协议的 WGS-84 RTK/参考点转换生成规范化报文；平台生成时明确附带 WGS-84，不虚构设备协议字段。不能确认参考坐标时仍按既有规则拒绝位置。
- 融合映射层再次校验，防止绕过接入接口的未声明位置进入业务。生产融合工作器将失败报文标记失败并继续处理后续报文，原始载荷及历史观测保持不变。

## 几何数据

- 空域边界和本地航线使用 GeoJSON：没有坐标系声明时按 GeoJSON 契约读取 WGS-84；显式的 `crs`、`coordinate_system`、`coordinateSystem` 必须为受支持的 WGS-84 声明。空域导入分别校验集合、要素、属性及几何节点，不能由外层 WGS-84 声明覆盖内层其他基准。
- 机场跑道和进离场程序的 `centerline` 使用二维 EWKT：只接受 `SRID=4326;LINESTRING (...)`，至少两个点，每点均须在有效经纬度范围。未声明 SRID、其他 SRID、其他几何类型或额外维度会被拒绝。
- 所有新位置均检查有效数值、经纬度范围及必要的成对关系；没有位置的数据保持没有位置，不补为 `(0, 0)`。

这些约束作用于新写入。旧坐标、冻结快照和历史回执不会被批量改写；已有历史数据是否确实使用 WGS-84，仍需来源协议或测量依据确认。仅更新接口代码不表示外部系统已切换；模拟器或其他调用方应在发送新位置前同步声明。

计划起降点的声明由追加迁移 `V202610200005__flight_plan_filing_coordinate_system.sql` 的可空字段保存，新写经验证后规范为 `WGS84`，详情原样返回以支持再次编辑。旧行保持 `NULL`，不会在升级时推定基准。管理端和模拟器表单遇到有位置但声明缺失或不受支持时，要求确认来源并重新录入 WGS-84 坐标；不会把原数值直接改标。
