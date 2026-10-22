package com.uav.lowaltitude.modules.fusion.ingest;

import static com.uav.lowaltitude.modules.fusion.ingest.JsonFields.number;
import static com.uav.lowaltitude.modules.fusion.ingest.JsonFields.text;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.geo.JsonCoordinateContract;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

/**
 * 阶段 8 的自建回放信封（`replay:<source_code>:<dataset_id>`）。字段已经是我们自己的形状，逐字搬运即可。
 * 既有历史读侧不变；旧原文没有坐标声明时，不再将它重新接入为新的业务位置。
 */
@Component
public class ReplayFrameMapper implements FrameMapper {
    public static final String PREFIX = "replay:";

    private final ObjectMapper json;

    public ReplayFrameMapper(ObjectMapper json) { this.json = json; }

    @Override
    public String prefix() { return PREFIX; }

    @Override
    public Frame map(InboxRow inbox) {
        try {
            JsonNode root = json.readTree(inbox.payloadJson());
            if (root.isTextual()) root = json.readTree(root.textValue());
            JsonNode frame = root.get("frame");
            if (frame == null || !frame.isObject()) throw new IllegalStateException("回放信封缺少 frame");
            List<Item> items = new ArrayList<>();
            for (JsonNode item : frame.withArray("items")) {
                if (JsonCoordinateContract.hasCoordinates(item, "lon", "lat")) {
                    try { JsonCoordinateContract.requireWgs84(root, frame, item); }
                    catch (IllegalArgumentException ex) { throw new IllegalStateException(ex.getMessage(), ex); }
                }
                Double latency = number(item, "latency_ms");
                items.add(new Item(text(item, "external_target_id"), text(item, "external_track_id"), number(item, "lon"), number(item, "lat"),
                        number(item, "position_accuracy_m"), number(item, "alt_amsl_m"), number(item, "height_agl_m"), number(item, "speed_mps"),
                        number(item, "heading_deg"), text(item, "class_code"), number(item, "class_confidence"), text(item, "identity_clue"),
                        latency == null ? null : latency.longValue()));
            }
            return new Frame(text(root, "dataset_id"), root.get("record_no").asLong(), text(frame, "source_code"),
                    Instant.ofEpochMilli(frame.get("observed_at").asLong()), List.copyOf(items));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("回放信封无法解析", ex);
        }
    }
}
