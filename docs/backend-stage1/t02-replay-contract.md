# T02 雷达回放协议契约

本文档固定 T02 雷达 v3.0.0 在阶段 1 的协议解析、回放输入、来源隔离、失败处理和测试边界。阶段 2 及后续实现必须沿用这里的名称、字节规则和幂等语义，不得自行改名或放宽校验。

## 1 范围与结论

- 本阶段唯一允许的数据源模式是 `replay`，回放适配器的 `mode()` 必须返回 `SourceMode.replay`。
- 排除范围：不建立 `live` 连接，不发送任何雷达控制指令，不实现反制能力，不根据 T02 点迹或航迹自动判警。
- 协议给出的网络端点只是一项设备文档示例。实现不得硬编码该地址或端口，回放也不得读取或探测该端点。
- `REQUEST_RTK` 是已知协议指令，但阶段 1 只识别其协议事实，不发送该指令，也不以其回复推进任何设备状态。
- 一个输入只有在来源键、帧边界、CRC 和消息负载全部校验成功后，才可以进入后续只读数据归一化。任何失败都不得写目标、轨迹、设备成功状态或告警。

## 2 协议帧事实

### 2.1 传输角色和字节序

设备文档规定上位机使用 TCP 客户端角色，雷达使用 TCP 服务端角色。所有数值字段默认使用网络字节序，也就是大端序；CRC 的线上双字节顺序按 2.3 节的专门规则处理。字符串采用“2 字节长度加 MBS 内容”的协议形式，但本阶段解析的五类消息不含字符串字段。

### 2.2 帧布局

| 顺序 | 字段 | 宽度 | 契约 |
| --- | --- | ---: | --- |
| 1 | `magic` | 4 字节 | 固定为 `0x55AA55AA` |
| 2 | `frame_length` | 4 字节无符号数 | 从 `command` 开始到 CRC 结束的总字节数，不包含 `magic` 和 `frame_length` 自身 |
| 3 | `command` | 4 字节无符号数 | 见 2.4 节 |
| 4 | `protocol_frame_id` | 4 字节无符号数 | 初值为 0，每次发送后递增；超过 `0xFFFFFFFF` 后回到 0 |
| 5 | `additional_data` | N 字节 | 由指令决定 |
| 6 | `crc` | 2 字节 | CRC16-MODBUS，覆盖 `command + protocol_frame_id + additional_data` |

`frame_length` 的协议最小值为 10。完整帧字节数必须等于 `8 + frame_length`，附加数据长度必须等于 `frame_length - 10`。阶段 1 另设 `MAX_FRAME_LENGTH = 16_777_216` 字节作为内存安全上限；长度小于 10、大于该上限、发生整数溢出或与实际字节数不一致，都属于非法长度。解析器在验证长度和计数前不得按不可信数值分配数组。

`protocol_frame_id` 只用于短时间关联及协议回复匹配。由于它会回绕，且回放可重复执行，它不是来源唯一键，也不是时间顺序依据。上传消息负载内的 8 字节 `Frame ID` 统一称为 `radar_frame_no`，不得与外层 `protocol_frame_id` 混用。

### 2.3 CRC16-MODBUS

CRC 固定采用以下可执行定义：

1. 初始余数为 `0xFFFF`，按反射算法逐字节处理，生成多项式为 `0xA001`。
2. 输入字节只包含 `command`、`protocol_frame_id` 和 `additional_data`，不包含帧头、帧长度或 CRC 字段。
3. 常规 CRC16-MODBUS 数值的低 8 位先上线，高 8 位后上线。设备文档附录的查表函数返回 `(uchCRCHi << 8) | uchCRCLo`；将该返回值按网络字节序写出，得到相同的“低字节、高字节”线上顺序。
4. 比较必须针对线上两个字节，不得把整个帧按无符号整数重新解释。

设备文档说明调试下发时可使用 `0xCCCC` 跳过校验，这只是一项协议事实。阶段 1 回放和验收测试禁止启用该旁路；CRC 字节为 `cc cc` 时仍按普通 CRC 值比较，只有计算结果也确实为该值才可通过。

### 2.4 已知指令

| 指令值 | 协议名称 | 阶段 1 处理 |
| --- | --- | --- |
| `0x00000001` | `HEARTBEAT` | 解析为 `Heartbeat` |
| `0x00000002` | `LOGIN` | 对雷达入站回复解析为 `LoginReply` |
| `0x00030001` | `UPLOAD_TARGET_V3` | 解析为 `TargetUploadV3` |
| `0x00030002` | `UPLOAD_TRACK_V3` | 解析为 `TrackUploadV3` |
| `0x00010005` | `REQUEST_RTK` | 已知但超出阶段 1 消息集合，保留为 `UnsupportedMessage` |
| `0x00010006` | `UPLOAD_RTK` | 解析为 `RtkUpload` |
| 其他值 | 未纳入阶段 1 | 保留为 `UnsupportedMessage` |

