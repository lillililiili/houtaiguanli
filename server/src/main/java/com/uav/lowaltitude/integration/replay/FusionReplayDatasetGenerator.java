package com.uav.lowaltitude.integration.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.fusion.domain.AlphaBetaFilter;

/**
 * 合成回放数据集（契约六场景）。固定随机种子与固定 T0：同一数据集每次生成的记录逐字节相同，回放回归才有意义。
 * 生成的是"来源观测"，不是结论：三路来源各按自己的 Demo 精度加噪，缺字段就不给（EO 无身份线索、TDOA 无类别），
 * 绝不为了让融合好看而补默认值。
 */
@Component
public class FusionReplayDatasetGenerator {
    public static final String DATASET_ID = "stage8-fusion-demo";
    /** 阶段 8.5 数据集：同样的六个场景，但报文换成凌云协议 A/C 的真实形状，另加四个直连专有场景。 */
    public static final String DATASET_ID_V2 = "stage85-lingyun-demo";
    public static final long T0_MILLIS = 1_757_073_600_000L; // 2026-09-05T12:00:00Z
    public static final long SEED = 20260905L;
    public static final long FRAME_INTERVAL_MS = 1000L;
    public static final String RADAR = "replay-radar-a", TDOA = "replay-tdoa-a", EO = "replay-eo-a";
    /** 六场景（契约 §回放数据集）。 */
    public static final List<String> SCENARIOS = List.of("three-source", "single-missing", "crossing", "split-merge", "late-out-of-order", "accuracy-gap");
    /** 直连专有场景：飞手位置、只有方位的 AOA、只在跟踪期间可见的光电、识别中的目标。 */
    public static final List<String> SCENARIOS_V2 = List.of("tdoa-pilot", "aoa-bearing", "eo-tracking", "identifying-255");
    public static final String AOA = "replay-aoa-a";
    /**
     * 阶段 16 的自动合并/分裂演示（决策 16-7）：**自己的 dataset_id、自己的来源标识**。
     *
     * 不能加进 `stage85-lingyun-demo`：那个 id 已经灌进所有开发库了，同一 id 下改内容会让
     * `alreadyLoadedV2()`（按条数判断）失效——旧库 180 条 < 新的 204 条，守卫不触发、重灌撞哈希，
     * 应用被拦在启动阶段。**dataset_id 是不可变内容，改内容必须换 id。**
     *
     * 来源标识也必须换：inbox 的键是 (source, record_no)。沿用 `lingyun:radar:S85R1` 而让 record_no
     * 从 1 起，两个数据集会写出同一个 `lingyun:radar:S85R1#1` 而载荷不同——那连全新库都装不上。
     */
    public static final String DATASET_ID_S16 = "stage16-fusion-merge-demo";
    public static final String RADAR_S16 = "replay-radar-s16";
    public static final List<String> SCENARIOS_S16 = List.of("converge-merge", "near-echo");
    /** 各回放来源在直连报文里的 inbox source（契约 v1.1 §2）。 */
    public static final Map<String, String> INBOX_SOURCES = Map.of(
            RADAR, "lingyun:radar:S85R1", TDOA, "lingyun:tdoa:S85T1", AOA, "lingyun:aoa:S85A1", EO, "eo-edge:S85E1",
            RADAR_S16, "lingyun:radar:S16R1");

    private static final double LON0 = 118.62, LAT0 = 37.42;
    private static final double RADAR_ACC = 15, TDOA_ACC = 60, EO_ACC = 25;
    private static final int FRAMES = 12;

    /** 一条回放记录：信封在 {@link FusionReplayRunner} 里组装，这里只给 frame 与真值。 */
    public record Record(long recordNo, String sourceCode, long observedAtMillis, long receivedAtMillis, List<Map<String, Object>> items, String scenario) { }
    public record GroundTruth(String scenario, long recordNo, String trueTargetKey, String sourceCode, String externalTargetId, long observedAtMillis) { }
    public record Dataset(String datasetId, List<Record> records, List<GroundTruth> groundTruth) { }

