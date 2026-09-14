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
 * 凌云协议 A `SenseData`（设备 → 平台）→ 一帧观测。source 形如 `lingyun:<deviceTypeAbbr>:<deviceId>`。
 *
 * 三条容易被想当然做错的规则，都写死在这里：
 * <ul>
 *   <li><b>光电（oe）与 AOA 的经纬度必须置 NULL。</b>协议原文说这两类设备的位置字段无效——它们给的是"看向哪儿"，
 *       不是"目标在哪儿"。照抄进 location 会让关联把一堆假位置当成证据，AOA 的方位改记 quality.bearing_deg。</li>
 *   <li><b>altitude 不进高度列。</b>协议 A 写"海拔高度（GPS/北斗）"，协议 B/C 写"椭球高"，客户尚未确认到底是哪一个；
 *       未确认的高度进了 altitude_amsl_m 就会被合法性判定拿去和空域限高比较，得出一个没人能负责的结论。
 *       原值留在 quality.altitude_raw，基准标 REFERENCE_UNKNOWN。</li>
 *   <li><b>objectType 255 是"识别中"，不是一个类别。</b>落 class_code=NULL 并记 quality.identifying=true，
 *       否则页面会把"还没认出来"显示成一个确定的类型。</li>
 * </ul>
 * 精度字段协议里没有，留 null 由管线按 fusion_config 的缺省精度补并标记，本类不出现任何裸阈值。
 */
@Component
public class LingyunSenseDataMapper implements FrameMapper {
    public static final String PREFIX = "lingyun:";
    public static final String CLASS_SOURCE = "SENSE_DATA";
    /** 协议 A extension.objectType 码表；255（识别中）不在表里，单独按"未定类"处理。 */
    private static final Map<Integer, String> OBJECT_TYPES = Map.of(
            0, "UNKNOWN", 3, "PERSON", 7, "VEHICLE", 30, "UAV", 40, "BIRD", 50, "SHIP", 100, "REMOTE_CONTROLLER");
    private static final int IDENTIFYING = 255;
    /** 这两类设备的经纬度按协议无效。 */
    private static final List<String> WITHOUT_POSITION = List.of("oe", "aoa");
    private static final double FULL_CIRCLE_DEG = 360;

    private final ObjectMapper json;

    public LingyunSenseDataMapper(ObjectMapper json) { this.json = json; }

    @Override
    public String prefix() { return PREFIX; }

    @Override
    public Frame map(InboxRow inbox) {
        JsonNode root = read(inbox.payloadJson());
        JsonNode objects = root.get("objects");
        if (objects == null || !objects.isArray()) throw new IllegalStateException("SenseData 缺少 objects 数组");
        String deviceTypeAbbr = deviceTypeAbbr(inbox.source());
        boolean positionInvalid = WITHOUT_POSITION.contains(deviceTypeAbbr);
        Long msgCnt = integer(root, "msgCnt");
        Long ptTime = integer(root, "ptTime");

        List<Item> items = new ArrayList<>();
        Long frameObservedAt = null;
        String sessionKey = null;
        for (JsonNode object : objects) {
            String externalTargetId = identifier(object, "objectId");
            Long observedAt = integer(object, "time");
            if (externalTargetId == null || observedAt == null) {
                throw new IllegalStateException("SenseData 的 object 缺少 objectId 或 time");
            }
            if (frameObservedAt == null) frameObservedAt = observedAt;
            JsonNode extension = object.get("extension");
            String taskId = text(extension, "taskId");
            if (taskId != null) {
                // 会话键参与 link 身份：把两个跟踪任务并成一条 link 是静默错误，事后从数据里看不出来。
                // 现在按"一帧一任务"的前提拦下并让整帧失败，A 那边能立刻看见，而不是等到关联结果不对再回头查（决策 8.5-25）。
                if (sessionKey != null && !sessionKey.equals(taskId)) {
                    throw new IllegalStateException("MIXED_TASK_ID: 同一条 SenseData 里出现了两个任务 id（" + sessionKey + " 与 " + taskId + "）");
                }
                sessionKey = taskId;
            }
            items.add(item(object, extension, externalTargetId, observedAt, frameObservedAt, positionInvalid, msgCnt, ptTime));
        }
        if (frameObservedAt == null) {
            // 空 objects 是合法的"本帧什么都没探到"，不是坏报文（决策 10-16）：设备按固定周期上报，
            // 视野里没有目标时照样发一条。当成错误会把 inbox 刷成 FAILED 并累计 fusion_attempts，
            // 几个空闲周期就能把一台正常设备的帧推到重试上限，之后真有目标了也领不进来。
            // 与协议 C 的心跳同样处理：返回空帧，inbox 照常 DONE。
            // 此时帧内没有任何 time，观测时刻只能取报文级的 ptTime；连 ptTime 都没有才是真的说不清这帧是什么时候的。
            if (ptTime == null) throw new IllegalStateException("SenseData 的 objects 为空且缺少 ptTime，无法确定该帧的时刻");
            return Frame.empty(sessionKey == null ? inbox.source() : sessionKey, msgCnt == null ? 0L : msgCnt,
                    inbox.source(), Instant.ofEpochMilli(ptTime));
        }
        // 没有任务 id 的设备（雷达/TDOA 等）用 source 本身作会话键：它稳定、非空，且天然按设备分区。
        return new Frame(sessionKey == null ? inbox.source() : sessionKey, msgCnt == null ? 0L : msgCnt,
                inbox.source(), Instant.ofEpochMilli(frameObservedAt), List.copyOf(items));
    }