## 3 固定内部接口和类型

下列签名是后续实现的唯一命名，不增加平行接口：

```java
interface AdapterPort {
    SourceMode mode();
    void start(InboundFrameSink sink);
    void stop();
}

interface InboundFrameSink {
    void accept(InboundFrame frame);
}

record InboundFrame(
    String sourceCode,
    String sourceNamespace,
    String sourceMessageId,
    long receivedAt,
    byte[] payload
) {}
```

字段语义固定如下：

- `sourceCode` 是启动适配器时配置的来源代码，不从 NDJSON 内容推导。
- `sourceNamespace` 按 5.2 节生成。
- `sourceMessageId` 是 `record_no` 的规范十进制字符串，不补零、不使用科学计数法。
- `receivedAt` 直接取 NDJSON 的 `received_at`，单位为 epoch 毫秒。
- `payload` 是 `frame_hex` 解码得到的原始协议帧候选字节。校验成功时它必须是包含帧头、帧长度和 CRC 的一个完整帧；校验失败时仍以原字节进入 Inbox 并记录失败。调用方如需长期保留，必须复制数组；适配器不得在 `accept` 返回后修改该数组。

回放适配器使用单工作线程，按文件物理行顺序调用 `accept`，同一适配器实例不并发调用 sink。`start` 原子地从停止态进入运行态并启动一次回放；运行中或停止中的第二次 `start` 必须以 `ADAPTER_ALREADY_RUNNING` 失败，不得创建第二个读取器或重绕文件。正常 EOF 后回到停止态；此后再次 `start` 从首行重放，并依赖来源幂等键消除重复影响。`stop` 是幂等操作，并且返回后不得再发生新的 `accept` 调用。

解析消息类型只允许以下名称：

| 类型 | 固定逻辑字段 |
| --- | --- |
| `LoginReply` | `protocolFrameId`、`permissionType`、`statusCode` |
| `Heartbeat` | `protocolFrameId`、`reserved` |
| `RtkUpload` | `protocolFrameId`、`latitudeRaw`、`longitudeRaw`、`headingRaw`、`satelliteCount`、`altitudeRaw` |
| `TargetUploadV3` | `protocolFrameId`、`utcMicros`、`radarFrameNo`、`scanStartRaw`、`scanEndRaw`、`scanDirection`、`targetCount`、目标项 |
| `TrackUploadV3` | `protocolFrameId`、`utcMicros`、`radarFrameNo`、`uploadTimestampMs`、`scanStartRaw`、`scanEndRaw`、`northReference`、`scanDirection`、`targetCount`、航迹项 |
| `UnsupportedMessage` | `command`、`protocolFrameId`、完整原始帧和 `UNSUPPORTED_MESSAGE` 原因 |

`UnsupportedMessage` 是显式的不支持结果，不是解析成功的替代物；它只保留溯源信息，不产生业务写入。

## 4 消息负载契约

所有无符号 32 位字段在 Java 中使用可容纳完整范围的数值表示，不能直接落入有符号 `int` 后丢失高位。所有缩放都先保留原始整数，再生成派生值。

### 4.1 固定长度消息

| 类型 | 指令 | 附加数据长度 | 字段顺序与约束 |
| --- | --- | ---: | --- |
| `LoginReply` | `0x00000002` | 6 | `permissionType: UINT32`，`statusCode: UINT16`；状态 0 表示设备回复登录成功，其他值表示失败，但阶段 1 不主动登录 |
| `Heartbeat` | `0x00000001` | 8 | `reserved: UINT64`，协议规定固定为 0；非零按 `MESSAGE_FIELD_INVALID` 处理 |
| `RtkUpload` | `0x00010006` | 32 | `latitudeRaw: INT64`、`longitudeRaw: INT64`、`headingRaw: INT64`、`satelliteCount: INT32`、`altitudeRaw: INT32` |

设备文档把登录权限类型 3 定义为管理员类型、5 定义为数据类型；雷达回复包含权限类型和状态码。阶段 1 只解析入站回复，不构造登录请求，也不把设备登录成功解释为平台授权成功。

`RtkUpload` 的纬度、经度和航向分辨率均为 `1e-9` 度。`altitudeRaw` 在设备文档中标为预留且当前无效，必须原样保留但不得作为任何高度基准。