    public Dataset generate() {
        Random random = new Random(SEED);
        List<Record> records = new ArrayList<>();
        List<GroundTruth> truth = new ArrayList<>();
        long recordNo = 0;
        for (String scenario : SCENARIOS) recordNo = scenario(scenario, recordNo, random, records, truth);
        return new Dataset(DATASET_ID, List.copyOf(records), List.copyOf(truth));
    }

    private long scenario(String scenario, long startRecordNo, Random random, List<Record> records, List<GroundTruth> truth) {
        long recordNo = startRecordNo;
        // 阶段 16 的场景排在 v1 之后，时间与经度都不与它们重叠。
        int index = SCENARIOS.contains(scenario) ? SCENARIOS.indexOf(scenario)
                : SCENARIOS.size() + SCENARIOS_S16.indexOf(scenario);
        long base = T0_MILLIS + index * 600_000L;
        double lonBase = LON0 + index * 0.05;
        for (int frame = 0; frame < FRAMES; frame++) {
            long observedAt = base + frame * FRAME_INTERVAL_MS;
            switch (scenario) {
                case "three-source" -> {
                    double[] p = along(lonBase, LAT0, frame, 10, 0);
                    recordNo = emitAll(scenario, recordNo, observedAt, p, random, records, truth, "T1", true, true, true, RADAR_ACC, TDOA_ACC, EO_ACC);
                }
                case "single-missing" -> {
                    // TDOA 在第 4–9 帧空窗（20 s 量级的缺失），雷达与光电继续；单源缺失不得让目标换 ID。
                    double[] p = along(lonBase, LAT0, frame, 8, 0.3);
                    boolean tdoa = frame < 4 || frame > 9;
                    recordNo = emitAll(scenario, recordNo, observedAt, p, random, records, truth, "T1", true, tdoa, true, RADAR_ACC, TDOA_ACC, EO_ACC);
                }
                case "crossing" -> {
                    // 两目标相向而行，最小间距约 40 m：交叉不得换 ID。
                    double[] a = along(lonBase, LAT0, frame, 12, 0);
                    double[] b = along(lonBase + 0.0012, LAT0 + 0.00036, FRAMES - 1 - frame, 12, 0);
                    List<Map<String, Object>> radarItems = List.of(item("R-A", a, RADAR_ACC, random, "UAV", null, null),
                            item("R-B", b, RADAR_ACC, random, "UAV", null, null));
                    records.add(new Record(recordNo++, RADAR, observedAt, observedAt, radarItems, scenario));
                    truth.add(new GroundTruth(scenario, recordNo - 1, scenario + ":TA", RADAR, "R-A", observedAt));
                    truth.add(new GroundTruth(scenario, recordNo - 1, scenario + ":TB", RADAR, "R-B", observedAt));
                }
                case "split-merge" -> {
                    // 前 6 帧一个回波，之后分成相距 ≥ split_min_separation_m 的两个。
                    List<Map<String, Object>> items = new ArrayList<>();
                    double[] a = along(lonBase, LAT0, frame, 10, 0);
                    items.add(item("R-M1", a, RADAR_ACC, random, "UAV", null, null));
                    if (frame >= 6) {
                        double[] b = AlphaBetaFilter.fromEnu(a[0], a[1], 150.0, 0);
                        items.add(item("R-M2", b, RADAR_ACC, random, "UAV", null, null));
                        truth.add(new GroundTruth(scenario, recordNo, scenario + ":TB", RADAR, "R-M2", observedAt));
                    }
                    truth.add(new GroundTruth(scenario, recordNo, scenario + ":TA", RADAR, "R-M1", observedAt));
                    records.add(new Record(recordNo++, RADAR, observedAt, observedAt, List.copyOf(items), scenario));
                }
                case "converge-merge" -> {
                    // 决策 16-1：同一架被同一部雷达报成两个回波（外部目标号不同），于是落成两个目标——
                    // 这正是"重复目标"现在的样子。两条各自稳定后一直落在 2σ 内，连续 ≥ merge_min_frames 帧，
                    // 由管线自动合并。真值把两条标成同一实体。
                    //
                    // 两点取同一真值位置、各自带独立噪声：门限是 2σ 而 σ 会随滤波收敛到 ~10 m，
                    // 一开始就隔开几百米再"跳"到近处是不行的——α-β 要好几帧才追得上，追上时门限已经收窄了。
                    double[] a = along(lonBase, LAT0, frame, 10, 0);
                    double[] b = a;
                    records.add(new Record(recordNo++, RADAR_S16, observedAt, observedAt,
                            List.of(item("R-CV1", a, RADAR_ACC, random, "UAV", null, null),
                                    item("R-CV2", b, RADAR_ACC, random, "UAV", null, null)), scenario));
                    truth.add(new GroundTruth(scenario, recordNo - 1, scenario + ":TA", RADAR_S16, "R-CV1", observedAt));
                    truth.add(new GroundTruth(scenario, recordNo - 1, scenario + ":TA", RADAR_S16, "R-CV2", observedAt));
                }
                case "near-echo" -> {
                    // 决策 16-2 的反面：同源第二回波从第 2 帧起出现，但一直只隔 40 m——够不上
                    // split_min_separation_m(100)，所以**不该**被判成分裂。计到 pending_expire_frames 后
                    // 这条 ONE_TO_MANY 应当以 EXPIRED 收场，而不是一直占着位置或悄悄分裂。
                    double[] a = along(lonBase, LAT0, frame, 10, 0);
                    List<Map<String, Object>> items = new ArrayList<>();
                    items.add(item("R-NE1", a, RADAR_ACC, random, "UAV", null, null));
                    if (frame >= 2) {
                        items.add(item("R-NE2", AlphaBetaFilter.fromEnu(a[0], a[1], 0, 40.0), RADAR_ACC, random, "UAV", null, null));
                        truth.add(new GroundTruth(scenario, recordNo, scenario + ":TB", RADAR_S16, "R-NE2", observedAt));
                    }
                    truth.add(new GroundTruth(scenario, recordNo, scenario + ":TA", RADAR_S16, "R-NE1", observedAt));
                    records.add(new Record(recordNo++, RADAR_S16, observedAt, observedAt, List.copyOf(items), scenario));
                }
                case "late-out-of-order" -> {
                    // EO 晚 2.5 s 到达且 record_no 倒序：迟到帧只补原始层，不得回退融合结果。
                    double[] p = along(lonBase, LAT0, frame, 9, 0);
                    records.add(new Record(recordNo++, RADAR, observedAt, observedAt, List.of(item("R-L", p, RADAR_ACC, random, "UAV", null, null)), scenario));
                    truth.add(new GroundTruth(scenario, recordNo - 1, scenario + ":TA", RADAR, "R-L", observedAt));
                    if (frame % 2 == 1) {
                        long lateObserved = observedAt - 1000;
                        double[] lp = along(lonBase, LAT0, frame - 1, 9, 0);
                        records.add(new Record(recordNo++, EO, lateObserved, observedAt + 2500, List.of(item("E-L", lp, EO_ACC, random, "UAV", 0.86, null)), scenario));
                        truth.add(new GroundTruth(scenario, recordNo - 1, scenario + ":TA", EO, "E-L", lateObserved));
                    }
                }
                case "accuracy-gap" -> {
                    // TDOA ±50 m 偏差、精度 60 m；融合位置应明显偏向雷达。
                    double[] p = along(lonBase, LAT0, frame, 7, 0);
                    records.add(new Record(recordNo++, RADAR, observedAt, observedAt, List.of(item("R-G", p, RADAR_ACC, random, "UAV", null, null)), scenario));
                    truth.add(new GroundTruth(scenario, recordNo - 1, scenario + ":TA", RADAR, "R-G", observedAt));
                    double[] biased = AlphaBetaFilter.fromEnu(p[0], p[1], 50.0, 0);
                    records.add(new Record(recordNo++, TDOA, observedAt, observedAt, List.of(item("D-G", biased, TDOA_ACC, random, null, null, "RF-0007")), scenario));
                    truth.add(new GroundTruth(scenario, recordNo - 1, scenario + ":TA", TDOA, "D-G", observedAt));
                }
                default -> throw new IllegalStateException("未知回放场景: " + scenario);
            }
        }
        return recordNo;
    }

