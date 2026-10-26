# GitHub Backend CI 修复验证（2026-10-21）

## 对应失败记录

- [Backend CI #149](https://github.com/lillililiili/houtaiguanli/actions/runs/39705606620)：Moquette 测试依赖的 Bugsnag 动态范围 `[3.0,4.0)` 无法解析，尚未进入测试。
- [Backend CI #150](https://github.com/lillililiili/houtaiguanli/actions/runs/39709623421)：提交 `04faac296a77cd81986f76bf7c90dbf3655fa45e`，6509 项中 9 个断言失败、4 个执行错误，1447 项跳过；后续目标读取、通知和打包步骤未执行。

## 修复范围

1. 在 `server/pom.xml` 固定既有测试实际使用的 Bugsnag 3.8.0，保留 Moquette 测试依赖范围，不升级依赖。
2. MQTT 带坐标心跳夹具声明 WGS84；新增缺失、空白、错误和冲突声明均不写坐标、不推进心跳水位的回归，并验证另一设备/位置的有效消息仍可接受。
3. Stage85、Stage9 和计划编号独立迁移测试调用 `MigrationLineage.configure`，保留来源目录精确断言，校验空库实际加载原本地迁移。
4. 计划编号测试明确新增 `filing_coordinate_system` 为 `null`，继续完整比较原历史字段、回执及关系，重复迁移执行数为零。
5. 空域测试按现行维护契约提供 AMSL 高度，恢复真实并发创建，校验一成功、一冲突，且空域、版本、来源和成功审计各一份。
6. 飞手撤离的最小关系夹具补齐空域删除时间，覆盖删除前历史和删除时刻边界。
7. 前台融合权限测试使用与数据库一致的身份，验证读取授权及范围；残留平台维护动作授权仍拒绝。
8. 告警 CSV 测试保留中文违规原因及历史无授权原因断言，校验新增预测列，并确保普通告警不伪造预测事实。

未修改生产业务守卫或已应用迁移，未执行 Flyway repair。

## 验证环境与结果

使用上述失败提交加本轮 9 个代码/测试文件的独立副本，避免纳入工作区正在进行的单位逻辑删除改动。运行环境为 Windows、Java 17、H2 和 PostgreSQL 16/PostGIS 3.5。数据库为本轮新建的 `stage456_verify_ci150`、`stage2_target_verify_ci150`、`advisory_verify_ci150`，未连接业务库执行测试。开发种子仅由已有隔离测试配置启用，未启动验收服务或更改验收配置。

| 验证组 | 用例数 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| 全部失败类及 AuthApiTest | 118 | 0 | 0 | 0 |
| 空库、本地/远端历史迁移 | 5 | 0 | 0 | 0 |
| CI 后续目标读取 | 3 | 0 | 0 | 0 |
| CI 后续通知、证据预览及融合输入 | 101 | 0 | 0 | 0 |
| 合计（19 个测试类） | 227 | 0 | 0 | 0 |

后端 `package` 成功；打包使用 `-DskipTests`，测试计数来自此前实际执行的测试报告。9 个修改文件与验证副本逐文件哈希一致，`git diff --check` 通过。

以下为有效验证命令，均在独立副本的 `server/` 中执行，省略日志重定向与报告目录参数。每组使用对应隔离库的 `POSTGRES_TEST_URL`，数据库身份由环境变量提供；Windows Unix socket 临时目录指定为已创建的 ASCII 路径。

```powershell
$ciSocket = Join-Path $env:TEMP 'houtaiguanli-ci150-sockets'
New-Item -ItemType Directory -Path $ciSocket -Force | Out-Null

# stage456_verify_ci150
.\mvnw.cmd -o -B '-Dtest=LegacyLocalPlanNumberMigrationTest,LocalPlanNumberPostgresTest,Stage9PostgresTest,PostgisPilotDepartureWatchTest,MqttIngressTest,MqttIngressPostgresTest,Stage85PostgresTest,AlarmViolationReasonFilterApiTest,AlarmViolationReasonFilterPostgresApiTest,Stage8AccessControlServiceTest,AuthApiTest' "-Dtest.unix-socket-directory=$ciSocket" test
.\mvnw.cmd -o -B '-Dtest=FeatureMigrationLineagePostgresTest' "-Dtest.unix-socket-directory=$ciSocket" test

# stage2_target_verify_ci150
.\mvnw.cmd -o -B '-Dtest=TargetReadPostgresApiTest' "-Dtest.unix-socket-directory=$ciSocket" test

# advisory_verify_ci150
.\mvnw.cmd -o -B '-Dtest=UavAdvisoryPostgresTest,AutoSmsPostgresTest,AutoVoicePostgresTest,EvidencePreviewPostgresTest,FusionInboxFreshSourceTest,MergedEvaluationAlarmTest' "-Dtest.unix-socket-directory=$ciSocket" test
.\mvnw.cmd -o -B -DskipTests package
```

完整日志保留在 `server/target/ci150-*.log`，XML 报告位于 `server/target/ci150-verification/server/target/surefire-reports/`，均不纳入版本管理。首次受限环境下未完成的测试进程已停止，以上计数仅来自随后成功完成的验证。三个临时测试数据库已在确认无连接后清理。

本轮未重跑 6509 项完整首阶段，未提交或推送修复，也未重新触发 GitHub 检查；旧失败记录不会被本地验证改写。