### 4.2 TargetUploadV3

`TargetUploadV3` 的附加数据固定为 32 字节头部加 `24 * targetCount` 字节目标项：

| 区域 | 字段顺序 | 类型和单位 |
| --- | --- | --- |
| 头部 | `utcMicros` | `INT64`，微秒 |
| 头部 | `radarFrameNo` | `INT64`，雷达处理帧号 |
| 头部 | `scanStartRaw`、`scanEndRaw` | 各 `INT32`，每单位 `0.0001` 度 |
| 头部 | `scanDirection` | `INT32`；0 为顺时针，2 为逆时针，其他值无效 |
| 头部 | `targetCount` | `INT32`，必须大于等于 0 |
| 每项目标 | `xRaw`、`yRaw`、`zRaw` | 各 `INT32`，每单位 `0.01` 米 |
| 每项目标 | 两个预留字段 | 各 `UINT16`，保留但不解释 |
| 每项目标 | `snrRaw` | `UINT16`，每单位 `0.01` dB |
| 每项目标 | 一个 2 字节预留字段和一个 4 字节预留字段 | `UINT16`、`INT32`，保留但不解释 |

负载长度必须精确满足 `32 + 24 * targetCount`。计数为负、乘法溢出或剩余字节不为零，都以 `MESSAGE_COUNT_INVALID` 失败。

### 4.3 TrackUploadV3

`TrackUploadV3` 的附加数据固定为 40 字节头部加 `64 * targetCount` 字节航迹项：

| 区域 | 字段顺序 | 类型和单位 |
| --- | --- | --- |
| 头部 | `utcMicros` | `INT64`，微秒 |
| 头部 | `radarFrameNo` | `INT64`，雷达处理帧号 |
| 头部 | `uploadTimestampMs` | `INT64`，毫秒 |
| 头部 | `scanStartRaw`、`scanEndRaw` | 各 `INT32`，每单位 `0.0001` 度 |
| 头部 | 两个预留字段 | 各 `UINT8`，保留但不解释 |
| 头部 | `northReference` | `UINT8`；0 表示相对正北，1 表示相对转台零位 |
| 头部 | `scanDirection` | `UINT8`；0 为顺时针，2 为逆时针 |
| 头部 | `targetCount` | `INT32`，必须大于等于 0 |
| 每条航迹 | `xRaw`、`yRaw`、`zRaw` | 各 `INT32`，每单位 `0.01` 米 |
| 每条航迹 | `vxRaw`、`vyRaw`、`vzRaw` | 各 `INT32`，每单位 `0.01` 米每秒 |
| 每条航迹 | `targetId` | `UINT32`，同一目标跨帧保持航迹号 |
| 每条航迹 | `snrRaw` | `INT16`，每单位 `0.01` dB |
| 每条航迹 | `rcsCoarseRaw` | `INT16`，每单位 `0.01` 平方米 |
| 每条航迹 | 三个 2 字节预留字段 | `INT16`、`UINT16`、`UINT16`，保留但不解释 |
| 每条航迹 | `targetType` | `INT8`；0 未完成识别，1 人，2 车，3 无人机，4 飞鸟，5 未识别 |
| 每条航迹 | 一个 1 字节、一个 8 字节和两个 4 字节预留字段 | 原样保留但不解释 |
| 每条航迹 | `rcsFineRaw` | `INT32`，每单位 `1e-6` 平方米 |
| 每条航迹 | `selected` | `INT32`；0 未选中，1 选中，其他值无效 |

设备文档在重复展示“目标 N”时把位置 `zRaw` 的单位误写成速度单位；阶段 1 以同表“目标 1”定义和位置字段语义为准，固定为 `0.01` 米。粗、细两个 RCS 字段都保留，禁止静默选择一个覆盖另一个。负载长度必须精确满足 `40 + 64 * targetCount`，计数和剩余字节按 `TargetUploadV3` 相同规则校验。

### 4.4 REQUEST_RTK 协议事实

设备文档定义请求附加数据为两个 `INT8`：开启标志取 0 或 1，第二字段固定为 0；回复附加数据为一个 `UINT16` 状态码，0 表示设备回复成功。该指令不在阶段 1 的五类解析结果中，因此入站帧保留为 `UnsupportedMessage`。

## 5 回放 NDJSON 和来源唯一键

### 5.1 文件格式

回放文件固定为无 BOM 的 UTF-8 NDJSON。每个物理行必须是一个完整 JSON 对象；最后一行可以没有换行符，空行不允许。每行只允许以下四个字段：