    private long emitAll(String scenario, long recordNo, long observedAt, double[] p, Random random, List<Record> records, List<GroundTruth> truth,
            String key, boolean radar, boolean tdoa, boolean eo, double radarAcc, double tdoaAcc, double eoAcc) {
        long next = recordNo;
        if (radar) {
            records.add(new Record(next++, RADAR, observedAt, observedAt, List.of(item("R-" + key, p, radarAcc, random, "UAV", null, null)), scenario));
            truth.add(new GroundTruth(scenario, next - 1, scenario + ":" + key, RADAR, "R-" + key, observedAt));
        }
        if (tdoa) {
            records.add(new Record(next++, TDOA, observedAt, observedAt, List.of(item("D-" + key, p, tdoaAcc, random, null, null, "RF-0042")), scenario));
            truth.add(new GroundTruth(scenario, next - 1, scenario + ":" + key, TDOA, "D-" + key, observedAt));
        }
        if (eo) {
            records.add(new Record(next++, EO, observedAt, observedAt, List.of(item("E-" + key, p, eoAcc, random, "UAV", 0.91, null)), scenario));
            truth.add(new GroundTruth(scenario, next - 1, scenario + ":" + key, EO, "E-" + key, observedAt));
        }
        return next;
    }

    /** 以 (lon0, lat0) 为起点、向东 speed m/s、向北 northPerFrame m/frame 的匀速航迹。 */
    private static double[] along(double lon0, double lat0, int frame, double speedMps, double northPerFrame) {
        return AlphaBetaFilter.fromEnu(lon0, lat0, speedMps * frame, northPerFrame * frame);
    }