    private Item item(JsonNode object, JsonNode extension, String externalTargetId, long observedAt, long frameObservedAt,
            boolean positionInvalid, Long msgCnt, Long ptTime) {
        Map<String, Object> quality = new LinkedHashMap<>();
        if (msgCnt != null) quality.put("msg_cnt", msgCnt);
        if (ptTime != null) quality.put("pt_time", ptTime);
        // 同一条 SenseData 里各目标的探测时刻理论上一致；不一致时如实记下，别让整帧时刻掩盖差异。
        if (observedAt != frameObservedAt) quality.put("observed_at_raw", observedAt);

        Double longitude = number(object, "longitude"), latitude = number(object, "latitude");
        if (positionInvalid) {
            quality.put("position", "REFERENCE_UNKNOWN");
            longitude = null; latitude = null;
        }
        Double bearing = number(extension, "direction");
        if (bearing != null) quality.put("bearing_deg", bearing);

        Double altitude = number(object, "altitude");
        if (altitude != null) {
            quality.put("altitude_raw", altitude);
            quality.put("altitude_datum", "REFERENCE_UNKNOWN");
        }
        Double heightAglM = number(object, "height");
        if (heightAglM != null) quality.put("height_datum", "DEVICE_GROUND");

        Double speedX = number(extension, "speedX"), speedY = number(extension, "speedY"), speedZ = number(extension, "speedZ");
        Double heading = heading(speedX, speedY);
        if (speedX != null || speedY != null || speedZ != null) {
            Map<String, Object> speedXyz = new LinkedHashMap<>();
            if (speedX != null) speedXyz.put("x", speedX);
            if (speedY != null) speedXyz.put("y", speedY);
            if (speedZ != null) speedXyz.put("z", speedZ);
            quality.put("speed_xyz", speedXyz);
        }

        String classCode = null;
        Long objectType = integer(extension, "objectType");
        if (objectType != null) {
            if (objectType == IDENTIFYING) quality.put("identifying", true);
            else {
                classCode = OBJECT_TYPES.get(objectType.intValue());
                // 码表外的取值原样留痕：宁可类别为空，也不要猜一个类型上屏。
                if (classCode == null) quality.put("object_type_raw", objectType);
            }
        }

        Double rcs = number(extension, "rcs");
        if (rcs != null) quality.put("rcs_m2", rcs);
        Map<String, Object> sizeCm = new LinkedHashMap<>();
        putIfPresent(sizeCm, "length", integer(extension, "length"));
        putIfPresent(sizeCm, "width", integer(extension, "width"));
        putIfPresent(sizeCm, "height", integer(extension, "height"));
        if (!sizeCm.isEmpty()) quality.put("size_cm", sizeCm);

        Map<String, Object> rf = new LinkedHashMap<>();
        putIfPresent(rf, "channel", text(extension, "channel"));
        putIfPresent(rf, "band_width", text(extension, "bandWidth"));
        if (!rf.isEmpty()) quality.put("rf", rf);

        String uavSn = text(extension, "uavSN"), uavModel = text(extension, "uavModel");
        if (uavModel != null) quality.put("uav_model", uavModel);
        String taskId = text(extension, "taskId");
        if (taskId != null) quality.put("task_id", taskId);

        return new Item(externalTargetId, null, longitude, latitude, null, null, heightAglM,
                number(object, "speed"), heading, classCode, number(extension, "probability"),
                // 身份线索按协议优先取序列号：型号只说"是哪一款"，序列号才指向具体这一台。
                uavSn != null ? uavSn : uavModel, null,
                number(extension, "pilotLon"), number(extension, "pilotLat"), CLASS_SOURCE, quality);
    }

    /** X 正东、Y 正北：航向 = atan2(东, 北)，归一到 [0,360)。两轴都缺就没有航向，不补 0。 */
    private static Double heading(Double speedX, Double speedY) {
        if (speedX == null || speedY == null) return null;
        if (speedX == 0 && speedY == 0) return null;
        double degrees = Math.toDegrees(Math.atan2(speedX, speedY));
        return (degrees % FULL_CIRCLE_DEG + FULL_CIRCLE_DEG) % FULL_CIRCLE_DEG;
    }

    /** source 形如 `lingyun:<deviceTypeAbbr>:<deviceId>`；取第二段。 */
    static String deviceTypeAbbr(String source) {
        String[] segments = (source == null ? "" : source).split(":");
        if (segments.length < 3 || segments[1].isBlank()) throw new IllegalStateException("凌云来源格式应为 lingyun:<设备类型>:<设备号>: " + source);
        return segments[1].trim().toLowerCase();
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private JsonNode read(String payloadJson) {
        try {
            JsonNode root = json.readTree(payloadJson);
            if (root != null && root.isTextual()) root = json.readTree(root.textValue());
            if (root == null || !root.isObject()) throw new IllegalStateException("SenseData 不是 JSON 对象");
            return root;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("SenseData 无法解析", ex);
        }
    }
}
