package com.uav.lowaltitude.modules.disposal.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.handoff.application.HandoffSubmissionService;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalPolicyRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.RunRow;
import com.uav.lowaltitude.modules.disposal.infrastructure.EmergencyStopRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 把协作者 A 的 device_command 状态同步成处置授权的结局：SUCCEEDED→COMPLETED、FAILED/TIMED_OUT→FAILED。
 *
 * 四通道反制设备例外（2026-10-08 验收预跑 3-9 / 3-6，新-20）：启动指令（迫降 0x0F、驱离 0x0D）回“成功”只说明设备打开了，
 * 授权保持执行中（反制中）并记一条运行记录，到时由 {@link DisposalDeviceRunTimer} 下发全部关闭；
 * 全部关闭（0x00）回“成功”才把授权记为完成，转干扰的来源反制随干扰一起完成。
 *
 * 设备指令进入终态后立即结案。读时同步和定时扫描只兜住没发出完成事件的旧指令。
 * 采用"读时同步 + 定时兜底"（简报第 4 步二选一，选这个并记理由）：
 * - 读时同步保证有人真正在看这条授权时，看到的就是最新结局，不依赖任何调度是否开着；
 * - 定时兜底负责没人看的那些（例如夜里执行完、第二天才有人查），否则它们会一直挂在 EXECUTING。
 * 只做其一都不够：只靠定时，调度一关（生产缺省关）就永远不收敛；只靠读时，没人看的授权永远不结案。
 *
 * 直接读 A 的仓库而不是 DeviceService.command(id)：后者要求调用者持有 monitoring:read，
 * 而同步是系统行为不是用户行为——定时任务根本没有登录身份，读时同步也不该逼着
 * 只有 disposal:read 的人再去申请一个设备权限才能看到自己那条授权的结果。
 */
@Service
public class DisposalReceiptSync {
    private static final int BATCH = 200;
    private static final List<String> SUCCESS = List.of("SUCCEEDED");
    private static final List<String> FAILURE = List.of("FAILED", "TIMED_OUT", "CANCELLED");

    private final DisposalRepository repository;
    private final DeviceRepository devices;
    private final AppClock clock;
    private final ObjectMapper json;
    private final DisposalJammingChain jammingChain;
    private final HandoffSubmissionService handoffs;
    private final DisposalPolicyRepository policies;
    private final EmergencyStopRepository stops;
    private final boolean scheduledEnabled;

    public DisposalReceiptSync(DisposalRepository repository, DeviceRepository devices, AppClock clock,
            ObjectMapper json, DisposalJammingChain jammingChain, HandoffSubmissionService handoffs,
            DisposalPolicyRepository policies, EmergencyStopRepository stops,
            @Value("${app.disposal.receipt-sync.enabled:false}") boolean scheduledEnabled) {
        this.repository = repository; this.devices = devices; this.clock = clock; this.json = json;
        this.jammingChain = jammingChain;
        this.handoffs = handoffs;
        this.policies = policies;
        this.stops = stops;
        this.scheduledEnabled = scheduledEnabled;
    }

    /** 读时同步：只处理这一条，避免打开一个详情页就扫全表。 */
    @Transactional
    public void syncOne(AuthorizationRow row) {
        if (row == null || !DisposalRules.EXECUTING.equals(row.status()) || row.executionCommandId() == null) return;
        apply(row);
    }

    /** 设备指令刚进入终态时结案。调用时指令状态必须已经写入。 */
    @Transactional
    public void syncByCommand(String commandId) {
        if (commandId == null || commandId.isBlank()) return;
        apply(repository.findExecutingByCommand(commandId));
    }

    /** 定时兜底；缺省关闭，local/test 打开（生产是否开由部署决定）。 */
    @Scheduled(fixedDelayString = "${app.disposal.receipt-sync.interval-millis:15000}")
    @Transactional
    public void sweep() {
        if (!scheduledEnabled) return;
        for (AuthorizationRow row : repository.awaitingReceipt(BATCH)) apply(row);
    }