    private static Map<String, Object> item(String externalTargetId, double[] position, double accuracy, Random random, String classCode,
            Double classConfidence, String identityClue) {
        // 噪声幅度与该源精度成比例：精度差的源本来就该给出更散的点。
        double[] noisy = AlphaBetaFilter.fromEnu(position[0], position[1], random.nextGaussian() * accuracy / 3, random.nextGaussian() * accuracy / 3);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("external_target_id", externalTargetId);
        item.put("lon", round(noisy[0]));
        item.put("lat", round(noisy[1]));
        item.put("position_accuracy_m", accuracy);
        if (classCode != null) item.put("class_code", classCode);
        if (classConfidence != null) item.put("class_confidence", classConfidence);
        if (identityClue != null) item.put("identity_clue", identityClue);
        return item;
    }

    // ---------- 阶段 8.5：数据集 v2（凌云协议 A / C 报文） ----------

    /** 一条直连回放记录：payload 就是设备原样上报的报文，inbox 的 source 决定它交给哪个映射器。 */
    public record ProtocolRecord(long recordNo, String sourceCode, String inboxSource, long observedAtMillis, long receivedAtMillis,
            Map<String, Object> payload, String scenario) { }
    public record ProtocolDataset(String datasetId, List<ProtocolRecord> records, List<GroundTruth> groundTruth) { }