```json
{"dataset_id":"t02-v3-synthetic-001","record_no":1,"received_at":1731464128000,"frame_hex":"55aa55aa..."}
```

| 字段 | 约束 |
| --- | --- |
| `dataset_id` | 必填字符串，匹配 `[a-z0-9][a-z0-9._-]{0,127}`；同一文件只能出现一个值 |
| `record_no` | 必填 JSON 整数，范围为 1 到 `Long.MAX_VALUE`；生产夹具中同一数据集内唯一 |
| `received_at` | 必填 JSON 整数，范围为 1 到 `Long.MAX_VALUE` 的 epoch 毫秒；0 不表示未知，也不得超出 Java `long` 正整数范围 |
| `frame_hex` | 必填非空字符串，只含偶数个十六进制字符，不带 `0x`、空格或分隔符；字符数不得超过 `2 * (8 + MAX_FRAME_LENGTH) = 33_554_448`；生成器统一输出小写，读取器可接受大小写 |

每行的 `frame_hex` 必须恰好解码为一个完整协议帧。十六进制可解码但得到零帧、多个粘连帧、前导或尾随字节时，先按 5.2 节保存该行的原始消息封装，再以 `REPLAY_RECORD_FRAME_COUNT_INVALID` 把该 Inbox 置为 `FAILED`，避免一个 `sourceMessageId` 对应多个设备消息。拆包和粘包能力在流式帧解析器单元测试中验证，不通过改变 NDJSON 的一行一帧约束来验证。

读取器必须使用有界读取，在扫描 `frame_hex` 字符串时累计字符数，一旦超限立即失败，不能先物化超限字符串再检查；十六进制解码和原始字节数组分配只能发生在长度检查通过后。超限直接按 `FRAME_HEX_INVALID` 隔离该行。该上限来自完整帧最大字节数 `8 + MAX_FRAME_LENGTH = 16_777_224`，不能另设更大的回放旁路。

回放按物理行顺序处理，不按 `record_no` 排序。文件打开失败、非法 UTF-8 或无法确定行边界属于文件级损坏并终止本次运行；单行 JSON、字段或十六进制错误记录行号后隔离该行，继续下一行，最终运行结果为失败。错误报告不得输出完整载荷。

### 5.2 来源和哈希

`source_code` 由适配器配置提供，必须匹配 `[a-z0-9][a-z0-9._-]{0,63}`，因此不能包含冒号。来源字段固定为：

```text
source_namespace = replay:<source_code>:<dataset_id>
source_message_id = record_no 的规范十进制字符串
payload_hash = SHA-256(frame_hex 解码后的原始字节)，输出 64 位小写十六进制
source_unique_key = (source_namespace, source_message_id)
```

`sourceCode` 保存配置值，`sourceNamespace` 和 `sourceMessageId` 分别保存上式结果。`protocol_frame_id`、`radar_frame_no`、目标 ID 和载荷哈希都不得替代来源唯一键。

生成 `source_namespace` 后必须在创建 `InboundFrame` 和查询 Inbox 前执行硬校验：字符串只能由上述 ASCII 片段和分隔冒号组成，字符数与 UTF-8 字节数都不得超过 128。即使 `source_code` 和 `dataset_id` 分别满足各自格式，组合值超限仍按 `REPLAY_LINE_INVALID` 处理；此时尚未形成合法来源键，不插入 Inbox。不得截断任一片段来适配 `inbox_message.source varchar(128)`。

`sourceCode` 必须通过大小写敏感的精确等值匹配解析到唯一的 `integration_source.source_code`，不得去除字符、折叠大小写、模糊匹配或自动创建来源。新 T02 消息必须把匹配行的 `integration_source.source_id` 写入 `inbox_message.source_id`；没有精确匹配时拒绝摄取，不插入 Inbox。

Inbox 列映射固定如下：

| Inbox 列 | 唯一来源和写入规则 |
| --- | --- |
| `source` | `InboundFrame.sourceNamespace` 原值 |
| `source_msg_id` | `InboundFrame.sourceMessageId` 原值 |
| `source_id` | `InboundFrame.sourceCode` 精确匹配到的 `integration_source.source_id` |
| `received_at` | `InboundFrame.receivedAt` 原值，epoch 毫秒 |
| `payload_hash` | `SHA-256(InboundFrame.payload)`，64 位小写十六进制 |
| `payload` | 下述四字段规范 JSON 对象 |

`inbox_message.payload` 固定保存由原 NDJSON 行规范化得到的 JSONB 对象：

