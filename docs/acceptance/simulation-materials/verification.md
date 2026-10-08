# 模拟资料准备验证记录

日期：2026-10-06。工作仓库基线：`7510b2ecc717448acb98b5228c8475d9e8f4d730`，保留已有未提交改动。本次新增资料准备工具及测试，未修改 Java/Vue 业务实现、迁移、生产依赖或菜单。

## 实际结果

| 检查 | 结果 |
| --- | --- |
| 本机后端 `8081` | `UP`，`local,qa`；启动参数明确 `--app.dev-seed.enabled=false` |
| 测试语音 | WAV 可读、非静音数据、16.029 秒、22050 Hz/单声道；SHA-256 与元数据一致 |
| 模拟目录 | 4 个明确标注的联系人，1 个模拟报送单位关联；复用原验收单位/区域 |
| 配套计划 | `SIM-ACC23-MATERIAL-ONLY`，mock；当日时段、SN、航线版本/坐标/AMSL 回读一致，主体 `LINKED` |
| 独立回读 | 原模拟器会话再次读取 plan-options 与 plan-filing，确认飞手可选、报送关联启用、主体一致 |
| 通知通道 | RISK_NOTICE、ADVISORY_SMS、ADVISORY_VOICE、PLAN_FEEDBACK、UAV_PUNISHMENT，共 5 类启用的本机独立模拟通道 |
| 空间风险 | `space-risk-demo-v1` 生效；保留 `DEMO`。合法性及自动动作参数未改动 |
| 账号 | `sim_acc23_operator` 为申请人并具有执行权限；`sim_acc23_approver` 为另一审批人；均 ACTIVE、待首次改密，无 disposal:direct |
| 资料写入日志 | 19 条 ACCEPTED；REJECTED/UNKNOWN/PENDING 均为 0；日志不含口令或会话 |
| 场景校验 | 17 份原始模板 + 17 份带实际关联的 ready-scenes，全部由现有 compile_scene 读取通过 |
| 通知配置校验 | 8 份由现有 validate_config 读取通过；模拟播放至少 17 秒，保留原观察时长 |
| 准备工具回归 | 16 项通过，0 失败、0 跳过；包括密码脱敏、未知结果阻止重发、旧资料冲突、权限分离、范围与时间边界 |
| 本机页面请求校验 | 无有效来源/CSRF 的 2 次请求均 403，未触发准备任务 |
| 空域真实 HTTP | 6 类结果符合预期；新增/重复/修订/撤销回读成功，两个冲突分支正确拒绝；最终独立空域 revision 3/WITHDRAW |
| 浏览器 | 录入页实际显示完成；账号和通知等资料由用户在该页登录后，经平台现有鉴权接口执行 |

全部准备动作使用现有 API，无直接业务表写入。实际 ID、时间、版本、提交记录及完整接口回读位于 `server/target/simulation-materials/`，不加入源码提交。可分享压缩包包含模拟关联和资料说明，不含原始日志、口令、Bearer 或媒体凭据。

首次空域检查脚本错误地按平台原始错误码、全部历史列表断言，而现有模拟器代理返回错误文本、context 只返回每个空域最新回执。这两处检查预期已按现有契约修正并复测通过；未改后端业务规则。原始实际接口已正确拒绝旧版本及同号异文。最终验证器保留这些契约断言。

## 实际命令

均从 `D:/沉积岩/houtaiguanli` 执行；Python 使用本机既有 3.11，未安装依赖。

```powershell
$env:PYTHONUTF8='1'
& D:/Software/python311/python.exe -m unittest discover -s server/src/test/python -p test_simulation_materials.py -v
& D:/Software/python311/python.exe scripts/prepare_simulation_materials.py
& D:/Software/python311/python.exe scripts/prepare_simulation_materials.py --serve
# 浏览器由用户登录并执行；工具在本机保存回读结果并注销本次平台会话。
& D:/Software/python311/python.exe docs/acceptance/simulation-materials/upstream/build_airspace_messages.py --prepared server/target/simulation-materials/prepared-materials.json --output server/target/simulation-materials/airspace-messages
$env:PYTHONPATH='C:/Temp/houtaiguanlii-local-runtime/python-deps;D:/沉积岩/demo-ronghe/tools/device-simulator'
& D:/Software/python311/python.exe server/src/test/python/check_prepared_simulation_materials.py
git diff --check
```

空域 6 份文件实际通过原模拟器 `POST /api/external/request` 顺序发送至 `/local-interface-simulator/airspaces`；原始报文及回执保存在 `airspace-messages/` 和 `airspace-validation.json`。验证器再次只读核对最终结果，无重复写入。直接平台调用的冲突为 HTTP 409；原模拟器代理表现为 HTTP 400，错误文本携带原 409 及具体原因。

本次没有修改业务前后端代码，未重复执行全仓 Java/Vue 构建；此前第二条的分层回归及剩余浏览器项保留在原报告中，未冒充为本次执行。

## 尚未代表的完成项

- 本轮完成的是模拟资料录入及材料验证，不是三条业务链的全部浏览器验收。
- 两个测试账号仍需本人首次登录改密。申请人申请、另一账号审批、原申请人执行，审批和执行仍逐次发生。
- 接收器必须由原模拟器实际启动；本次没有生成通知送达、签收、处罚办结或反制成功记录。
- 运维成功分支仍需足够的受控健康输入；协议 A 心跳恢复不能冒充健康 GOOD，原 UNKNOWN 阻断保留。
- 自动动作规则、处置策略、运行时授权/证据、视频及通知异常矩阵仍需继续按第二条计划逐项演练。

上述限制在 README 和资料定义中同步列明，不能因资料包已准备而把原第二条报告改成全部通过。
