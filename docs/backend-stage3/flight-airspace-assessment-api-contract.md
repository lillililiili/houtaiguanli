# 阶段 3 飞行计划、空域与合法性只读接口契约

## 交付边界

本契约冻结阶段 3 的后端只读闭环：航线版本、飞行计划、空域版本、时空冲突事实和已保存合法性研判。GET 接口只读取已存在的数据，不生成或重算合法性结论。边界接触、走廊宽度解释、AGL/AMSL 换算或证据充分性尚未确认时，结果必须保留为 `UNDETERMINED`，不得推断为合法或非法。

阶段 3A 是只读基础检查点，不代表生产合法性自动判定已经完成。

## 通用约定

- 基础路径为 `/api/v1`，响应继续使用 `ApiResponse.ok(data)`；错误使用稳定的 HTTP 状态和 `code/message`。
- JSON 字段为 `snake_case`；所有业务 ID 均为字符串；时间均为 epoch 毫秒。
- 分页从 1 开始，默认 `page=1&size=20`，最大 100，响应固定为 `items/page/size/total`。
- 列表必须使用固定排序和唯一 ID 尾键；`total` 与 `items` 使用完全相同的数据范围和筛选条件。
- 重复标量参数、未知参数、非法页码或非法时间窗返回 400，不静默忽略。
- 必须先校验动作权限，再解析筛选条件或查询路径对象，避免用错误差异探测数据。
- `ASSIGNED` 只允许完整匹配同一个 `(owner_org_id, district_id)` 授权元组；不得将两个不同授权元组交叉拼接。
- `ALL` 仍不返回没有组织或行政区归属的数据。详情、版本和关联对象越权统一返回 404。
- 不返回 `source_snapshot`、`credential_ref`、原始 Inbox payload、内部规则参数或完整飞手证件。

## 权限与接口

| 方法与路径 | 动作权限 | 固定行为 |
| --- | --- | --- |
| `GET /api/v1/flight-plans` | `flight:read` | `start_at DESC NULLS LAST, plan_id ASC` |
| `GET /api/v1/flight-plans/{plan_id}` | `flight:read` | 返回计划及其精确航线版本摘要；越权 404 |
| `GET /api/v1/routes` | `route:read` | `updated_at DESC, route_id ASC` |
| `GET /api/v1/routes/{route_id}` | `route:read` | 航线根详情；越权 404 |
| `GET /api/v1/routes/{route_id}/versions` | `route:read` | `version_no DESC, route_version_id ASC` |
| `GET /api/v1/route-versions/{route_version_id}` | `route:read` | WGS-84 中心线和高度带；越权 404 |
| `GET /api/v1/airspaces` | `airspace:read` | `updated_at DESC, airspace_id ASC` |
| `GET /api/v1/airspaces/{airspace_id}` | `airspace:read` | 返回当前有效版本摘要；越权 404 |
| `GET /api/v1/airspaces/{airspace_id}/versions` | `airspace:read` | `version_no DESC, airspace_version_id ASC` |
| `GET /api/v1/airspace-versions/{airspace_version_id}` | `airspace:read` | WGS-84 边界、高度带和有效期；越权 404 |
| `GET /api/v1/flight-plans/{plan_id}/airspace-conflicts` | `flight:read` + `airspace:read` | 只返回时间、空间和高度冲突事实，不返回合法性结论 |
| `GET /api/v1/flight-plans/{plan_id}/legality-assessments` | `flight:read` + `assessment:read` | `assessed_at DESC, assessment_id ASC`；只读已保存历史 |
| `GET /api/v1/legality-assessments/{assessment_id}` | `assessment:read` | 返回精确输入版本、规则版本、结论、原因和证据引用；越权 404 |

双权限接口按表中顺序校验两个动作权限；任一缺失均返回 403，且不得先暴露计划是否存在。

## 筛选参数

`GET /flight-plans` 接受：

```text
page, size, status_code, source_code, route_id, uav_sn,
owner_org_id, district_id, window_from, window_to, keyword
```

`window_from/window_to` 必须成对，并以计划完整时段与查询时段相交为匹配条件。计划任一时间缺失时，不匹配时间筛选；无时间筛选时仍可返回，并在 `field_issues` 标明缺失。

`GET /routes` 接受：

```text
page, size, enabled, source_mode, owner_org_id, district_id, keyword
```

`GET /airspaces` 接受：

```text
page, size, kind_code, source_mode, owner_org_id, district_id, valid_at, keyword
```

## 数据与版本语义

- 飞行计划保存并返回精确 `route_version_id`；后续发布新航线版本不得改变历史计划或研判的输入。
- 空域和航线根对象与版本对象分离；版本有效期重叠、缺失或歧义返回 `VERSION_AMBIGUOUS` 或不可判定事实，不自动选择最新值。
- `centerline` 只允许 WGS-84 `LineString`；`boundary` 只允许 WGS-84 `MultiPolygon`。不可信、空、自交、错误类型或错误 SRID 的几何不得绘制或进入正式空间结论。
- 空间候选可使用 4326 GiST 包围盒，但真实米制关系必须用 `geography` 验证；仅包围盒重叠不等于冲突。
- 无法确认边界接触语义时返回 `BOUNDARY_POLICY_UNKNOWN`；无法确认高度基准或换算依据时返回相应未知原因，不用 0 补值。
- `AirspaceConflictDto` 只表达 `horizontal_relation`、`height_relation`、`time_relation`、`conflict_code` 和 `unknown_reasons`。
- `LegalityAssessmentDto` 只返回已保存的 `conclusion_code`、输入版本、规则版本、检查项、未知原因、证据引用和 `source_mode`；读取不得更新任何业务表。

## 演示数据隔离

- 固定演示数据只允许在 `!production & (local | test)` 且 `app.dev-seed.enabled=true` 时写入。
- `production` 和 `production,local` 组合必须保持零阶段 3 演示数据。
- local 可保存 `LEGAL`、`ILLEGAL`、`UNDETERMINED` 三类确定性样例，但必须明确标记为演示规则或模拟来源，不能称为生产规则验证。
- API 失败必须显示真实失败；前端不得回退到 `window.MOCK`，也不得自行生成合法性结论或伪造写操作成功。

## 稳定错误码

```text
INVALID_PAGE
INVALID_TIME_RANGE
VALIDATION_ERROR
FORBIDDEN
FLIGHT_PLAN_NOT_FOUND
ROUTE_NOT_FOUND
ROUTE_VERSION_NOT_FOUND
AIRSPACE_NOT_FOUND
AIRSPACE_VERSION_NOT_FOUND
LEGALITY_ASSESSMENT_NOT_FOUND
VERSION_AMBIGUOUS
INTERNAL_ERROR
```

## 仍需业务确认

权威来源与同步水位、正式计划状态、外部 ID 唯一范围、跨区授权、走廊全宽或半宽、边界接触、空域类型、AGL/AMSL 转换依据、匹配容差与证据阈值、规则追溯重算制度，以及飞手/执照/审批字段的权限与脱敏要求，均不阻塞只读基础，但会阻塞生产自动判定。