    /**
     * v2 = 把 v1 的六个场景逐条翻译成协议报文 + 四个直连专有场景。
     * 翻译而不是重新生成，是为了让原六场景的坐标与顺序逐字不变——只有这样，"换了报文格式但结论不变"才是可验证的。
     * 协议里没有精度字段，去掉后由 fusion_config 的缺省精度补上（RADAR 15 / TDOA 60 / EO 25，与 v1 显式值一致）。
     */
    public ProtocolDataset generateV2() {
        Dataset v1 = generate();
        List<ProtocolRecord> records = new ArrayList<>();
        for (Record record : v1.records()) records.add(translate(record));
        List<GroundTruth> truth = new ArrayList<>(v1.groundTruth());
        long recordNo = records.isEmpty() ? 0 : records.get(records.size() - 1).recordNo() + 1;
        recordNo = directAccessScenarios(recordNo, records, truth);
        return new ProtocolDataset(DATASET_ID_V2, List.copyOf(records), List.copyOf(truth));
    }

    /** 阶段 16 的合并/分裂演示数据集（决策 16-7）：与 stage85 完全分开，各灌各的。 */
    public ProtocolDataset generateStage16() {
        Random random = new Random(SEED + 16);
        List<Record> records = new ArrayList<>();
        List<GroundTruth> truth = new ArrayList<>();
        long recordNo = 0;
        for (String scenario : SCENARIOS_S16) recordNo = scenario(scenario, recordNo, random, records, truth);
        List<ProtocolRecord> protocol = new ArrayList<>();
        for (Record record : records) protocol.add(translate(record));
        return new ProtocolDataset(DATASET_ID_S16, List.copyOf(protocol), List.copyOf(truth));
    }

    private ProtocolRecord translate(Record record) {
        if (EO.equals(record.sourceCode())) {
            // 光电在协议 A 里既没有位置也没有类别：它的观测只能来自协议 C 的跟踪上报。
            // 协议 C 一条上报只跟一个目标，因此光电记录在 v1 里本来就只有一个 item。
            Map<String, Object> item = record.items().get(0);
            Map<String, Object> aiStatus = aiStatus(className(item.get("class_code")), number(item.get("lon")), number(item.get("lat")),
                    number(item.get("class_confidence")));
            return new ProtocolRecord(record.recordNo(), record.sourceCode(), INBOX_SOURCES.get(EO), record.observedAtMillis(),
                    record.receivedAtMillis(), beginTracking(String.valueOf(item.get("external_target_id")), record.observedAtMillis(), aiStatus),
                    record.scenario());
        }
        // 一条 SenseData 可以带多个目标（交叉、分裂场景就是同一帧两个回波）：逐个翻译，一个都不能少。
        List<Map<String, Object>> objects = new ArrayList<>();
        for (Map<String, Object> item : record.items()) {
            Map<String, Object> extension = new LinkedHashMap<>();
            putObjectType(extension, item.get("class_code"));
            putIfPresent(extension, "probability", item.get("class_confidence"));
            putIfPresent(extension, "uavSN", item.get("identity_clue"));
            objects.add(senseObject(String.valueOf(item.get("external_target_id")), record.observedAtMillis(),
                    number(item.get("lon")), number(item.get("lat")), extension));
        }
        return new ProtocolRecord(record.recordNo(), record.sourceCode(), INBOX_SOURCES.get(record.sourceCode()), record.observedAtMillis(),
                record.receivedAtMillis(), senseData(record.sourceCode(), record.recordNo(), record.receivedAtMillis(), List.copyOf(objects)), record.scenario());
    }

