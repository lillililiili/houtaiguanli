package com.uav.lowaltitude.modules.fusion.ingest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

/**
 * 一条 inbox 报文 → 一帧观测。每种来源协议一个实现，由 {@link InboxSourceRouter} 按 source 前缀分派。
 *
 * 映射器只做"把厂家报文翻译成我们的字段"，不访问数据库、不做关联与滤波：这样每种协议的解释规则都能用纯 Java
 * 单测钉住，换厂家时只替换对应实现。翻不动的报文一律抛异常，让整帧 FAILED——把半条报文写进库比丢掉它更糟。
 */
public interface FrameMapper {

    /** 匹配的 source 前缀，例如 {@code "lingyun:"}。 */
    String prefix();

    /** 报文无法解释时抛 {@link IllegalStateException}；能解释但本条不产生观测（心跳、结束跟踪）时返回 items 为空的帧。 */
    Frame map(InboxRow inbox);

    /**
     * 一帧：同一来源同一时刻的一批观测。
     * observedAt 是整帧的观测时刻，用于关联时的状态预测；同帧内各目标自带时刻不同时，差异记在 item 的 quality 里。
     */
    record Frame(String sessionKey, long recordNo, String sourceCode, Instant observedAt, List<Item> items) {
        public static Frame empty(String sessionKey, long recordNo, String sourceCode, Instant observedAt) {
            return new Frame(sessionKey, recordNo, sourceCode, observedAt, List.of());
        }
    }

    /**
     * 一条观测。字段缺失即 null，不补默认值——缺失的原因写进 quality，由后续环节决定怎么处理。
     * 高度两列（altitudeAmslM / heightAglM）只在基准明确时才填：凌云协议 A 的 altitude 基准（海拔 vs 椭球高）
     * 尚未与客户确认，填进去等于让合法性判定拿一个来路不明的数字去比空域限高。
     */
    record Item(String externalTargetId, String externalTrackId, Double longitude, Double latitude, Double positionAccuracyM,
            Double altitudeAmslM, Double heightAglM, Double speedMps, Double headingDeg, String classCode, Double classConfidence,
            String identityClue, Long latencyMs, Double pilotLongitude, Double pilotLatitude, String classSource,
            Map<String, Object> quality) {

        public Item {
            quality = quality == null ? new LinkedHashMap<>() : new LinkedHashMap<>(quality);
        }

        /** 阶段 8 回放帧的旧形状：没有飞手位置、类别来源与附加 quality。 */
        public Item(String externalTargetId, String externalTrackId, Double longitude, Double latitude, Double positionAccuracyM,
                Double altitudeAmslM, Double heightAglM, Double speedMps, Double headingDeg, String classCode, Double classConfidence,
                String identityClue, Long latencyMs) {
            this(externalTargetId, externalTrackId, longitude, latitude, positionAccuracyM, altitudeAmslM, heightAglM, speedMps,
                    headingDeg, classCode, classConfidence, identityClue, latencyMs, null, null, null, null);
        }
    }
}
