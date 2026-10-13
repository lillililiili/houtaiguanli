package com.uav.lowaltitude.modules.fusion.ingest;

import static com.uav.lowaltitude.modules.fusion.ingest.JsonFields.integer;
import static com.uav.lowaltitude.modules.fusion.ingest.JsonFields.number;
import static com.uav.lowaltitude.modules.fusion.ingest.JsonFields.text;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

/**
 * 凌云协议 C 光电边端上报（`eo-edge:<edgeId>`）→ 一帧观测。
 *
 * 光电只在**跟踪任务期间**有观测：只有 `event=BeginTracking` 且 `metadata.workState=1` 的上报才产生观测，
 * `EndTracking` 与 `HeartBeat` 返回空帧（inbox 照常 DONE，不是错误）。这正是光电与其他源的本质差别——
 * 它不是持续扫描的传感器，把心跳也当成观测会凭空造出"目标一直可见"的假象。
 *
 * <p><b>引导源 objectData 不入库。</b>它描述的是"谁把光电指过去的"（哈勃 / 飞行家 / 5G-A 的目标），
 * 是另一个来源已经上报过的同一个物理目标；再入一次库等于把同一个证据数两遍，关联时会自己和自己配对。
 * 只把它的标识记进 quality.bootstrap_source_*，供来源核验时回溯。
 *
 * <p>类别只认协议明确支持的 drone / bird；其余取值一律 class_code=NULL 并把原串留在 quality.class_name_raw
 * ——`className` 的完整取值表客户还没给，猜一个映射比留空更危险。
 */
@Component
public class EoTrackingReportMapper implements FrameMapper {
    public static final String PREFIX = "eo-edge:";
    public static final String CLASS_SOURCE = "EO_TRACKING";
    public static final String BEGIN_TRACKING = "BeginTracking";
    /** 协议 C 目前只承诺 drone / bird 两种取值。 */
    private static final Map<String, String> CLASS_NAMES = Map.of("drone", "UAV", "bird", "BIRD");
    private static final long WORK_STATE_TRACKING = 1;

    private final ObjectMapper json;

    public EoTrackingReportMapper(ObjectMapper json) { this.json = json; }

    @Override
    public String prefix() { return PREFIX; }

    @Override
    public Frame map(InboxRow inbox) {
        JsonNode root = read(inbox.payloadJson());
        String event = text(root, "event");
        if (event == null) throw new IllegalStateException("光电上报缺少 event");
        Long timestamp = integer(root, "timestamp");
        if (timestamp == null) throw new IllegalStateException("光电上报缺少 timestamp");
        JsonNode metadata = root.get("metadata");
        String taskId = text(metadata, "taskId");
        Instant observedAt = Instant.ofEpochMilli(timestamp);
        String sessionKey = taskId == null ? inbox.source() : taskId;

        Long workState = integer(metadata, "workState");
        Long codeStatus = integer(metadata, "codeStatus");
        JsonNode aiStatus = metadata == null ? null : metadata.get("aiStatus");
        // 心跳与结束跟踪不是错误，只是没有观测；未映射的新事件同样放过，等契约明确再接。
        if (!BEGIN_TRACKING.equals(event) || codeStatus != null && codeStatus != 200
                || workState == null || workState != WORK_STATE_TRACKING
                || aiStatus == null || !aiStatus.isObject()) {
            return Frame.empty(sessionKey, timestamp, inbox.source(), observedAt);
        }
        if (taskId == null) throw new IllegalStateException("BeginTracking 上报缺少 taskId");

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("event", event);
        String edgeId = text(root, "edgeId");
        if (edgeId != null) quality.put("edge_id", edgeId);
        quality.put("eo_task_id", taskId);
        String externalDevice = text(metadata, "deviceId");
        if (externalDevice != null) quality.put("eo_external_device_id", externalDevice);
        Double trackConfidence = validated(aiStatus, "trackConfidence", 0, 1, quality);
        if (trackConfidence != null) quality.put("track_confidence", trackConfidence);
        Double altitude = validated(aiStatus, "altitude", -Double.MAX_VALUE, Double.MAX_VALUE, quality);
        if (altitude != null) {
            // 与协议 A 同一条规矩：基准未确认前高度不进合法性比较。
            quality.put("altitude_raw", altitude);
            quality.put("altitude_datum", "REFERENCE_UNKNOWN");
        }
        putCamera(quality, metadata.get("cameraStatus"));
        putBootstrapSource(quality, metadata.get("objectData"), metadata.get("extention"));

        String className = text(aiStatus, "className");
        String classCode = null;
        if (className != null) {
            classCode = CLASS_NAMES.get(className.toLowerCase());
            if (classCode == null) quality.put("class_name_raw", className);
        }

        // 一个跟踪任务就是光电眼里的一个目标：协议没有给目标号，taskId 既是会话键也是外部目标标识。
        Double longitude = validated(aiStatus, "longitude", -180, 180, quality);
        Double latitude = validated(aiStatus, "latitude", -90, 90, quality);
        if (longitude == null || latitude == null) quality.put("eo_fusion_exclusion", "EO_POSITION_UNKNOWN");
        Item item = new Item(taskId, null, longitude, latitude, null,
                null, null, null, null, classCode, validated(aiStatus, "detectConfidence", 0, 1, quality), null, null,
                null, null, CLASS_SOURCE, quality);
        return new Frame(sessionKey, timestamp, inbox.source(), observedAt, List.of(item));
    }

    private static Double validated(JsonNode node, String field, double min, double max, Map<String, Object> quality) {
        Double value = number(node, field);
        if (value != null && Double.isFinite(value) && value >= min && value <= max) return value;
        if (node.has(field) && !node.path(field).isNull()) quality.put("invalid_" + field, node.path(field).toString());
        return null;
    }

    private static void putCamera(Map<String, Object> quality, JsonNode cameraStatus) {
        if (cameraStatus == null || !cameraStatus.isObject()) return;
        Map<String, Object> camera = new LinkedHashMap<>();
        for (String field : List.of("hfov", "vfov", "panOrientAngle", "tiltOrientAngle", "focalLen", "detectDist", "zoomIndex")) {
            Double value = number(cameraStatus, field);
            if (value != null) camera.put(field, value);
        }
        if (!camera.isEmpty()) quality.put("camera", camera);
    }

    /** 只留引导源的标识，不留它的位置与类别——那些是另一个来源的观测，不是光电看到的。 */
    private static void putBootstrapSource(Map<String, Object> quality, JsonNode objectData, JsonNode extention) {
        String bootstrapSourceId = text(extention, "bootstrapSourceId");
        if (bootstrapSourceId != null) quality.put("bootstrap_source_id", bootstrapSourceId);
        Long bootstrapSourceType = integer(extention, "bootstrapSourceType");
        if (bootstrapSourceType != null) quality.put("bootstrap_source_type", bootstrapSourceType);
        String dataId = text(objectData, "dataId");
        if (dataId != null) quality.put("bootstrap_source_data_id", dataId);
    }

    private JsonNode read(String payloadJson) {
        try {
            JsonNode root = json.readTree(payloadJson);
            if (root != null && root.isTextual()) root = json.readTree(root.textValue());
            if (root == null || !root.isObject()) throw new IllegalStateException("光电上报不是 JSON 对象");
            return root;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("光电上报无法解析", ex);
        }
    }
}