    private long directAccessScenarios(long startRecordNo, List<ProtocolRecord> records, List<GroundTruth> truth) {
        Random random = new Random(SEED + 1);
        long recordNo = startRecordNo;
        long base = T0_MILLIS + (SCENARIOS.size() + 1L) * 600_000L;
        double lonBase = LON0 + SCENARIOS.size() * 0.05;

        for (int frame = 0; frame < FRAMES; frame++) {
            long observedAt = base + frame * FRAME_INTERVAL_MS;
            double[] p = along(lonBase, LAT0, frame, 10, 0);
            double[] noisy = noisy(p, TDOA_ACC, random);

            // ① TDOA 带飞手位置：C02-6 超视距要拿目标与飞手两点算距离，飞手点必须真的落进 pilot_location。
            Map<String, Object> pilotExtension = new LinkedHashMap<>();
            pilotExtension.put("uavSN", "SN-PILOT-01");
            pilotExtension.put("pilotLon", round(lonBase));
            pilotExtension.put("pilotLat", round(LAT0));
            putObjectType(pilotExtension, "UAV");
            records.add(new ProtocolRecord(recordNo++, TDOA, INBOX_SOURCES.get(TDOA), observedAt, observedAt,
                    senseData(TDOA, recordNo, observedAt, List.of(senseObject("D-PILOT", observedAt, noisy[0], noisy[1], pilotExtension))), "tdoa-pilot"));
            truth.add(new GroundTruth("tdoa-pilot", recordNo - 1, "tdoa-pilot:TA", TDOA, "D-PILOT", observedAt));

            // ② AOA 只有方位：协议明说经纬度无效，报文里照样带着，映射时必须丢掉——留着就是假位置。
            Map<String, Object> aoaExtension = new LinkedHashMap<>();
            aoaExtension.put("direction", round((frame * 7.5) % 360));
            aoaExtension.put("uavSN", "SN-PILOT-01");
            records.add(new ProtocolRecord(recordNo++, AOA, INBOX_SOURCES.get(AOA), observedAt, observedAt,
                    senseData(AOA, recordNo, observedAt, List.of(senseObject("A-BEARING", observedAt, round(noisy[0]), round(noisy[1]), aoaExtension))), "aoa-bearing"));
            truth.add(new GroundTruth("aoa-bearing", recordNo - 1, "aoa-bearing:TA", AOA, "A-BEARING", observedAt));

            // ③ 光电只在第 4–8 帧跟踪：前后是心跳，不产生任何观测。
            boolean tracking = frame >= 4 && frame <= 8;
            Map<String, Object> payload = tracking
                    ? beginTracking("T-EO-TRACK", observedAt, aiStatus("drone", round(noisy[0]), round(noisy[1]), 0.9))
                    : heartBeat(observedAt);
            records.add(new ProtocolRecord(recordNo++, EO, INBOX_SOURCES.get(EO), observedAt, observedAt, payload, "eo-tracking"));
            if (tracking) truth.add(new GroundTruth("eo-tracking", recordNo - 1, "eo-tracking:TA", EO, "T-EO-TRACK", observedAt));

            // ④ 识别中（255）：类别必须为空，页面才不会把"还没认出来"显示成一个确定的类型。
            Map<String, Object> identifyingExtension = new LinkedHashMap<>();
            identifyingExtension.put("objectType", 255);
            double[] other = noisy(along(lonBase + 0.02, LAT0, frame, 6, 0), RADAR_ACC, random);
            records.add(new ProtocolRecord(recordNo++, RADAR, INBOX_SOURCES.get(RADAR), observedAt, observedAt,
                    senseData(RADAR, recordNo, observedAt, List.of(senseObject("R-IDENT", observedAt, other[0], other[1], identifyingExtension))), "identifying-255"));
            truth.add(new GroundTruth("identifying-255", recordNo - 1, "identifying-255:TA", RADAR, "R-IDENT", observedAt));
        }
        return recordNo;
    }

    /**
     * 协议 A 报文里的 {@code deviceId} 必须与主题末段一致——{@code LingyunEnvelope} 的身份校验按这条拒收
     * （payload.deviceId != 主题末段 → IDENTITY_MISMATCH）。主题末段由 {@code LingyunMqttReplayExporter}
     * 从 {@link #INBOX_SOURCES} 的最后一段拼出，所以这里从同一处取值：两边各写一份迟早会漂。
     */
    static String deviceIdOf(String sourceCode) {
        String source = INBOX_SOURCES.get(sourceCode);
        if (source == null) throw new IllegalArgumentException("未登记的回放来源: " + sourceCode);
        return source.substring(source.lastIndexOf(':') + 1);
    }

