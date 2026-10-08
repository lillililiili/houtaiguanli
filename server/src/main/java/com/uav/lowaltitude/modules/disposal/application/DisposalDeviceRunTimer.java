package com.uav.lowaltitude.modules.disposal.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.device.DeviceProtocolCodes;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.RunRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 反制设备到时自动全部关闭（2026-10-08 验收预跑 3-9 / 3-6，新-20）。
 *
 * 四通道反制设备回“已打开”后授权一直是反制中（见 {@link DisposalReceiptSync}）。到了策略里的 device_run_seconds，
 * 这里以最近一次下发它的人的名义发“全部关闭”；设备回“成功”后由回执同步把授权记为完成，再照常自动移送处罚。
 * 转干扰的来源反制不单独关：干扰那条仍在执行中时，由干扰到时关闭，两条一起完成。
 *
 * 关闭没成功（设备回失败、超时，或下发被拒）时下一轮再试，试满 max-off-attempts 次仍不成功就停下来，
 * 记一条“设备可能还开着”，授权保持反制中：设备停没停只能由人按急停或到现场确认，系统不替人认定。
 *
 * 每条运行记录各用一个事务，锁序与急停相同：事件→授权→设备→指令。急停已停掉的授权不再自动关闭。
 *
 * 反制还开着却没接上信号干扰的（设备打开那一刻依据一时不满足，或干扰没发出去），每轮补接一次，
 * 见 {@link DisposalJammingChain#retryWhileOn}（2026-10-08 第二批复验）。
 */
@Component
public class DisposalDeviceRunTimer {
    private static final Logger log = LoggerFactory.getLogger(DisposalDeviceRunTimer.class);
    private static final int BATCH = 50;
    private static final Set<String> IN_FLIGHT = Set.of("QUEUED", "SENT", "ACCEPTED");

    private final DisposalRepository repository;
    private final EmergencyStopRepository stops;
    private final DeviceRepository devices;
    private final DisposalExecutionGateway gateway;
    private final DisposalReceiptSync receipts;
    private final AppClock clock;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final DisposalJammingChain jammingChain;
    private final boolean enabled;
    private final int maxAttempts;

    public DisposalDeviceRunTimer(DisposalRepository repository, EmergencyStopRepository stops, DeviceRepository devices,
            DisposalExecutionGateway gateway, DisposalReceiptSync receipts, AppClock clock, ObjectMapper json,
            PlatformTransactionManager transactions, DisposalJammingChain jammingChain,
            @Value("${app.disposal.device-run.enabled:true}") boolean enabled,
            @Value("${app.disposal.device-run.max-off-attempts:3}") int maxAttempts) {
        this.repository = repository; this.stops = stops; this.devices = devices; this.gateway = gateway;
        this.receipts = receipts; this.clock = clock; this.json = json; this.jammingChain = jammingChain;
        this.tx = new TransactionTemplate(transactions);
        this.enabled = enabled;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /**
     * 缺省打开：这不是兜底，是设备到时关掉的唯一途径，关着就等于设备一直开着。
     * 用例里关掉，由用例直接调 {@link #tick()}。
     */
    @Scheduled(fixedDelayString = "${app.disposal.device-run.poll-millis:5000}")
    public void scheduledTick() {
        if (enabled) tick();
    }

    /** 处理一轮到时的运行记录，返回处理了几条。一条出错不耽误别的。还开着却没接上干扰的，先补接一次。 */
    public int tick() {
        for (String parent : repository.runsAwaitingJamming(now(), BATCH)) {
            try {
                tx.executeWithoutResult(status -> jammingChain.retryWhileOn(parent));
            } catch (RuntimeException ex) {
                log.warn("auto jamming of authorization {} was not retried this round: {}", parent, ex.toString());
            }
        }
        int handled = 0;
        for (RunRow run : repository.dueRuns(now(), BATCH)) {
            try {
                tx.executeWithoutResult(status -> close(run.authorizationId()));
                handled++;
            } catch (RuntimeException ex) {
                log.warn("device run of authorization {} was not closed this round: {}", run.authorizationId(), ex.toString());
            }
        }
        return handled;
    }

    void close(String authorizationId) {
        AuthorizationRow seen = repository.findUnlocked(authorizationId);
        if (seen == null || !DisposalRules.EXECUTING.equals(seen.status())) return;
        if ("UAV_EVENT".equals(seen.subjectKind())) stops.lockEvent(seen.subjectId());
        AuthorizationRow row = repository.lockForSystem(authorizationId);
        // 等锁期间可能已被急停或回执结案：只关仍在反制中的。
        if (row == null || !DisposalRules.EXECUTING.equals(row.status())) return;
        RunRow run = repository.run(authorizationId);
        OffsetDateTime now = now();
        if (run == null || run.gaveUpAt() != null || run.offDueAt().isAfter(now)) return;
        // 已转为信号干扰且干扰仍在执行中：设备由干扰那条到时一起关。
        if (repository.executingChild(authorizationId)) return;
        if (run.offCommandId() != null) {
            Map<String, Object> command = devices.findCommand(run.offCommandId());
            String status = command == null ? null : String.valueOf(command.get("status"));
            if (status != null && IN_FLIGHT.contains(status)) return; // 等设备回。
            if ("SUCCEEDED".equals(status)) { receipts.syncOne(row); return; } // 回执还没同步，这里补上。
            boolean last = run.offAttempts() >= maxAttempts;
            event(row, "RECEIPT", "自动全部关闭没有成功（设备" + outcome(status) + "），设备可能还开着"
                    + (last ? "" : "；系统马上再试一次，也可以随时急停"), snapshot(run, run.offCommandId()), now);
            now = now.plusNanos(1_000_000); // 同一事务里的下一条事件晚一毫秒，事件列表次序才确定（决策 19-8）。
        }
        if (run.offAttempts() >= maxAttempts) {
            giveUp(row, run, "自动全部关闭试了 " + run.offAttempts() + " 次都没成功，设备可能还开着，请按急停或到现场关闭设备", now);
            return;
        }
        issue(row, run, now);
    }

    private void issue(AuthorizationRow row, RunRow run, OffsetDateTime now) {
        String id = row.authorizationId();
        Map<String, Object> device = devices.find(run.deviceId());
        if (device == null || !"live".equals(text(device, "source_mode"))
                || !DeviceProtocolCodes.COUNTERMEASURE_TCP_4CH_V2_0.equals(text(device, "protocol_code"))) {
            giveUp(row, run, "这台设备不能远程全部关闭，设备可能还开着，请按急停或到现场关闭设备", now);
            return;
        }
        String actorId = repository.latestExecuteActor(id);
        AuthUser executor = repository.actor(actorId == null ? row.requestedBy() : actorId);
        if (executor == null) {
            giveUp(row, run, "找不到下发这次反制的账号，系统没法自动全部关闭；设备可能还开着，请按急停或到现场关闭设备", now);
            return;
        }
        int attempt = run.offAttempts() + 1;
        stops.lockDevice(run.deviceId());
        DisposalExecutionGateway.Result result = gateway.stop4ch(executor, run.deviceId(),
                "disposal-auto-off-" + id + "-" + attempt, id, "反制已满设定时长，系统自动全部关闭");
        if (result instanceof DisposalExecutionGateway.Accepted accepted) {
            repository.recordOffAttempt(id, accepted.commandId(), now);
            // 授权跟着关闭指令走：设备回“已关闭”时回执同步按这条指令把授权记为完成。持锁写入，不会被别人改过。
            if (repository.transition(id, row.version(), DisposalRules.EXECUTING, now, accepted.commandId(), null, null) != 1)
                throw new IllegalStateException("authorization " + id + " changed while holding its lock");
            event(row, "DEVICE_ALL_OFF_ISSUED", attempt == 1 ? "反制已满设定时长，系统自动下发全部关闭"
                    : "系统再次自动下发全部关闭（第 " + attempt + " 次）", snapshot(run, accepted.commandId()), now);
            return;
        }
        DisposalExecutionGateway.Rejected rejected = (DisposalExecutionGateway.Rejected) result;
        repository.recordOffAttempt(id, null, now);
        event(row, rejected.eventKind(), "自动全部关闭没有下发：" + rejected.detail()
                + (attempt >= maxAttempts ? "" : "；系统稍后再试，也可以随时急停"), snapshot(run, null), now);
    }

    private void giveUp(AuthorizationRow row, RunRow run, String note, OffsetDateTime now) {
        repository.giveUpRun(row.authorizationId(), now);
        event(row, "RECEIPT", note, snapshot(run, run.offCommandId()), now);
        log.warn("authorization {} stays EXECUTING: device {} may still be on ({})", row.authorizationId(), run.deviceId(), note);
    }

    private void event(AuthorizationRow row, String kind, String note, Map<String, Object> snapshot, OffsetDateTime at) {
        repository.insertEvent(UUID.randomUUID().toString(), row.authorizationId(), kind, null, note, write(snapshot), at);
    }

    private static Map<String, Object> snapshot(RunRow run, String commandId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", DisposalRules.EXECUTING);
        snapshot.put("device_id", run.deviceId());
        if (commandId != null) snapshot.put("command_id", commandId);
        snapshot.put("source", "RUN_DURATION_REACHED");
        snapshot.put("off_due_at", run.offDueAt().toInstant().toEpochMilli());
        return snapshot;
    }

    private static String outcome(String status) {
        if ("TIMED_OUT".equals(status)) return "超时没有回应";
        if ("CANCELLED".equals(status)) return "指令被取消";
        if ("FAILED".equals(status)) return "回执：失败";
        return "没有回执";
    }

    private OffsetDateTime now() { return clock.now().atOffset(ZoneOffset.UTC); }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize disposal event snapshot", ex); }
    }
}
