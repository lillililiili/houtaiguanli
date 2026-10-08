# 第四条故障测试运行说明

所有故障只作用于本轮隔离实例。不要在验收数据库、真实 MQTT 连接或现场设备上执行断连、停止或夹具操作。运行前读取容器实际映射，不照抄旧文档端口。

## 启动及基线

在仓库根目录：

```powershell
& .\scripts\item4-environment.ps1 -Action Status
& .\scripts\item4-environment.ps1 -Action Start
& 'D:/Software/python311/python.exe' .\scripts\item4-baseline.py --frontend 'D:/沉积岩/demo-ronghe' --output .\server\target\item4-20261007\baseline-new.json
```

基线脚本拒绝覆盖已有输出；每次使用新文件名。容器脚本只操作匹配名称/标签、仅绑定 loopback 的实例。`Stop` 不删除容器和持久库，因此同一轮恢复使用同一份数据库。测试 schema 由各自夹具创建和回收，不使用验收 schema。

## 正式回归

Maven 命令仅在 `server/`：

```powershell
$env:JAVA_HOME='C:/Users/19221/.jdks/temurin-17'
$env:POSTGRES_TEST_URL='jdbc:postgresql://127.0.0.1:25432/stage456_verify_item4_20261007'
$env:POSTGRES_TEST_USER='item4'
$env:POSTGRES_TEST_PASSWORD='isolated-unused' # 专用 loopback 测试实例，不是业务库凭据
$env:ITEM4_RESTART_TESTS='true'
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/19221/.m2/repository' '-Dtest=Item4FusionProcessRecoveryPostgresTest' test
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/19221/.m2/repository' '-Dtest=Item4AuthorizationRecoveryPostgresTest#savedApplicationAndApprovalSurviveProcessCrashesWithoutAnExecutionRequest' test
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/19221/.m2/repository' '-Dtest=Item4ReceiptRecoveryPostgresTest#committedReceiptSettlesOnceAfterCrashAndCreatesOneOrdinaryJammingChild' test
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/19221/.m2/repository' '-Dtest=Item4EoRecoveryPostgresTest#pendingBeginAndEndKeepOccupancyAndPauseAcrossCrashes' test
$env:POSTGRES_TEST_URL='jdbc:postgresql://127.0.0.1:25432/maintenance_flow_verify_item4_20261007'
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/19221/.m2/repository' '-Dtest=Item4MaintenanceRecoveryPostgresTest#committedPassCannotCompleteAfterRestartAndNewFault' test
```

每条命令将输出重定向到**新日志**，然后运行 `../scripts/item4-results.py <日志路径>` 保存该命令真正执行的 Surefire XML 和计数。不得在另一个命令覆盖同一套 XML 后才归档。必须核对 `tests > 0`、失败/错误为 0、必测无跳过。方法选择器用于只运行新增进程切点，父类原有业务测试由相关回归另跑。

完整套件编排见 `scripts/item4-regressions.ps1`、`scripts/item4-crash-regressions.ps1`、`scripts/item4-final-regressions.ps1`；这些脚本遇到已有同名证据即拒绝运行。最终回归脚本支持 `-EvidenceLabel final-round-02`，将日志放入新的子目录。其他重复执行也应使用新日志目录/名称，不删除旧结果。不要把所有 PostgreSQL 套件都指向 stage456 库，视频使用 advisory 前缀、运维/报表使用各自前缀。

Python 故障及全部测试在现有 `tools/device-simulator/` 目录执行：

```powershell
$env:PYTHONPATH='C:/Temp/houtaiguanlii-local-runtime/python-deps'
$env:ITEM4_BROKER_CONTAINER='item4-mosquitto-20261007'
$env:ITEM4_MEDIA_CONTAINER='item4-mediamtx-20261007'
$env:ITEM4_DOCKER='C:/Users/19221/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
$env:ITEM4_RUN_LABEL='item4-20261007'
$env:ITEM4_FFMPEG='D:/沉积岩/houtaiguanli/server/target/item2-media/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe'
$env:ITEM4_EVIDENCE_DIR='D:/沉积岩/houtaiguanli/server/target/item4-20261007/process-evidence-new'
& 'D:/Software/python311/python.exe' -m unittest discover -s tests -p 'test_*.py'
& 'C:/nvm4w/nodejs/node.exe' --test tests/test_*.cjs
```

容器变量名是 `ITEM4_BROKER_CONTAINER`；误写为 `ITEM4_MQTT_CONTAINER` 会使故障测试跳过，不能算通过。媒体故障脚本只挂起/停止自己创建的 FFmpeg 进程。

管理端 npm 命令只在 `ruoyi-ui/`，按 package.json 执行测试、lint、build；业务前台在其仓库执行现有契约、构建及扫描。实际运行命令、数量以日志为准。

## 页面彩排与资料

沿用 [完整模拟资料](../simulation-materials/README.md)、通知配置和场景模板。每轮重新生成时间、批次、目标/计划/空域 ID。稳定设备可复用；同一空域版本不能用相同生效时间覆盖不同范围。网络结果未知先回读，不盲目换幂等键重写。

普通处置使用已有 `sim_acc23_operator` 申请、`sim_acc23_approver` 审批，再回原申请人执行。密码由用户自行输入，不保存。通知接收器必须先记录平台真实出站请求，才发送相应阶段回执；电话 PLAYED 之前不能进入电话后观察。

每轮保存目标、研判、告警、事件、通知、授权、指令、干扰及移送关联；设备运维必须独立恢复核验；光电视频实际加载鉴权清单和分片、连续解码。正常演示只使用正常配置，异常注入另用隔离实例。

暂停、停止、API 错误或登录失效后，先保存 `/api/status`、`/api/realtime/status` 的脱敏证据，再停止本轮响应端。不得重启后自动补发旧指令成功回执。结束时恢复原通知配置，保留错误和业务历史。

## 停止

在仓库根目录执行 `scripts/item4-environment.ps1 -Action Stop`，只停止本轮隔离 Broker、媒体和数据库；不删除持久数据或日志。验收服务 8081/5173/5175/8766 保持运行且种子关闭。受控重启验收服务必须在保存上一轮结果后执行，仅停止已核对路径/端口的所属进程，不能全局停止 Java/Python/网络。