```json
{"dataset_id":"t02-v3-synthetic-001","record_no":1,"received_at":1731464128000,"frame_hex":"55aa55aa..."}
```

该对象必须且只能包含 `dataset_id` 字符串、`record_no` JSON 整数、`received_at` JSON 整数和 `frame_hex` 字符串。`frame_hex` 通过把 `InboundFrame.payload` 重新编码为无分隔符的小写十六进制生成，因此能够无损还原完整原始帧候选字节；其他三个值与已校验的 NDJSON 字段完全一致。JSONB 不承诺键的物理排列顺序，但键集合、类型和值固定。该对象是内部原始消息封装，不是 REST DTO，也不得由阶段 1 的业务查询接口返回。

### 5.3 幂等和冲突

- 首次出现唯一键时，先持久化不可变原始消息和 `payload_hash`，再解析业务内容。
- 相同唯一键且哈希相同是幂等重放。后续记录不更新首次保存的 `receivedAt`、载荷或解析结果，不重复产生历史点、最新状态或其他业务影响。
- 相同唯一键但哈希不同必须返回 `SOURCE_MESSAGE_CONFLICT`。由于 `UNIQUE(source, source_msg_id)`，不得插入第二条正常 Inbox，也不得通过改写来源命名空间、消息 ID 或其他字段绕开唯一键。“隔离”只表示拒绝该冲突输入的正常业务写入，并为本次回放形成脱敏诊断；已存在 Inbox 行的 `source/source_msg_id/source_id/received_at/payload_hash/payload/status/processed_at/last_error` 全部保持不变。运行可以继续处理后续唯一键，但整体结果为失败。
- 载荷相同但唯一键不同不是重复消息；它们分别保留来源记录，后续业务去重不得偷偷改用载荷哈希。

### 5.4 T02 业务标识映射

回放模式下的来源会话和 T02 航迹标识固定映射如下：

```text
source_session_key = dataset_id
external_target_id = TrackUploadV3.targetId 的无符号十进制字符串
external_track_id = TrackUploadV3.targetId 的同一无符号十进制字符串
point_seq = 当前 NDJSON 的 record_no
```

`source_session_key` 原样使用已校验的 `dataset_id`，不拼接来源代码或帧 ID。`targetId` 按 `UINT32` 解释并输出 0 到 4294967295 的规范十进制形式，不补零；同一来源会话内，它同时作为 `target_source_link.external_target_id`，并在该 link 内作为 `track.external_track_id`。`point_seq` 使用产生该航迹点的 `record_no`，重放时不得重新编号。

同一 `TrackUploadV3` 消息内每个 `targetId` 最多出现一次。解析完整消息后必须先检查重复 ID，再执行任何 target、link、track 或 point 写入；出现重复时整个 Inbox 以 `MESSAGE_FIELD_INVALID` 置为 `FAILED`，不得部分写入。

`UPLOAD_TARGET_V3` 不提供稳定目标 ID，禁止使用数组下标、帧内顺序、坐标或哈希猜造 target、`target_source_link` 或 track。该消息只保留 Inbox 和解析事实。`LoginReply`、`Heartbeat`、`RtkUpload` 在缺少已确认的 source 到 device 唯一映射时同样只保留 Inbox 和解析事实，不得写设备成功状态；不得把来源已启用或消息解析成功等同于设备映射已确认。T02 的任何消息都不创建 `alarm`。

## 6 流式帧解析

流式帧解析器的输入分块不具有语义，必须满足以下状态机：

1. 缓冲区不足 4 字节时等待更多字节，并最多保留可能构成帧头的末尾 3 字节。
2. 在缓冲区中搜索 `55 aa 55 aa`。帧头前的字节逐字节丢弃并报告 `FRAME_LEADING_NOISE`，随后可继续寻找下一帧。
3. 至少取得 8 字节后读取大端 `frame_length`。非法长度不得触发对应大小的内存分配；丢弃当前候选帧头的第一个字节后重新同步。
4. 缓冲区未达到 `8 + frame_length` 时等待后续分块，不提前报告错误。
5. 取得完整候选帧后验证 CRC。CRC 错误报告一次 `FRAME_CRC_MISMATCH`，丢弃该候选帧并继续处理缓冲区中的后续字节。
6. CRC 通过后校验指令负载长度、计数、枚举和值域；成功时只发出一个完整帧，粘在其后的字节继续按同一状态机处理。
7. 输入结束时，空缓冲区是正常 EOF；仅剩无法构成帧头的噪声报告 `FRAME_LEADING_NOISE`，存在合法帧头但帧未完整则报告 `FRAME_TRUNCATED_AT_EOF`。

