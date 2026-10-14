# 自动档核对与通知零等待

## 用户确认与实现范围

2026-10-13：取消短信送达后3秒、电话播放完成后10秒的固定观察等待。沿用每2秒后台调度及原位置、飞手、通道、录音、回执和幂等校验；电话完成且未明确撤离后可进入待反制，由操作员判断并人工发起，服务端继续校验资格。没有修改历史通知、撤离快照或自动规则启用状态。

实现：NotifyFlow 两个共享窗口改为0；AutoVoiceService 删除旧等待文案并保持未来时间校验；前台实时位置核对显示送达/播放完成时间，历史快照仍展示原观察时间。

## 当前运行核对

- 8081运行进程加载本仓库构建的后端JAR，profiles为local,qa；实际DB_URL为 `jdbc:postgresql://127.0.0.1:5432/houtaiguanli_fresh3`。工作目录为 `/Users/frank/git/houtaiguanli-latest/server`，资产读取须继续保持该目录。初查依旧说明误读了同容器旧houtaiguanli库，已纠正；旧库18条停用规则不代表当前系统。
- 开启：融合处理、合法性自动研判、C04空间风险、自动规则执行引擎、自动短信、自动电话、replay目标光电自动跟踪、计划状态推进、设备到时关闭与回执同步。
- 实际运行库三类共6条规则全部开启：核实为置信度至少80%、观测不超过60秒；反制资格为观测不超过60秒、高风险；通知处罚为依据不超过120秒、事件关联目标明确。持续满足时间均为0，缺失依据等待15秒后转人工异常处理。当前库没有规则变更审计，不能推测配置操作时间或操作者。
- 自动核实没有发生的证据：当前核实状态53条REVIEW（观测缺失、过期或时间异常）、8条条件未满足、3条事件已排除，自动核实历史为0条。以目标20261013-115为例，在观测仅7.717/9.936秒时，时效条件PASS而置信度UNKNOWN；最新目标classification_confidence为空且unknown_fields标NOT_REPORTED。停止上报后又出现观测超过60秒的FAIL。不能把类别已识别、风险评分或融合权重补成80%识别置信度。
- 实际运行库通知接收端enabled=true且租约有效；没有选用上传电话录音，启动参数也未提供录音。前台电话详情显示通道未连接或未选用有效WAV录音。当前电话的明确阻塞是缺少录音，模拟通知不代表真实短信/电话已接入。
- 反制必须人工发起；四通道设备运行策略60秒，每5秒检查关闭，到成功关闭回执才完成。处置完成后的自动移交和自动通知处罚各自继续校验当前条件。
- 验收库保持dev-seed=false，没有向验收库写测试业务数据。

## 验证计划与证据

1. 先修改既有状态机、观察读取与电话API测试，在原实现下54项中6项因仍等待而失败。
2. 修改共享窗口与显示后，原54项通过；扩展通知与自动规则回归，随package通过109项，无失败、错误或跳过。
3. 前台24项通知测试、build和scan通过。PostgreSQL时序测试使用独立stage456_verify_notification_20261013库及随机schema；首次按旧说明25432连接被拒，改为实测端口5432后2项通过，失败/错误/跳过均为0。观测由实际PostGIS读取、使用实际时钟，本次短信送达到电话启动25毫秒，电话播放完成到读取待反制69毫秒；随后新观测仍可确认仍在，重复处理不重拨。该耗时只证明隔离调用无固定等待，不是生产调度或真实渠道SLA。
4. 已更新本机8081运行包，保留原DB_URL、local,qa及资产工作目录，dev-seed=false；重启后health=UP，6条自动规则仍全部开启，引擎心跳更新且last_error为空。Chrome刷新后正常恢复登录态、列表及同一告警详情；查看1013-009显示“待定是否反制”，当前观测已过期时“发起反制”仍禁用并完整显示原因。电话继续如实显示通道/录音不可用，未实际拨打或发起反制。新通知零等待全流程由上述隔离API测试验证，浏览器未执行新通知流程或过期登录重登验收。
5. 两个仓库git diff --check通过；没有覆盖原有未提交改动。

命令：后端 `bash mvnw -q -Dtest=NotifyFlowTest,UavDepartureObservationTest,AutoVoiceApiTest,AutoSmsApiTest,SimulatorAdvisoryReceiptTest,UavAdvisoryApiTest,UavHandoffProgressTest,AutomationRuntimeEvaluatorTest,AutomationRuntimeApiTest,AutoCountermeasureApiTest package` 实际发现109项；PostgreSQL单独运行 `NotificationPresenceTimingPostgresTest` 2项。前台 `node --test tools/departureHistoryView.test.mjs tools/voiceWhenSmsNotSent.test.mjs tools/notificationConnectivity.test.cjs tools/handoffAdvisory.test.cjs tools/advisoryRecovery.test.cjs`、`npm run build`、`node tools/scan.cjs`。

## 代码变更说明

| 文件/类 | 方法/函数 | 方法作用 | 本次作用 |
| --- | --- | --- | --- |
| server/.../alarm/domain/NotifyFlow.java | 两个等待常量，phase消费 | 统一通知阶段与观察时间 | 短信/电话固定等待均为0 |
| server/.../alarm/application/AutoVoiceService.java | eligible | 判断自动电话资格 | 去除3秒等待文案，保持时间异常与其他门禁 |
| server/src/main/resources/application-local.yml | 无具体方法，注释 | 说明本地电话流程 | 与零等待规则一致 |
| dongying-vue/src/components/disposal/UavAdvisoryPanel.vue | 无具体方法，模板 | 展示通知后位置核对 | 实时显示回执时间，历史保留原观察时间 |
| dongying-vue/src/components/disposal/AutoSmsNotice.vue | retry | 打开短信重试/登记表单 | 更新后续电话流程说明 |
| NotifyFlowTest、UavDepartureObservationTest、AutoVoiceApiTest、NotificationPresenceTimingPostgresTest | 即时阶段、即时电话、观察读取、实际时序测试 | 验证状态/API/持久层与重复调用 | 将旧3/10秒预期改为无等待，保留未知、权限和历史分支；PostgreSQL仅允许回环地址隔离库，适配实际端口 |

不把单元测试、模拟设备或浏览器只读检查表述为真实通知通道或真实反制设备验收。
