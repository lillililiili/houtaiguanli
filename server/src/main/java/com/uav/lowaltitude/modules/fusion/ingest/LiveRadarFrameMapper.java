package com.uav.lowaltitude.modules.fusion.ingest;

import static com.uav.lowaltitude.modules.fusion.ingest.JsonFields.identifier;
import static com.uav.lowaltitude.modules.fusion.ingest.JsonFields.integer;
import static com.uav.lowaltitude.modules.fusion.ingest.JsonFields.number;
import static com.uav.lowaltitude.modules.fusion.ingest.JsonFields.text;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

/**
 * 实测雷达轨迹批（`live-radar:<deviceId>`，契约 v1.1 §2）→ 一帧观测。
 * 本类只做解析；是否真的把实测雷达提升进统一目标库由 `app.fusion.live-promotion.enabled` 控制（默认关，助手接）。
 *
 * 高度同样不进基准列：雷达的 `z_m` 是相对站址的高度，站址高程与基准尚未确认，进 altitude_amsl_m 会让
 * 合法性判定拿一个来路不明的数字去比空域限高。原值留在 quality.altitude_raw。
 */
@Component
public class LiveRadarFrameMapper implements FrameMapper {
    public static final String PREFIX = "live-radar:";
    public static final String CLASS_SOURCE = "RADAR";

    private final ObjectMapper json;

    public LiveRadarFrameMapper(ObjectMapper json) { this.json = json; }

    @Override
    public String prefix() { return PREFIX; }

    @Override
    public Frame map(InboxRow inbox) {
        JsonNode root = read(inbox.payloadJson());
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) throw new IllegalStateException("雷达帧缺少 items 数组");
        Long bootMicros = integer(root, "boot_micros");
        String frameId = identifier(root, "frame_id");
        if (bootMicros == null || frameId == null) throw new IllegalStateException("雷达帧缺少 boot_micros 或 frame_id");
        String deviceId = text(root, "device_id");

        List<Item> parsed = new ArrayList<>();
        for (JsonNode item : items) {
            String externalTrackId = identifier(item, "external_track_id");
            if (externalTrackId == null) throw new IllegalStateException("雷达轨迹缺少 external_track_id");
            Map<String, Object> quality = new LinkedHashMap<>();
            quality.put("frame_id", frameId);
            quality.put("boot_micros", bootMicros);
            Double z = number(item, "z_m");
            if (z != null) {
                quality.put("altitude_raw", z);
                quality.put("altitude_datum", "REFERENCE_UNKNOWN");
            }
            Map<String, Object> velocity = new LinkedHashMap<>();
            putIfPresent(velocity, "x", number(item, "velocity_x_mps"));
            putIfPresent(velocity, "y", number(item, "velocity_y_mps"));
            putIfPresent(velocity, "z", number(item, "velocity_z_mps"));
            if (!velocity.isEmpty()) quality.put("speed_xyz", velocity);
            putIfPresent(quality, "snr_db", number(item, "snr_db"));
            putIfPresent(quality, "rcs_m2", number(item, "rcs_m2"));
            String classification = text(item, "classification");
            // 雷达分类码的取值表尚未确认，原样留痕而不映射成我们的类别字典。
            if (classification != null) quality.put("classification_raw", classification);

            parsed.add(new Item(externalTrackId, externalTrackId, number(item, "longitude"), number(item, "latitude"),
                    null, null, null, null, null, null, null, null, null, null, null, CLASS_SOURCE, quality));
        }
        // 一帧一个会话键：雷达重启后 boot_micros 归零，把它并进会话键才不会把重启前后的轨迹号当成同一条。
        String sessionKey = (deviceId == null ? inbox.source() : deviceId) + ":" + bootMicros;
        return new Frame(sessionKey, Long.parseLong(frameId.replaceAll("\\D", "").isEmpty() ? "0" : frameId.replaceAll("\\D", "")),
                inbox.source(), Instant.ofEpochMilli(inbox.receivedAtMillis()), List.copyOf(parsed));
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private JsonNode read(String payloadJson) {
        try {
            JsonNode root = json.readTree(payloadJson);
            if (root != null && root.isTextual()) root = json.readTree(root.textValue());
            if (root == null || !root.isObject()) throw new IllegalStateException("雷达帧不是 JSON 对象");
            return root;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("雷达帧无法解析", ex);
        }
    }
}
