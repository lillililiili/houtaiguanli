package com.uav.lowaltitude.integration.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 导出物必须与 8.5 回放写进 inbox 的原文**逐字一致**——这是 A 的 P1 退出条件的全部意义所在：
 * 他用 MQTT 发出来的报文，要能让我们这边复现出同一批观测。逐字不一致就等于两边在跑不同的数据。
 */
@SpringBootTest(properties = {
        "app.dev-seed.enabled=true", "app.fusion.enabled=false", "app.fusion.replay.run-on-start=false",
        "spring.datasource.url=jdbc:h2:mem:stage10_export;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class LingyunMqttReplayExporterTest {

    @Autowired FusionReplayDatasetGenerator generator;
    @Autowired FusionReplayRunner runner;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    /**
     * 直接构造而不是注入：导出器带 local profile 与开关双门禁，为了拿到 bean 而在测试里打开 local，
     * 会连带启用一堆只该在本地跑的 runner，而且它的 ApplicationRunner 会真的往仓库 docs/ 里写文件。
     * 这里只验导出内容，不该有落盘副作用。
     */
    private LingyunMqttReplayExporter exporter() {
        return new LingyunMqttReplayExporter(generator, runner, json);
    }

    @Test
    void oneLinePerDatasetRecordInRecordNoOrder() throws Exception {
        List<String> lines = exporter().lines();
        assertThat(lines).hasSize(generator.generateV2().records().size());
        long previous = -1;
        for (String line : lines) {
            long recordNo = json.readTree(line).path("record_no").asLong();
            // 发布顺序就是 record_no 顺序：A 逐行发布即可复现场景。
            assertThat(recordNo).isGreaterThan(previous);
            previous = recordNo;
        }
    }

    @Test
    void topicsFollowTheVendorProtocolShape() throws Exception {
        boolean sawSenseData = false, sawTracking = false;
        for (String line : exporter().lines()) {
            JsonNode node = json.readTree(line);
            String topic = node.path("topic").asText(), source = node.path("source").asText();
            assertThat(node.path("qos").asInt()).isEqualTo(1);
            if (source.startsWith("lingyun:")) {
                // bridge/{providerCode}/device_data/{deviceTypeAbbr}/{deviceId}
                assertThat(topic).matches("^bridge/dongying/device_data/[a-z0-9]+/[^/]+$");
                String[] segments = source.split(":");
                assertThat(topic).endsWith("/" + segments[1] + "/" + segments[2]);
                sawSenseData = true;
            } else if (source.startsWith("eo-edge:")) {
                assertThat(topic).isEqualTo("iot-reporting/cmlc/edge/" + source.substring("eo-edge:".length()));
                sawTracking = true;
            } else {
                throw new AssertionError("导出物里出现了未知来源前缀: " + source);
            }
        }
        assertThat(sawSenseData).as("数据集应含协议 A 报文").isTrue();
        assertThat(sawTracking).as("数据集应含协议 C 报文").isTrue();
    }

    @Test
    void payloadHashMatchesWhatTheReplaySeedWroteIntoInbox() throws Exception {
        // 种子已在上下文启动时把 v2 数据集写进 inbox；逐行比对 sha256(payload) 与库里的 payload_hash。
        int checked = 0;
        for (String line : exporter().lines()) {
            JsonNode node = json.readTree(line);
            String payload = node.path("payload").asText();
            Map<String, Object> row = jdbc.queryForMap("select payload_hash, cast(payload as varchar) as payload_text"
                    + " from inbox_message where source=? and source_msg_id=?",
                    node.path("source").asText(), Long.toString(node.path("record_no").asLong()));
            assertThat(FusionReplayReader.sha256(payload))
                    .as("第 %s 条的 payload 与 inbox 原文哈希不一致", node.path("record_no").asLong())
                    .isEqualTo(row.get("payload_hash"));
            checked++;
        }
        assertThat(checked).isEqualTo(generator.generateV2().records().size());
    }

    @Test
    void payloadIsTheDeviceMessageWithoutAnyReplayEnvelope() throws Exception {
        for (String line : exporter().lines()) {
            JsonNode payload = json.readTree(json.readTree(line).path("payload").asText());
            // 回放信封是我们自己的东西，设备不会发它；混进去 A 那边就发了一条厂家不认识的报文。
            assertThat(payload.has("dataset_id")).isFalse();
            assertThat(payload.has("frame")).isFalse();
            assertThat(payload.has("record_no")).isFalse();
            assertThat(payload.has("payload_hash")).isFalse();
            // 必须是厂家形状之一：协议 A 有 objects，协议 C 有 event。
            assertThat(payload.has("objects") || payload.has("event")).isTrue();
        }
    }

    /**
     * 仓库里那份生成物必须是"当前数据集导出的结果"（决策 10-9）。
     * 其余用例只保证"导出器与 inbox 一致"，保证不了"提交进仓库的文件是最新的"——
     * 数据集一改，A 手里的文件就悄悄过期，而联调时表现为哈希对不上，很难往回查。
     */
    @Test
    void theCommittedFileIsUpToDateWithTheCurrentDataset() throws Exception {
        Path file = repositoryRoot().resolve(LingyunMqttReplayExporter.DEFAULT_TARGET);
        assertThat(Files.exists(file)).as("找不到生成物 %s；请按《凌云回放说明》导出并提交", file).isTrue();

        List<String> committed = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> current = exporter().lines();
        String hint = "仓库里的 " + LingyunMqttReplayExporter.DEFAULT_TARGET + " 与当前数据集不一致："
                + "数据集变更后需按《凌云回放说明》重新导出并提交，否则协作者 A 手里的文件与我们库里的 payload_hash 对不上";
        assertThat(committed).as(hint + "（行数不同）").hasSameSizeAs(current);
        for (int i = 0; i < current.size(); i++) {
            // 逐行比对而不是整表比对：整表 diff 在 180 行上没法看，指出第一条不一致的记录更有用。
            assertThat(committed.get(i)).as(hint + "（第 %d 行起不同）", i + 1).isEqualTo(current.get(i));
        }
    }

    /**
     * 向上找到含 docs/ 的仓库根：Maven 下工作目录是 server/，IDE 里可能是仓库根，
     * 写死相对路径两边必有一边找不到文件。
     */
    private static Path repositoryRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("docs"))) return dir;
        }
        throw new IllegalStateException("从 " + Path.of("").toAbsolutePath() + " 向上没找到含 docs/ 的仓库根");
    }

    @Test
    void exportingTwiceProducesTheSameBytes() {
        // 可重复执行是前提：A 重新导一份必须和我们手里的这份一致，否则对不上账。
        assertThat(exporter().lines()).isEqualTo(exporter().lines());
    }
}