    private void apply(AuthorizationRow row) {
        if (row == null) return; // Standalone device commands have no disposal authorization to settle.
        Map<String, Object> command = devices.findCommand(row.executionCommandId());
        if (command == null) return;
        String commandStatus = String.valueOf(command.get("status"));
        final String next;
        if (SUCCESS.contains(commandStatus)) next = DisposalRules.COMPLETED;
        else if (FAILURE.contains(commandStatus)) next = DisposalRules.FAILED;
        else return;   // 仍在途：不猜结局。
        Integer mask = DisposalRules.COUNTERMEASURE_4CH.equals(row.channel())
                ? repository.relayMask(row.executionCommandId()) : null;
        boolean relayOn = mask != null && mask != 0;
        boolean allOff = mask != null && mask == 0;
        // 设备打开了还不算完成：一直算反制中，到时由系统全部关闭（新-20）。
        if (relayOn && DisposalRules.COMPLETED.equals(next)) { deviceOn(row, command); return; }
        // 自动全部关闭没有成功：授权仍是反制中，由 DisposalDeviceRunTimer 重试，试满再交给人急停。
        if (allOff && DisposalRules.FAILED.equals(next)) return;
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        String resultCode = text(command.get("result_code"));
        String resultDetail = text(command.get("result_detail"));
        // 按当前状态而不是版本号做乐观并发：这条迁移由系统推进，没有哪个调用方持有版本号。
        // 返回 0 说明有人刚把它停了或撤了——那是人的决定，系统不覆盖。
        if (repository.transitionFromStatus(row.authorizationId(), DisposalRules.EXECUTING, next, at,
                resultCode == null ? "DEVICE_" + commandStatus : resultCode, resultDetail) != 1) return;
        repository.insertEvent(UUID.randomUUID().toString(), row.authorizationId(), "RECEIPT", null,
                allOff ? "设备回执：已全部关闭" : "设备回执：" + commandStatus,
                write(Map.of("status", next, "command_status", commandStatus,
                        "command_id", row.executionCommandId())), at);
        repository.insertEvent(UUID.randomUUID().toString(), row.authorizationId(),
                DisposalRules.COMPLETED.equals(next) ? "COMPLETE" : "FAIL", null, null,
                write(Map.of("status", next)), at);
        // 转干扰的来源反制和干扰开的是同一台设备，设备关了它也一起完成。
        if (allOff && DisposalRules.JAMMING.equals(row.actionType())) completeSourceCounter(row, command, at);
        // 四通道的干扰在设备打开时就接上了（deviceOn），关了以后不再接。
        if (!allOff && DisposalRules.COMPLETED.equals(next) && DisposalRules.COUNTERMEASURE.equals(row.actionType())) {
            jammingChain.scheduleAfterComplete(row.authorizationId());
        }
        if (DisposalRules.COMPLETED.equals(next) && DisposalRules.JAMMING.equals(row.actionType())) {
            handoffs.automaticAfterJamming(row.subjectId());
        }
    }

    /**
     * 启动指令回“成功”：设备打开了。授权保持执行中，记下该在什么时候全部关闭。
     * 转干扰的那条沿用来源反制的关闭时刻，两条一起关；反制打开后立即接上信号干扰（与以前接续的时机相同）。
     */
    private void deviceOn(AuthorizationRow seen, Map<String, Object> command) {
        // 已经记过（读时同步、定时兜底大多走到这里就结束），不必加锁。
        if (repository.run(seen.authorizationId()) != null) return;
        // 回执监听和定时兜底可能同时到这里：锁住授权再确认一遍，运行记录只记一次。
        AuthorizationRow row = repository.lockForSystem(seen.authorizationId());
        if (row == null || !DisposalRules.EXECUTING.equals(row.status())
                || !Objects.equals(row.executionCommandId(), seen.executionCommandId())) return;
        if (repository.run(row.authorizationId()) != null) return;
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        OffsetDateTime onAt = command.get("completed_at") instanceof Number done
                ? Instant.ofEpochMilli(done.longValue()).atOffset(ZoneOffset.UTC) : at;
        RunRow source = DisposalRules.JAMMING.equals(row.actionType()) ? repository.run(stops.parent(row.authorizationId())) : null;
        int seconds = source == null ? Math.max(0, policies.active().deviceRunSeconds()) : 0;
        OffsetDateTime due = source == null ? onAt.plusSeconds(seconds) : source.offDueAt();
        if (due.isBefore(onAt)) due = onAt; // 来源反制的关闭时刻已经过了：干扰一打开就关。
        if (!repository.startRun(row.authorizationId(), row.deviceId(), row.executionCommandId(), onAt, due, at)) return;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", DisposalRules.EXECUTING);
        snapshot.put("command_status", String.valueOf(command.get("status")));
        snapshot.put("command_id", row.executionCommandId());
        snapshot.put("device_on", true);
        snapshot.put("off_due_at", due.toInstant().toEpochMilli());
        repository.insertEvent(UUID.randomUUID().toString(), row.authorizationId(), "RECEIPT", null,
                source == null ? "设备回执：已打开，反制中；满 " + seconds + " 秒系统自动全部关闭，也可以随时急停"
                        : "设备回执：已转为信号干扰，和来源反制一起到时自动全部关闭，也可以随时急停",
                write(snapshot), at);
        if (DisposalRules.COUNTERMEASURE.equals(row.actionType())) jammingChain.scheduleAfterDeviceOn(row.authorizationId());
    }

    /** 设备已全部关闭：接出这条干扰的来源反制仍在执行中时，一起记完成。 */
    private void completeSourceCounter(AuthorizationRow jamming, Map<String, Object> command, OffsetDateTime at) {
        String parentId = stops.parent(jamming.authorizationId());
        if (parentId == null) return;
        String resultCode = text(command.get("result_code"));
        if (repository.transitionFromStatus(parentId, DisposalRules.EXECUTING, DisposalRules.COMPLETED, at,
                resultCode == null ? "DEVICE_SUCCEEDED" : resultCode, text(command.get("result_detail"))) != 1) return;
        repository.insertEvent(UUID.randomUUID().toString(), parentId, "RECEIPT", null,
                "设备回执：已全部关闭（随信号干扰 " + jamming.authorizationNo() + " 一起关闭）",
                write(Map.of("status", DisposalRules.COMPLETED, "command_status", String.valueOf(command.get("status")),
                        "command_id", jamming.executionCommandId())), at);
        repository.insertEvent(UUID.randomUUID().toString(), parentId, "COMPLETE", null, null,
                write(Map.of("status", DisposalRules.COMPLETED)), at);
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize receipt snapshot", ex); }
    }
}