对严格回放记录，任何前导噪声、尾随字节或一行产生的帧数量不是 1 都会使该记录失败；通用状态机的恢复能力用于证明拆包、粘包和重新同步行为正确。

## 7 错误分类和写入边界

`inbox_message.status` 只允许 `RECEIVED`、`PROCESSING`、`DONE`、`FAILED`。新消息插入为 `RECEIVED`，取得处理租约后变为 `PROCESSING`，完整解析和允许的业务写入成功后才变为 `DONE`。`UnsupportedMessage` 和所有帧、CRC、负载或字段解析失败都必须变为 `FAILED`，并把下表对应的稳定错误码原样写入 `last_error`；不存在 `UNSUPPORTED` 或其他第五种状态。成功完成时 `last_error` 必须为空。

| 错误码 | 分类 | 触发条件 | 处理 |
| --- | --- | --- | --- |
| `REPLAY_FILE_UNREADABLE` | 文件 | 文件不存在、无权限或读取失败 | 终止运行，不调用 sink |
| `REPLAY_ENCODING_INVALID` | 文件 | 非 UTF-8、BOM 或无法可靠划分行 | 终止运行，不调用 sink |
| `REPLAY_LINE_INVALID` | 记录 | JSON 非对象、字段缺失、多余、类型或范围错误，或组合来源命名空间超过 128 | 隔离该行，继续处理后续行；未形成合法来源键时不建 Inbox |
| `REPLAY_DATASET_MISMATCH` | 记录 | 同一文件出现不同 `dataset_id` | 隔离该行，整体运行失败 |
| `FRAME_HEX_INVALID` | 记录 | `frame_hex` 为空、字符非法、长度为奇数或超过 33_554_448 个字符 | 在解码前隔离该行，不创建伪造载荷 |
| `REPLAY_RECORD_FRAME_COUNT_INVALID` | 记录 | 一行不是恰好一个完整帧 | 当前 Inbox 置为 `FAILED`，`last_error` 写本错误码 |
| `SOURCE_MESSAGE_CONFLICT` | 来源 | 同一来源唯一键对应不同哈希 | 不插入或更新 Inbox，只形成本次回放诊断 |
| `ADAPTER_ALREADY_RUNNING` | 生命周期 | 运行中或停止中重复启动 | 拒绝第二次启动 |
| `FRAME_LEADING_NOISE` | 帧边界 | 帧头前或 EOF 后只有无关字节 | 通用解析器可重同步；严格回放的当前 Inbox 置为 `FAILED` |
| `FRAME_LENGTH_INVALID` | 帧边界 | 长度小于 10、超过上限、溢出或与记录字节数不符 | 当前 Inbox 置为 `FAILED`，不分配大缓冲区 |
| `FRAME_TRUNCATED_AT_EOF` | 帧边界 | EOF 时已有帧头但候选帧不完整 | 当前 Inbox 置为 `FAILED` |
| `FRAME_CRC_MISMATCH` | 完整性 | 线上 CRC 与计算结果不同 | 当前 Inbox 置为 `FAILED` |
| `MESSAGE_PAYLOAD_LENGTH_INVALID` | 协议 | 固定负载长度错误或动态负载有剩余字节 | 当前 Inbox 置为 `FAILED` |
| `MESSAGE_COUNT_INVALID` | 协议 | 数量为负、溢出或与负载长度不一致 | 当前 Inbox 置为 `FAILED` |
| `MESSAGE_FIELD_INVALID` | 协议 | 固定值或受限枚举不合法 | 当前 Inbox 置为 `FAILED` |
| `UNSUPPORTED_MESSAGE` | 协议 | 指令不在五类解析消息中 | 返回 `UnsupportedMessage`，当前 Inbox 置为 `FAILED` |

NDJSON 信封尚未形成合法来源键或原始字节时，只能产生回放运行诊断，不能伪造 Inbox。形成合法 `InboundFrame` 后必须先创建或命中幂等 Inbox，再执行帧和消息解析；CRC、长度、计数、字段或不支持错误只把该 Inbox 更新为 `FAILED` 并把精确稳定错误码写入 `last_error`，禁止写目标、轨迹、设备成功状态。sink 抛出异常时停止本次运行并传播失败，不得跳过该记录后继续宣称回放成功。

## 8 RTK 坐标 高度和时间边界

### 8.1 RTK 和坐标

