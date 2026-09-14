package com.uav.lowaltitude.integration.replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.replay.FusionReplayDatasetGenerator.ProtocolDataset;
import com.uav.lowaltitude.integration.replay.FusionReplayDatasetGenerator.ProtocolRecord;
import com.uav.lowaltitude.modules.fusion.ingest.EoTrackingReportMapper;
import com.uav.lowaltitude.modules.fusion.ingest.LingyunSenseDataMapper;

/**
 * 把 v2 直连数据集导出成协作者 A 能直接用 MQTT 客户端逐行发布的 NDJSON（契约 v1.2 §8）。
 *
 * 每行一条：{@code {topic, qos, payload, record_no, received_at, source}}。
 * <ul>
 *   <li><b>payload 是字符串，不是内嵌对象。</b>它必须与回放种子写进 inbox 的原文**逐字一致**——
 *       A 那边发出来的报文要能在我们这边复现出同一批观测，差一个空格哈希就对不上。
 *       内嵌成对象的话，任何一方重新序列化都可能改变键序与空白。发布时把这个字符串原样当作 MQTT 载荷即可，
 *       不要再做一次 JSON 序列化。</li>
 *   <li><b>规范化复用 {@link FusionReplayRunner#payloadJson}</b>，不在这里重写一遍：两处各写一遍序列化早晚会漂。</li>
 *   <li>发布顺序即 {@code record_no} 顺序。同毫秒并列的多条（同一帧多路）没有事实上的先后，
 *       按 record_no 发布只是为了可复现。</li>
 * </ul>
 * 只在 local profile 且显式开开关时运行：它写的是仓库里的文档产物，不该在任何正常启动路径上被触发。
 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "app.replay.export", name = "enabled", havingValue = "true")
public class LingyunMqttReplayExporter implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LingyunMqttReplayExporter.class);
    /** 协议 A 主题里的厂商编码（决策 10-4 定为 dongying）。 */
    public static final String PROVIDER_CODE = "dongying";
    /** 至少一次投递：设备侧上报按至少一次语义，去重由 inbox 的 (source, source_msg_id) 唯一键兜底。 */
    public static final int QOS = 1;
    public static final String DEFAULT_TARGET = "docs/直连接入计划/stage85-lingyun-demo.mqtt.ndjson";

    private final FusionReplayDatasetGenerator generator;
    private final FusionReplayRunner runner;
    private final ObjectMapper json;

    public LingyunMqttReplayExporter(FusionReplayDatasetGenerator generator, FusionReplayRunner runner, ObjectMapper json) {
        this.generator = generator; this.runner = runner; this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Path target = Path.of(System.getProperty("app.replay.export.target", DEFAULT_TARGET));
        int lines = export(target);
        log.info("lingyun mqtt replay exported: file={}, lines={}", target.toAbsolutePath(), lines);
    }

    /** 写文件并返回行数；父目录不存在就建。 */
    public int export(Path target) throws IOException {
        List<String> lines = lines();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.write(target, lines, StandardCharsets.UTF_8);
        return lines.size();
    }

    /** 导出内容本身（不落盘），供测试逐行核对。 */
    public List<String> lines() {
        ProtocolDataset dataset = generator.generateV2();
        List<ProtocolRecord> records = new ArrayList<>(dataset.records());
        records.sort((left, right) -> Long.compare(left.recordNo(), right.recordNo()));
        List<String> lines = new ArrayList<>();
        for (ProtocolRecord record : records) lines.add(line(record));
        return List.copyOf(lines);
    }

    private String line(ProtocolRecord record) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("topic", topic(record.inboxSource()));
        line.put("qos", QOS);
        line.put("payload", runner.payloadJson(record.payload()));
        line.put("record_no", record.recordNo());
        line.put("received_at", record.receivedAtMillis());
        line.put("source", record.inboxSource());
        try {
            return json.writeValueAsString(line);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("导出行无法序列化: record_no=" + record.recordNo(), ex);
        }
    }

    /** `lingyun:<abbr>:<deviceId>` → 协议 A 主题；`eo-edge:<edgeId>` → 协议 C 主题。 */
    static String topic(String source) {
        if (source != null && source.startsWith(LingyunSenseDataMapper.PREFIX)) {
            String[] segments = source.split(":");
            if (segments.length < 3 || segments[1].isBlank() || segments[2].isBlank()) {
                throw new IllegalStateException("凌云来源格式应为 lingyun:<设备类型>:<设备号>: " + source);
            }
            return "bridge/" + PROVIDER_CODE + "/device_data/" + segments[1] + "/" + segments[2];
        }
        if (source != null && source.startsWith(EoTrackingReportMapper.PREFIX)) {
            String edgeId = source.substring(EoTrackingReportMapper.PREFIX.length());
            if (edgeId.isBlank()) throw new IllegalStateException("光电来源缺少边缘中心号: " + source);
            return "iot-reporting/cmlc/edge/" + edgeId;
        }
        // 认不出前缀就不猜主题：发到错误的主题比不发更糟，A 那边会当成另一类设备解释。
        throw new IllegalStateException("没有对应 MQTT 主题的来源前缀: " + source);
    }
}
