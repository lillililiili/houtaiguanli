package com.uav.lowaltitude.modules.fusion.ingest;

import static com.uav.lowaltitude.modules.fusion.ingest.JsonFields.identifier;
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
import com.uav.lowaltitude.modules.fusion.domain.IdentitySerials;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

/** Maps the explicitly authenticated normalized simulator contract into a fusion frame. */
@Component
public class NormalizedObservationMapper implements FrameMapper {
    public static final String PREFIX = "sim-normalized:";
    private final ObjectMapper json;

    public NormalizedObservationMapper(ObjectMapper json) { this.json = json; }

    @Override
    public String prefix() { return PREFIX; }

    @Override
    public Frame map(InboxRow inbox) {
        JsonNode root = read(inbox.payloadJson());
        String sourceId = textAny(root, "source_id", "sourceId");
        String owner = textAny(root, "owner_org_id", "ownerOrgId");
        String district = textAny(root, "district_id", "districtId");
        Double observed = numberAny(root, "observed_at", "observedAt");
        Long observedAt = observed == null ? null : observed.longValue();
        JsonNode items = root.get("items");
        if (!inbox.source().equals(PREFIX + sourceId) || owner == null || district == null || observedAt == null
                || items == null || !items.isArray() || items.isEmpty())
            throw new IllegalStateException("规范化模拟观测报文不完整");
        List<Item> parsed = new ArrayList<>();
        for (JsonNode node : items) {
            String external = identifierAny(node, "external_target_id", "externalTargetId");
            if (external == null) throw new IllegalStateException("规范化观测缺少 external_target_id");
            Double longitude = number(node, "longitude"), latitude = number(node, "latitude");
            if ((longitude == null) != (latitude == null)) throw new IllegalStateException("规范化观测经纬度必须成对提供");
            Map<String, Object> quality = new LinkedHashMap<>();
            String subtype = text(node, "subtype");
            if (subtype != null) quality.put("subtype", subtype);
            JsonNode count = node.has("object_count") ? node.get("object_count") : node.get("objectCount");
            if (count != null && !count.isNull()) {
                if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() <= 0)
                    throw new IllegalStateException("规范化观测 object_count 必须为正整数");
                quality.put("object_count", count.intValue());
            }
            quality.put("source", "LOCAL_SIMULATOR");
            String serial = textAny(node, "uav_sn", "uavSn");
            if (serial != null) quality.put(IdentitySerials.QUALITY_KEY, serial);
            parsed.add(new Item(external, textAny(node, "external_track_id", "externalTrackId"), longitude, latitude, null,
                    numberAny(node, "altitude_amsl_m", "altitudeAmslM"), numberAny(node, "height_agl_m", "heightAglM"),
                    numberAny(node, "speed_mps", "speedMps"), numberAny(node, "heading_deg", "headingDeg"),
                    textAny(node, "class_code", "classCode"), numberAny(node, "class_confidence", "classConfidence"),
                    serial, null, numberAny(node, "pilot_longitude", "pilotLongitude"),
                    numberAny(node, "pilot_latitude", "pilotLatitude"),
                    "SENSE_DATA", quality));
        }
        return new Frame(sourceId, observedAt, inbox.source(), Instant.ofEpochMilli(observedAt), List.copyOf(parsed));
    }

    private static String textAny(JsonNode node, String primary, String fallback) {
        String value = text(node, primary);
        return value == null ? text(node, fallback) : value;
    }

    private static Double numberAny(JsonNode node, String primary, String fallback) {
        Double value = number(node, primary);
        return value == null ? number(node, fallback) : value;
    }

    private static String identifierAny(JsonNode node, String primary, String fallback) {
        String value = identifier(node, primary);
        return value == null ? identifier(node, fallback) : value;
    }

    private JsonNode read(String payload) {
        try {
            JsonNode root = json.readTree(payload);
            if (root == null || !root.isObject()) throw new IllegalStateException("规范化观测不是 JSON 对象");
            return root;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("规范化观测无法解析", ex);
        }
    }
}
