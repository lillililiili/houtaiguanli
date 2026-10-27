# 2026-10-27 电话顺序与航班避让迁移合并

## 保留的两条历史

`202610260001` 在独立分支上分别用于电话发送依据和航班避让。本次不修改已应用脚本、校验值或 Flyway 历史，也不执行 repair。

| 原脚本 | 原校验值 | 原文保存位置 |
| --- | ---: | --- |
| `V202610260001__freeze_voice_response_order_basis.sql` | 1792140 | `server/src/main/resources/db/lineage/voice-order/` |
| `V202610260001__flight_avoidance.sql` | 200317101 | `server/src/main/resources/db/lineage/flight-avoidance/` |

启动及独立 Flyway 工具继续调用 `MigrationLineage.configure`，按本 schema 的成功历史、脚本名及原校验值选择原分支。空库选择电话顺序分支。未知脚本、未知校验值或失败历史直接拒绝迁移，不猜测或修复历史。

`202610270005` 在原历史之后补齐另一分支缺失结构。航班避让结构部分缺失时拒绝升级，不重置策略和数据。`202610279001` 在补齐之后为本 schema 的 `counter_flight_check` 添加原 `disposal` 刷新信号，解决电话分支运行原 `202610269001` 时表尚未存在而跳过的问题；已有触发器保留。

远端已发布的 `202610270001__counter_flight_distance` 原样保留。只读检查本地 PostgreSQL 全部 30 个可连接数据库和其中所有 Flyway 历史，未发现本地尚未发布的实测移送 `270001/270002` 应用记录，故其首次交付版本分配为 `202610270003/202610270004`，脚本内容不变。运行库只发现电话顺序 `260001` 成功记录，本轮未迁移运行库。

## 隔离验证

新增 `VoiceFlightMigrationLineagePostgresTest`：空库、电话历史、航班历史、已应用逐设备航班距离历史均升级到完整结构；保留原 Flyway 行、原航班策略和检查记录；验证真实 PostgreSQL 通知、不可变实测移送触发器、再次迁移无动作及 validate。另覆盖未知校验值、失败历史、航班部分结构缺失时拒绝猜测。

同时回归原 `MigrationLineagePostgresTest` 与 `FeatureMigrationLineagePostgresTest`，覆盖 10 月 16 日、10 月 20 日的旧独立分支。测试使用 `stage456_verify_*` 隔离 PostgreSQL/PostGIS 数据库中的随机 schema，测试结束清理自身 schema；不使用验收业务库，不开启开发种子。

执行命令（`server/`，通过环境注入隔离连接，凭据不写入仓库）：

```powershell
.\mvnw.cmd -q "-Dtest=VoiceFlightMigrationLineagePostgresTest,FeatureMigrationLineagePostgresTest,MigrationLineagePostgresTest" clean test
```

脚本移动或改号后必须先清理旧构建产物；Maven 的增量资源复制不会删除 `target/classes` 中已经移走的旧路径。打包时两份原 `260001` 仅位于各自的 `db/lineage/` 目录，实测移送仅包含 `270003/270004`，不能同时带入旧 `db/migration/260001`、旧本地 `270001` 或旧 PostgreSQL `270002` 副本。

2026-10-27 从清理后的构建运行以上三类隔离测试：共 **17 项通过，0 失败、0 错误、0 跳过**（新电话/航班历史 7 项、10 月 20 日历史 5 项、10 月 16 日历史 5 项）。旧测试原先以“全部触发器总数为 6”判断实时通知，因已存在的单位写入保护触发器而失效；现精确断言原 6 个通知触发器和 `guard_current_organization`，保护没有删减。

测试报告为 `server/target/surefire-reports/TEST-*.MigrationLineagePostgresTest.xml`、`TEST-*.FeatureMigrationLineagePostgresTest.xml` 和 `TEST-*.VoiceFlightMigrationLineagePostgresTest.xml`。本记录不代表部署或运行库迁移已经完成；本轮未写入运行库迁移历史，也未启用开发种子。
