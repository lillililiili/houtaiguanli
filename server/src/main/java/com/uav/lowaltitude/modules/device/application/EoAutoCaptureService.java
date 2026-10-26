package com.uav.lowaltitude.modules.device.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.uav.lowaltitude.modules.evidence.application.EvidenceAssociationService;
import com.uav.lowaltitude.modules.evidence.application.EvidenceAssociationService.EoCapture;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 告警核实属实、反制开始时自动截图并录一段像（2026-10-19 用户确认）。
 * <p>
 * 触发只看已落库的事实：风险核实账本、兼容核实历史里新出现的“属实”、反制授权事件里的“开始执行”。
 * 风险处置核实不改写实测违法结论；同次核实双写的新旧历史只取证一次。每次触发先写一条审计占位，
 * 同一触发只处理一次（重启后也不重复）。
 * <p>
 * 画面只取该目标进行中的光电跟踪任务正在登记的视频流；当时没有画面就在审计里如实记下，不拿别的画面代替。
 * 截图、录像由本机 ffmpeg 从视频服务只读拉流，入证据库并关联告警事件、目标、光电设备和反制授权。
 */
@Service
public class EoAutoCaptureService {
    private static final Logger log = LoggerFactory.getLogger(EoAutoCaptureService.class);
    static final String ACTION = "eo_auto_capture";
    static final String ALARM = "ALARM_CONFIRMED", RISK = "RISK_CONFIRMED", COUNTER = "COUNTER_STARTED";
    // A case confirmation can also append the original event verdict. Match that exact write,
    // never every confirmation for the target/event: distinct regions remain distinct triggers.
    private static final String SAME_VERIFICATION = """
            rv.event_id=v.event_id AND rv.event_version+1=v.version AND rv.conclusion='CONFIRMED' AND v.conclusion='CONFIRMED'
            AND rv.basis_kind='OBSERVED'
            AND ((rv.trigger_mode='AUTO' AND rv.automation_run_id=v.automation_run_id)
              OR (rv.trigger_mode='MANUAL' AND rv.actor_id=v.actor_id
                  AND rv.created_at=CAST(EXTRACT(EPOCH FROM v.created_at)*1000 AS BIGINT)))
            """;
    private static final String CASE_SCOPE = """
            rc.target_id=a.target_id AND rc.owner_org_id=a.owner_org_id AND rc.district_id=a.district_id
            AND rc.source_mode=a.source_mode AND e.owner_org_id=a.owner_org_id AND e.district_id=a.district_id
            """;
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.of("Asia/Shanghai"));
    /** 只补最近两分钟内的触发：画面是“当时”的才有意义，停机很久后补拍的不是当时画面。 */
    private static final long LOOKBACK_MS = 120_000;
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final TargetVideoService video;
    private final EvidenceAssociationService evidence;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final AppClock clock;
    private final boolean enabled;
    private final String ffmpeg;
    private final int clipSeconds;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "eo-auto-capture");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public EoAutoCaptureService(JdbcTemplate jdbc, TargetVideoService video, EvidenceAssociationService evidence,
                                AuditService audit, TransactionTemplate tx, AppClock clock,
                                @Value("${app.video.auto-capture.enabled:true}") boolean enabled,
                                @Value("${app.video.auto-capture.ffmpeg-path:ffmpeg}") String ffmpeg,
                                @Value("${app.video.auto-capture.clip-seconds:30}") int clipSeconds) {
        this.jdbc = jdbc; this.video = video; this.evidence = evidence; this.audit = audit; this.tx = tx;
        this.clock = clock; this.enabled = enabled; this.ffmpeg = resolve(ffmpeg);
        this.clipSeconds = Math.max(5, Math.min(clipSeconds, 60));
    }

    record Trigger(String triggerId, String kind, String eventId, String targetId, String authorizationId,
                   String riskCaseId, String basisKind) {
        Trigger(String id, String kind, String event, String target, String authorization) {
            this(id, kind, event, target, authorization, null, null);
        }
        String objectType() { return RISK.equals(kind) ? "uav_risk_case_verification"
                : ALARM.equals(kind) ? "uav_event_verification" : "disposal_authorization_event"; }
        String label() { return RISK.equals(kind) ? "风险核实属实" : ALARM.equals(kind) ? "告警核实属实" : "反制开始"; }
        String context() { return riskCaseId == null ? "" : "; risk_case_id=" + riskCaseId + "; basis_kind=" + basisKind; }
        String key() { return objectType() + ":" + triggerId; }
    }

    @Scheduled(fixedDelayString = "${app.video.auto-capture.poll-millis:5000}",
            initialDelayString = "${app.video.auto-capture.initial-delay-millis:15000}")
    public void poll() {
        if (!enabled) return;
        try {
            for (Trigger trigger : pending()) {
                if (inFlight.add(trigger.key())) {
                    try { handle(trigger); } finally { inFlight.remove(trigger.key()); }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("自动截图录像轮询失败：{}", ex.getMessage());
        }
    }

    List<Trigger> pending() {
        java.time.OffsetDateTime since = Instant.ofEpochMilli(clock.nowMillis() - LOOKBACK_MS).atOffset(java.time.ZoneOffset.UTC);
        List<Trigger> risks = jdbc.query("""
                SELECT rv.verification_id,rv.risk_case_id,rv.basis_kind,rv.event_id,
                       COALESCE((SELECT c.current_target_id FROM target_current_alias c WHERE c.historical_target_id=a.target_id),a.target_id) AS target_id
                FROM uav_risk_case_verification rv JOIN uav_risk_case rc ON rc.risk_case_id=rv.risk_case_id
                JOIN uav_risk_case_member rm ON rm.risk_case_id=rv.risk_case_id AND rm.event_id=rv.event_id
                JOIN uav_event e ON e.event_id=rv.event_id JOIN alarm a ON a.alarm_id=e.alarm_id
                WHERE rv.conclusion='CONFIRMED' AND rv.created_at>=? AND a.target_id IS NOT NULL AND
                """ + CASE_SCOPE + """
                  AND NOT EXISTS (SELECT 1 FROM audit_log l WHERE l.object_type='uav_risk_case_verification' AND l.object_id=rv.verification_id AND l.action=?)
                  AND NOT EXISTS (SELECT 1 FROM uav_event_verification v JOIN audit_log l
                    ON l.object_type='uav_event_verification' AND l.object_id=v.history_id AND l.action=?
                    WHERE
                """ + SAME_VERIFICATION + ") ORDER BY rv.created_at,rv.verification_id LIMIT 20",
                (rs,i) -> new Trigger(rs.getString("verification_id"),RISK,rs.getString("event_id"),rs.getString("target_id"),null,
                        rs.getString("risk_case_id"),rs.getString("basis_kind")), since.toInstant().toEpochMilli(),ACTION,ACTION);
        List<Trigger> alarms = jdbc.query("""
                SELECT v.history_id, e.event_id,
                       COALESCE((SELECT c.current_target_id FROM target_current_alias c WHERE c.historical_target_id=a.target_id), a.target_id) AS target_id
                FROM uav_event_verification v JOIN uav_event e ON e.event_id=v.event_id JOIN alarm a ON a.alarm_id=e.alarm_id
                WHERE v.resulting_state='CONFIRMED' AND v.previous_state<>'CONFIRMED' AND v.created_at>=? AND a.target_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM audit_log l WHERE l.object_type='uav_event_verification' AND l.object_id=v.history_id AND l.action=?)
                  AND NOT EXISTS (SELECT 1 FROM uav_risk_case_verification rv JOIN uav_risk_case rc ON rc.risk_case_id=rv.risk_case_id
                    JOIN uav_risk_case_member rm ON rm.risk_case_id=rv.risk_case_id AND rm.event_id=rv.event_id
                    WHERE
                """ + CASE_SCOPE + " AND " + SAME_VERIFICATION + ") ORDER BY v.created_at,v.history_id LIMIT 20",
                (rs, i) -> new Trigger(rs.getString("history_id"), ALARM, rs.getString("event_id"), rs.getString("target_id"), null),
                since, ACTION);
        List<Trigger> counters = jdbc.query("""
                SELECT ev.event_id AS trigger_id, CASE WHEN da.subject_kind='UAV_EVENT' THEN da.subject_id END AS event_id,
                       COALESCE((SELECT c.current_target_id FROM target_current_alias c WHERE c.historical_target_id=da.target_id), da.target_id) AS target_id,
                       da.authorization_id
                FROM disposal_authorization_event ev JOIN disposal_authorization da ON da.authorization_id=ev.authorization_id
                WHERE ev.event_kind='EXECUTE' AND ev.occurred_at>=? AND da.target_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM audit_log l WHERE l.object_type='disposal_authorization_event' AND l.object_id=ev.event_id AND l.action=?)
                ORDER BY ev.occurred_at LIMIT 20""",
                (rs, i) -> new Trigger(rs.getString("trigger_id"), COUNTER, rs.getString("event_id"), rs.getString("target_id"),
                        rs.getString("authorization_id")),
                since, ACTION);
        return java.util.stream.Stream.of(risks,alarms,counters).flatMap(List::stream).toList();
    }

    void handle(Trigger trigger) {
        TargetVideoService.AutoCaptureSource source = source(trigger.targetId());
        String outcome = source == null
                ? trigger.label() + "：当时没有光电视频画面，未能自动截图录像。"
                : trigger.label() + "：开始自动截图并录像 " + clipSeconds + " 秒（光电设备 " + source.source().deviceId() + "）。";
        tx.executeWithoutResult(status -> audit.record(null, "AUTO_EVIDENCE", "SYSTEM", "evidence", ACTION,
                trigger.objectType(), trigger.triggerId(), outcome + " 目标=" + trigger.targetId()
                        + (trigger.eventId() == null ? "" : "; 事件=" + trigger.eventId())
                        + (trigger.authorizationId() == null ? "" : "; 授权=" + trigger.authorizationId()) + trigger.context(),
                source == null ? "SKIPPED" : "SUCCESS", "", ""));
        if (source == null) return;
        Instant startedAt = Instant.ofEpochMilli(clock.nowMillis());
        workers.submit(() -> capture(trigger, source, "EO_STILL", startedAt));
        workers.submit(() -> capture(trigger, source, "EO_VIDEO", startedAt));
    }

    private TargetVideoService.AutoCaptureSource source(String targetId) {
        try { return video.autoCaptureSource(targetId); }
        catch (RuntimeException ex) { return null; }
    }

    private void capture(Trigger trigger, TargetVideoService.AutoCaptureSource from, String kind, Instant startedAt) {
        boolean still = "EO_STILL".equals(kind);
        String name = "自动-" + (RISK.equals(trigger.kind()) ? "风险核实" : ALARM.equals(trigger.kind()) ? "告警核实" : "反制开始")
                + "-" + (still ? "截图-" : "录像-")
                + NAME_TIME.format(startedAt) + (still ? ".jpg" : ".mp4");
        Path file = null;
        try {
            file = Files.createTempFile("eo-auto-", still ? ".jpg" : ".mp4");
            List<String> command = still
                    ? List.of(ffmpeg, "-nostdin", "-loglevel", "error", "-rtsp_transport", "tcp", "-i", from.readUrl(),
                            "-frames:v", "1", "-q:v", "3", "-y", file.toString())
                    : List.of(ffmpeg, "-nostdin", "-loglevel", "error", "-rtsp_transport", "tcp", "-i", from.readUrl(),
                            "-t", Integer.toString(clipSeconds), "-an", "-c:v", "copy", "-movflags", "+faststart",
                            "-f", "mp4", "-y", file.toString());
            run(command, still ? 20 : clipSeconds + 30);
            long size = Files.size(file);
            if (size == 0 || size > MAX_BYTES) throw new IOException("文件大小异常：" + size);
            byte[] content = Files.readAllBytes(file);
            var source = from.source();
            EoCapture capture = new EoCapture(kind, source.targetId(), trigger.eventId(), source.deviceId(), source.taskId(),
                    source.streamId(), source.sourceMode(), startedAt);
            evidence.ingestAutoEoCapture(capture, trigger.authorizationId(), name, content, trigger.kind() + trigger.context());
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("{}自动{}未能保存：{}", trigger.label(), still ? "截图" : "录像", ex.getMessage());
            try {
                tx.executeWithoutResult(status -> audit.record(null, "AUTO_EVIDENCE", "SYSTEM", "evidence", ACTION,
                        trigger.objectType(), trigger.triggerId(), trigger.label() + "：自动" + (still ? "截图" : "录像")
                                + "未能保存（视频中断或取证程序不可用）。", "FAILED", "", ""));
            } catch (RuntimeException ignored) { /* 审计失败不影响后续触发 */ }
        } finally {
            if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        }
    }

    /** 只写了程序名时按 PATH 找；从图形界面启动的服务常拿不到 Homebrew 的 PATH，再看两个常见安装位置。 */
    static String resolve(String configured) {
        if (configured.contains("/") || configured.contains("\\")) return configured;
        String path = System.getenv("PATH");
        List<String> dirs = new java.util.ArrayList<>(path == null ? List.of() : List.of(path.split(java.io.File.pathSeparator)));
        dirs.addAll(List.of("/opt/homebrew/bin", "/usr/local/bin"));
        for (String dir : dirs) {
            Path candidate = Path.of(dir, configured);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return configured;
    }

    /** 拉流地址带只读凭据，进程输出丢弃，异常信息里不带命令行。 */
    private static void run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("取证程序超时");
        }
        if (process.exitValue() != 0) throw new IOException("取证程序退出码 " + process.exitValue());
    }
}