    private static Map<String, Object> senseData(String sourceCode, long msgCnt, long ptTime, List<Map<String, Object>> objects) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceId", deviceIdOf(sourceCode));
        payload.put("msgCnt", msgCnt);
        payload.put("ptTime", ptTime);
        payload.put("objects", objects);
        return payload;
    }

    private static Map<String, Object> senseObject(String objectId, long time, Double longitude, Double latitude, Map<String, Object> extension) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("objectId", objectId);
        object.put("time", time);
        putIfPresent(object, "longitude", longitude);
        putIfPresent(object, "latitude", latitude);
        object.put("extension", extension);
        return object;
    }

    private static Map<String, Object> beginTracking(String taskId, long timestamp, Map<String, Object> aiStatus) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", taskId);
        metadata.put("deviceId", "S85E1D1");
        // 协议 C 的 BeginTracking/EndTracking 必带 codeStatus，缺了会被 EoEdgeEnvelope 判 INVALID_ENVELOPE；
        // 200 表示成功（EoEdgeIngressService 按 !=200 记失败），其余取值待厂家给完整码表后再补。
        metadata.put("codeStatus", 200);
        metadata.put("workState", 1);
        metadata.put("aiStatus", aiStatus);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "BeginTracking");
        payload.put("edgeId", "S85E1");
        payload.put("timestamp", timestamp);
        payload.put("metadata", metadata);
        return payload;
    }

    private static Map<String, Object> heartBeat(long timestamp) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        // 心跳同样要过 EoEdgeEnvelope：deviceId 对所有事件必填，HeartBeat 另外要求 codeStatus、workState
        // 和 cameraStatus 三者齐备（缺任一判 INVALID_ENVELOPE）。cameraStatus 平台侧只原样存档，
        // 不解析字段，因此这里给一个最小对象；真实字段表待厂家提供。
        metadata.put("deviceId", "S85E1D1");
        metadata.put("codeStatus", 200);
        metadata.put("workState", 1);
        metadata.put("cameraStatus", Map.of("zoom", 1, "focus", "AUTO"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "HeartBeat");
        payload.put("edgeId", "S85E1");
        payload.put("timestamp", timestamp);
        payload.put("metadata", metadata);
        return payload;
    }

    private static Map<String, Object> aiStatus(String className, Double longitude, Double latitude, Double detectConfidence) {
        Map<String, Object> aiStatus = new LinkedHashMap<>();
        putIfPresent(aiStatus, "className", className);
        putIfPresent(aiStatus, "longitude", longitude);
        putIfPresent(aiStatus, "latitude", latitude);
        putIfPresent(aiStatus, "detectConfidence", detectConfidence);
        return aiStatus;
    }

    /** 我们的类别码 → 协议 A 的 objectType 数字；协议里没有的类别就不写这个字段。 */
    private static void putObjectType(Map<String, Object> extension, Object classCode) {
        if ("UAV".equals(classCode)) extension.put("objectType", 30);
        else if ("BIRD".equals(classCode)) extension.put("objectType", 40);
    }

    /** 我们的类别码 → 协议 C 的 className；协议目前只承诺 drone / bird。 */
    private static String className(Object classCode) {
        if ("UAV".equals(classCode)) return "drone";
        if ("BIRD".equals(classCode)) return "bird";
        return null;
    }

    private static double[] noisy(double[] position, double accuracy, Random random) {
        double[] point = AlphaBetaFilter.fromEnu(position[0], position[1], random.nextGaussian() * accuracy / 3, random.nextGaussian() * accuracy / 3);
        return new double[]{round(point[0]), round(point[1])};
    }

    private static Double number(Object value) { return value == null ? null : ((Number) value).doubleValue(); }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static double round(double value) { return Math.round(value * 1e7) / 1e7; }
}