- T02 上报的 X、Y、Z 是以雷达为原点的局部三维坐标。原始整数、缩放后的局部值以及 `northReference` 必须保留可追溯性。
- 单个 `RtkUpload` 只是候选观测，不自动成为坐标原点。设备文档建议在雷达静止时对约 20 帧取平均值；阶段 1 只有在外部明确确认雷达静止、至少 20 个连续有效样本完成聚合，并显式批准该原点后，才把它视为可用 RTK 原点。
- 有效候选样本至少要求纬度位于 `[-90, 90]`、经度位于 `[-180, 180]`、航向位于 `[0, 360)`、卫星数大于 0。范围合法不等于已经批准为原点。
- 缺少已批准的纬度、经度或航向，或者航迹声明相对转台零位但缺少相应姿态依据时，不生成 WGS-84 点。禁止用 `(0, 0)`、默认航向或最近一次不明来源的 RTK 值补齐。
- 坐标转换失败或依据不足时，原始 XYZ 和原始 RTK 字段仍保留在 Inbox，派生经纬度保持未知。

### 8.2 高度

- 点迹和航迹的 `zRaw * 0.01` 只表示相对雷达原点的高度，不能直接写入 AGL 或 AMSL。
- `RtkUpload.altitudeRaw` 当前无效，不能用作雷达安装高程。
- 只有雷达安装高度、垂直基准、地面高程来源及转换关系均得到明确确认后，才能计算标准高度。在此之前，AGL 和 AMSL 都保持未知，不写 0。

### 8.3 时间和迟到帧

| 值 | 单位和用途 |
| --- | --- |
| `InboundFrame.receivedAt` | NDJSON 提供的 epoch 毫秒，只表示平台接收时间 |
| `TargetUploadV3.utcMicros` | 协议微秒；值域验证通过后以向下整除 1000 转为事件 epoch 毫秒 |
| `TrackUploadV3.uploadTimestampMs` | 协议毫秒；值域验证通过后作为航迹事件时间 |
| `TrackUploadV3.utcMicros` | 原始保留，用于审计和交叉核对，不覆盖有效 `uploadTimestampMs` |
| `RtkUpload` | 协议负载没有事件时间；事件时间保持未知，不能拿 `receivedAt` 冒充 |

协议时间为 0、负数、溢出或无法解释为 epoch 时间时，原始值保留、派生事件时间保持未知。`receivedAt` 永远不覆盖协议事件时间。

迟到帧可以补写历史，但只有事件时间严格晚于现有最新状态时才可更新最新状态。事件时间相等时首次接受的来源消息保持为最新，后到消息只进入历史；事件时间未知的消息不得更新最新状态。`record_no` 和两个帧 ID 仅用于顺序诊断，不替代事件时间。

## 9 合成测试向量

测试不得复制设备文档示例报文或现场抓包。所有帧由测试代码按以下生成器程序化构造：

```text
body(command, frameId, payload) = be32(command) || be32(frameId) || payload
frameLength = len(body) + 2
remainder = crc16Modbus(body, init=0xFFFF, reflectedPoly=0xA001)
crcWire = byte(remainder & 0xFF) || byte((remainder >>> 8) & 0xFF)
frame = 55aa55aa || be32(frameLength) || body || crcWire
```

`be16`、`be32`、`be64` 和对应有符号写入器都必须检查范围。动态消息先构造目标项，再由项数计算计数和长度，不能在测试中手写彼此不一致的成功帧。

