# 模拟上级空域同步报文

使用已有 `/api/v1/local-interface-simulator/airspaces`，来源固定为 `local-airspace-upstream / mock`。这不是尚未提供的正式上级接口协议。

资料录入成功后，在仓库根目录生成本次报文：

```powershell
python docs/acceptance/simulation-materials/upstream/build_airspace_messages.py --prepared server/target/simulation-materials/prepared-materials.json --output server/target/simulation-materials/airspace-messages
```

生成器只写文件，不请求接口、不创建空域。以当前时间生成半小时有效窗口；每批使用独立空域编号。测试区域位于独立位置，不把撤销测试用于主闭环的告警空域。再演示时换一个空输出目录生成新批次；重试原提交必须复用原文件。

使用原模拟器已有授权代理 `POST http://127.0.0.1:8766/api/external/request`，请求结构如下，`body` 替换为文件中的完整 JSON：

```json
{
  "method": "POST",
  "path": "/local-interface-simulator/airspaces",
  "body": {},
  "key": "使用该报文的原 message_id"
}
```

也可由现有授权账号直接调用平台 API。勿把密码或 Bearer 会话写入文件。该接口不通过 MQTT，不能在 MQTTX 发布 JSON 代替 HTTP 输入。

| 顺序 | 报文 | 预期 |
| --- | --- | --- |
| 1 | `01-create.json` | ACCEPTED，mock，revision 1；记录平台空域及版本 ID |
| 2 | `02-exact-repeat.json` | 原样重复，返回同一空域和版本，不新增版本 |
| 3 | `03-update.json` | 同一空域 revision 2，高度上限 120 米 AMSL；保持名称、单位和区域 |
| 4 | `04-stale-rejected.json` | `UPSTREAM_REVISION_CONFLICT`，原版本不变 |
| 5 | `05-message-conflict-rejected.json` | `SOURCE_MESSAGE_CONFLICT`，原版本不变 |
| 6 | `06-withdraw.json` | revision 3，缩短当前版本有效期；历史仍可查 |

每步回读 `/local-interface-simulator/airspaces/context`，核对源模式、版本、报文载荷和对应版本 ID。最终再从空域管理页面刷新核对。接口超时或结果未知时先回读，禁止直接再写一个新 message_id。

气象风险使用场景 `17-risk-weather.json`：独立预警输入生成风险，普通天气预报仍是单独事实。飞行计划、飞手和报送关联则由完整模拟资料工具及场景启动时通过既有 `/local-interface-simulator/plans` 提交。
