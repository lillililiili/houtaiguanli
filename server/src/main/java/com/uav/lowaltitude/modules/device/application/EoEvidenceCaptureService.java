package com.uav.lowaltitude.modules.device.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.EvidenceDetailDto;
import com.uav.lowaltitude.modules.evidence.application.EvidenceAssociationService;
import com.uav.lowaltitude.modules.evidence.application.EvidenceAssociationService.EoCapture;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 光电跟踪中由平台视频画面截图、录像并存为证据。
 * 现有设备协议没有定义图片和视频回传（协议 B 的拍照/摄像指令只下发、不回传），
 * 所以取证来自平台正在播放的跟踪视频流；设备、任务和视频流由服务端按当前跟踪任务核定。
 */
@Service
public class EoEvidenceCaptureService {
    private static final Set<String> FIELDS = Set.of("kind_code", "stream_id", "event_id", "capture_age_ms");
    private static final Set<String> KINDS = Set.of("EO_STILL", "EO_VIDEO");
    /**
     * 取证时刻按“发出请求时距截图或开始录像过了多久”由服务端换算，不收客户端钟点，免得电脑时钟不准。
     * 录像最长一分钟，放宽到十分钟内，防止把旧画面登记成刚取的证据。
     */
    private static final long MAX_CAPTURE_AGE_MS = 10 * 60_000L;

    private final AccessControlService access;
    private final TargetVideoService video;
    private final EvidenceAssociationService evidence;
    private final AppClock clock;

    public EoEvidenceCaptureService(AccessControlService access, TargetVideoService video,
                                    EvidenceAssociationService evidence, AppClock clock) {
        this.access = access; this.video = video; this.evidence = evidence; this.clock = clock;
    }

    public EvidenceDetailDto capture(String targetId, MultipartFile file, MultiValueMap<String, String> form,
                                     String idempotencyKey) {
        access.require(PermissionCode.EVIDENCE_INGEST);
        form.keySet().stream().filter(key -> !FIELDS.contains(key) && !"file".equals(key)).findFirst()
                .ifPresent(key -> { throw invalid("参数无效"); });
        String kind = one(form, "kind_code", true);
        if (!KINDS.contains(kind)) throw invalid("kind_code 只能是 EO_STILL（截图）或 EO_VIDEO（录像）");
        String streamId = one(form, "stream_id", true);
        String eventId = one(form, "event_id", false);
        Instant capturedAt = capturedAt(one(form, "capture_age_ms", false));
        TargetVideoService.CaptureSource source = video.captureSource(targetId, streamId);
        return evidence.ingestEoCapture(new EoCapture(kind, source.targetId(), eventId, source.deviceId(),
                source.taskId(), source.streamId(), source.sourceMode(), capturedAt), file, idempotencyKey);
    }

    private Instant capturedAt(String raw) {
        if (raw == null) return null;
        long age;
        try { age = Long.parseLong(raw); }
        catch (NumberFormatException ex) { throw invalid("capture_age_ms 参数无效"); }
        if (age < 0 || age > MAX_CAPTURE_AGE_MS) throw invalid("只能保存最近 10 分钟内截取的画面");
        return Instant.ofEpochMilli(clock.nowMillis() - age);
    }

    private static String one(MultiValueMap<String, String> form, String name, boolean required) {
        List<String> found = form.get(name);
        if (found == null) {
            if (required) throw invalid(name + " 参数无效");
            return null;
        }
        if (found.size() != 1 || found.get(0) == null || found.get(0).isBlank() || found.get(0).trim().length() > 64)
            throw invalid(name + " 参数无效");
        return found.get(0).trim();
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