| 编号 | 程序化构造 | 输入方式 | 预期结果 |
| --- | --- | --- | --- |
| V01 完整帧 | `frame(HEARTBEAT, 1, be64(0))` | 一次输入全部字节 | 只产生一个 `Heartbeat`；规范十六进制为 `55aa55aa0000001200000001000000010000000000000000b1e1` |
| V02 逐字节拆包 | 复用 V01 | 从第 1 字节起逐字节送入流式解析器 | 前 25 次不产生消息，最后 1 字节到达后恰好产生一个 `Heartbeat` |
| V03 双帧粘包 | V01 加 `frame(LOGIN, 2, be32(5) + be16(0))` | 单次输入拼接字节 | 按顺序产生 `Heartbeat`、`LoginReply`；第二帧规范十六进制为 `55aa55aa0000001000000002000000020000000500009378` |
| V04 CRC 错误 | 复制 V01，翻转保留负载中的一位但保留原 CRC | 单次输入 | `FRAME_CRC_MISMATCH`，无解析消息和业务写入 |
| V05 非法长度 | 复制 V01，把长度改为 9；另测长度改为 `MAX_FRAME_LENGTH + 1` | 单次输入 | 两次均为 `FRAME_LENGTH_INVALID`，不按声明长度分配内存 |
| V06 未知指令 | `frame(0x7F000001, 4, empty)` | 单次输入 | 返回一个 `UnsupportedMessage`；规范十六进制为 `55aa55aa0000000a7f000001000000043b6c` |
| V07 RTK 缺失 | 构造一个含单目标的 `UPLOAD_TARGET_V3`，局部坐标取 1、2、3 米，测试上下文不提供已批准 RTK 原点 | 完整帧 | `TargetUploadV3` 解析成功并保留 XYZ，不生成经纬度或高度，不写告警 |
| V08 重复记录 | 同一 NDJSON 记录完整运行两次 | 两次回放 | 第二次命中相同唯一键和哈希，不重复产生任何业务影响，也不更新首次 `receivedAt` |
| V09 相同键不同载荷 | 保持 `dataset_id` 和 `record_no`，第二次换成另一个 CRC 正确的心跳帧 | 两次回放 | `SOURCE_MESSAGE_CONFLICT`；首条 Inbox 和载荷不变，第二条隔离 |
| V10 前导噪声 | `00 ff` 加 V01 | 流式解析器单元测试 | 报告 `FRAME_LEADING_NOISE` 后恢复并产生 V01；若作为一条回放记录则以严格一行一帧规则失败 |
| V11 截断 EOF | 删除 V01 的最后 1 字节 | 输入后调用结束 | `FRAME_TRUNCATED_AT_EOF`，无解析消息和业务写入 |
| V12 CRC 旁路禁用 | 把 V01 的 CRC 两字节替换为 `cc cc` | 完整帧 | 计算值不匹配，因此为 `FRAME_CRC_MISMATCH` |
| V13 有效 RTK 上报 | `frame(UPLOAD_RTK, 13, be64(latRaw) + be64(lonRaw) + be64(headingRaw) + be32(satellites) + be32(0))`，使用纯合成合法范围值 | 完整帧 | 产生 `RtkUpload`，保留无效的 `altitudeRaw`，单帧不自动批准原点 |
| V14 已知范围外请求回复 | `frame(REQUEST_RTK, 14, be16(0))` | 完整帧 | 返回 `UnsupportedMessage`，不推进设备状态 |
| V15 Inbox 状态收口 | 复用 V06 | 经过完整 Inbox 处理 | 状态依次为 `RECEIVED`、`PROCESSING`、`FAILED`，`last_error` 精确为 `UNSUPPORTED_MESSAGE`，不出现第五种状态 |
| V16 来源命名空间超限 | 分别构造合法格式的 `source_code` 和 `dataset_id`，使组合命名空间达到 129 个 ASCII 字符 | 读取 NDJSON | `REPLAY_LINE_INVALID`，不创建 Inbox；128 个字符的边界值可继续处理 |
| V17 回放字段上限 | 分别令 `received_at` 超过 `Long.MAX_VALUE`、令 `frame_hex` 达到 33_554_450 个字符 | 读取 NDJSON | 前者为 `REPLAY_LINE_INVALID`，后者在解码前为 `FRAME_HEX_INVALID`，两者均不创建 Inbox |
| V18 规范原始消息封装 | 使用大小写混合但 CRC 正确的 V01 十六进制和合法来源配置 | 完整回放 | Inbox 六列按 5.2 节映射；JSONB 只有四个键，`frame_hex` 为 V01 的小写规范值，并可还原相同原始字节 |
| V19 航迹标识和重复 ID | 构造含两个相同 `targetId` 的 `UPLOAD_TRACK_V3` | 完整帧 | Inbox 置为 `FAILED`，`last_error` 为 `MESSAGE_FIELD_INVALID`，不写 target、link、track 或 point；两个不同 ID 时各自使用 `dataset_id`、无符号 ID 字符串和 `record_no` 映射 |
| V20 无稳定映射 | 分别构造有效 `UPLOAD_TARGET_V3`，以及没有 source 到 device 映射的有效 `HEARTBEAT` | 完整回放 | 两者 Inbox 可完成解析；前者不写 target/link/track，后者不写设备成功状态，均不写 `alarm` |

每个成功向量还必须断言 `frame_length`、线上 CRC 顺序、外层 `protocol_frame_id` 和完整字节消费；每个失败向量必须断言 Inbox 或运行诊断的精确错误码，并断言目标、轨迹、设备成功状态和告警写入次数均为 0。

## 10 阶段 2 入口条件

后续编码只能实现本契约已经固定的回放适配、帧解析和五类消息解析。若设备现场行为与本契约在 CRC 线序、时间含义、坐标轴或字段长度上冲突，必须先用脱敏、合成化证据修订本契约并通过阶段审查，不能在实现中增加静默兼容分支。
