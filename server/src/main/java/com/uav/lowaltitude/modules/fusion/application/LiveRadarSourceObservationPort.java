package com.uav.lowaltitude.modules.fusion.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceObservationPort;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 实测雷达 ops → 阶段 2 提升的真实入口（阶段 8.5，接替 {@link NoopSourceObservationPort}）。
 *
 * <p>只做一件事：把 A 侧交来的雷达帧按契约 §2 写成 {@code inbox_message} 信封，
 * {@code source = live-radar:<device_id>}、{@code source_msg_id = <boot_micros>:<frame_id>}。
 * <b>不写任何业务表</b>——{@code target / track / track_point / source_observation} 一行都不碰。
 * 语义映射由 {@code LiveRadarFrameMapper} 在管线领取后完成，实测数据与回放数据因此走的是同一条管线：
 * 关联、身份、降级逻辑只有一份实现，不会出现"实测走另一套写入"的分叉。
 *
 * <p>写入前只做<b>信封级</b>校验（必需字段在不在、类型对不对）。这是端口自己的职责范围——
 * 它要产出的就是信封的三个键。更深的语义（分类码表、精度缺省、站址基准）属于映射器，不在这里重复实现；
 * 校验不过的帧直接拒收，不写进 inbox：写进去再 FAILED 等于把毒帧留在库里白占领取次数。
 *
 * <p>站址 RTK 缺失时位置为 NULL 并在 quality 记 {@code REFERENCE_UNKNOWN}、<b>绝不补 (0,0)</b>——
 * 这条规则落在映射层，本端口原样透传 payload，不在这里替设备猜一个坐标。
 *
 * <p>{@code app.fusion.live-promotion.enabled=true} 时才注册；关闭时由 {@link NoopSourceObservationPort} 兜底，
 * 且 {@code FusionInboxRepository.claim} 的前缀白名单也不含 {@code live-radar:}（两道闸，见 8.5.3 报告的共享变更请求）。
 */
@Component
@ConditionalOnProperty(prefix = "app.fusion.live-promotion", name = "enabled", havingValue = "true")
public class LiveRadarSourceObservationPort implements SourceObservationPort {
    private static final Logger log = LoggerFactory.getLogger(LiveRadarSourceObservationPort.class);
    public static final String SOURCE_PREFIX = "live-radar:";

    private final FusionInboxRepository inbox;
    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final ObjectMapper json;

    public LiveRadarSourceObservationPort(FusionInboxRepository inbox, JdbcTemplate jdbc, AppClock clock, ObjectMapper json) {
        this.inbox = inbox; this.jdbc = jdbc; this.clock = clock; this.json = json;
    }

    @Override
    public void accept(List<Map<String, Object>> observations) {
        if (observations == null || observations.isEmpty()) return;
        int accepted = 0, duplicate = 0, conflict = 0, rejected = 0;
        for (Map<String, Object> frame : observations) {
            try {
                write(frame);
                accepted++;
            } catch (DuplicateFrame ignored) {
                duplicate++;
            } catch (IllegalStateException sourceConflict) {
                // 同键不同哈希：同一条报文被两种内容重发，说明上游有问题。已存的信封**不覆盖**——
                // 先到的那份才是当时真正收到的证据，后到的用它盖掉等于篡改历史输入。单独计数并显式告警。
                conflict++;
                log.error("live radar frame conflicts with a stored envelope, kept the stored one: {}", sourceConflict.getMessage());
            } catch (RuntimeException rejection) {
                // 拒收不抛给 A：一帧不合规不该让整批上报失败，但必须留下可定位的日志。
                rejected++;
                log.warn("live radar frame rejected: reason={}", rejection.getMessage());
            }
        }
        log.info("live radar frames ingested: accepted={}, duplicate={}, conflict={}, rejected={}", accepted, duplicate, conflict, rejected);
    }

    /** 重复帧抛 {@link DuplicateFrame}，不合规帧抛 {@link IllegalArgumentException}；正常返回即已写入一条新信封。 */
    private void write(Map<String, Object> frame) {
        String deviceId = requiredText(frame, "device_id");
        String sourceMsgId = requiredText(frame, "boot_micros") + ":" + requiredText(frame, "frame_id");
        requireItems(frame);
        String sourceId = resolveSourceId(deviceId);
        String payload = serialize(frame);
        boolean inserted = inbox.insertEnvelope(SOURCE_PREFIX + deviceId, sourceMsgId, clock.nowMillis(), sourceId, sha256(payload), payload);
        if (!inserted) throw new DuplicateFrame();
    }

    /**
     * 设备必须先在 {@code integration_source} 注册（契约 §2：A 注册设备时建）。查不到就拒收，
     * <b>不自动建来源行</b>：凭一条上报就凭空建来源，等于让未注册设备的数据混进统一目标库，
     * 而 {@code source_type}/{@code schema_status} 只有注册流程知道该填什么。
     */
    private String resolveSourceId(String deviceId) {
        try {
            return jdbc.queryForObject("SELECT source_id FROM integration_source WHERE source_code=? AND enabled=true", String.class, deviceId);
        } catch (EmptyResultDataAccessException notRegistered) {
            throw new IllegalArgumentException("设备未注册或已停用，拒收: " + deviceId);
        }
    }

    private static String requiredText(Map<String, Object> frame, String field) {
        Object value = frame.get(field);
        if (value == null) throw new IllegalArgumentException("缺少必填字段 " + field);
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) throw new IllegalArgumentException("字段 " + field + " 为空");
        return text;
    }

    /** 契约 §2 的雷达帧必须带 items 数组。空数组是合法的"本帧无目标"，缺字段才是不合规。 */
    private static void requireItems(Map<String, Object> frame) {
        Object items = frame.get("items");
        if (!(items instanceof List<?>)) throw new IllegalArgumentException("items 必须是数组");
    }

    private String serialize(Map<String, Object> frame) {
        try {
            return json.writeValueAsString(frame);
        } catch (JsonProcessingException notSerializable) {
            throw new IllegalArgumentException("帧无法序列化为 JSON: " + notSerializable.getOriginalMessage());
        }
    }

    /** {@code ck_stage2_inbox_payload_hash} 要求 64 位小写十六进制。 */
    private static String sha256(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 同键同哈希的重复上报：契约要求丢弃并计数，不是错误。 */
    private static final class DuplicateFrame extends RuntimeException {
        DuplicateFrame() { super("duplicate frame", null, false, false); }
    }
}
