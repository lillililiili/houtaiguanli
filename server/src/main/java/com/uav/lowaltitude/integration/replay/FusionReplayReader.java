package com.uav.lowaltitude.integration.replay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 回放信封读取与校验（沿用 docs/backend-stage1/t02-replay-contract.md）：
 * source = replay:&lt;source_code&gt;:&lt;dataset_id&gt;，source_msg_id = record_no，payload_hash = SHA-256(规范 JSON)。
 * 哈希不符或 record_no 重复一律拒绝：回放的价值在于"同一数据集重放得到同一结论"，
 * 放过一条被改过的记录，之后所有回归结论都不再可信。
 */
@Component
public class FusionReplayReader {
    private final ObjectMapper json;

    public FusionReplayReader(ObjectMapper json) { this.json = json; }

    public record Envelope(String datasetId, long recordNo, String sourceCode, String source, long receivedAtMillis, String payloadJson, String payloadHash) { }

    /** 解析一行 NDJSON 信封并校验哈希；record_no 的唯一性由 {@link #readAll} 跨行检查。 */
    public Envelope read(String line) {
        JsonNode root;
        try { root = json.readTree(line == null ? "" : line); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("回放记录不是合法 JSON", ex); }
        if (root == null || !root.isObject()) throw new IllegalStateException("回放记录不是 JSON 对象");
        for (String field : new String[]{"dataset_id", "record_no", "received_at", "frame", "payload_hash"}) {
            if (root.get(field) == null || root.get(field).isNull()) throw new IllegalStateException("回放记录缺少字段: " + field);
        }
        JsonNode frame = root.get("frame");
        if (!frame.isObject() || frame.get("source_code") == null || frame.get("observed_at") == null || !frame.withArray("items").isArray()) {
            throw new IllegalStateException("回放记录 frame 结构无效");
        }
        String datasetId = root.get("dataset_id").asText();
        long recordNo = root.get("record_no").asLong();
        String sourceCode = frame.get("source_code").asText();
        String declared = root.get("payload_hash").asText();
        String payloadJson = payloadJson(root);
        String actual = sha256(payloadJson);
        if (!actual.equals(declared)) throw new IllegalStateException("回放记录哈希不符: record_no=" + recordNo + "（声明 " + declared + "，实际 " + actual + "）");
        return new Envelope(datasetId, recordNo, sourceCode, source(sourceCode, datasetId), root.get("received_at").asLong(), payloadJson, actual);
    }

    public List<Envelope> readAll(List<String> lines) {
        List<Envelope> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            Envelope envelope = read(line);
            if (!seen.add(envelope.source() + "#" + envelope.recordNo())) {
                throw new IllegalStateException("回放数据集内 record_no 重复: " + envelope.source() + "#" + envelope.recordNo());
            }
            out.add(envelope);
        }
        return List.copyOf(out);
    }

    public static String source(String sourceCode, String datasetId) { return "replay:" + sourceCode + ":" + datasetId; }

    /** 规范 JSON：payload 是去掉 payload_hash 后的整条记录，字段按字母序，保证哈希可复算。 */
    public String payloadJson(JsonNode root) {
        com.fasterxml.jackson.databind.node.ObjectNode copy = ((com.fasterxml.jackson.databind.node.ObjectNode) root).deepCopy();
        copy.remove("payload_hash");
        try { return json.writer().withDefaultPrettyPrinter() == null ? copy.toString() : canonical(copy); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("回放载荷无法规范化", ex); }
    }

    private String canonical(JsonNode node) throws JsonProcessingException {
        return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(json.treeToValue(node, java.util.TreeMap.class));
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
