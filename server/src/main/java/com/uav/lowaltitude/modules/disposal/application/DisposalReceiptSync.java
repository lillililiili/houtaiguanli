package com.uav.lowaltitude.modules.disposal.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.disposal.domain.DisposalRules;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 把协作者 A 的 device_command 状态同步成处置授权的结局：SUCCEEDED→COMPLETED、FAILED/TIMED_OUT→FAILED。
 *
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
    private final boolean scheduledEnabled;

    public DisposalReceiptSync(DisposalRepository repository, DeviceRepository devices, AppClock clock,
            ObjectMapper json, DisposalJammingChain jammingChain,
            @Value("${app.disposal.receipt-sync.enabled:false}") boolean scheduledEnabled) {
        this.repository = repository; this.devices = devices; this.clock = clock; this.json = json;
        this.jammingChain = jammingChain;
        this.scheduledEnabled = scheduledEnabled;
    }

    /** 读时同步：只处理这一条，避免打开一个详情页就扫全表。 */
    @Transactional
    public void syncOne(AuthorizationRow row) {
        if (row == null || !DisposalRules.EXECUTING.equals(row.status()) || row.executionCommandId() == null) return;
        apply(row);
    }

    /** 定时兜底；缺省关闭，local/test 打开（生产是否开由部署决定）。 */
    @Scheduled(fixedDelayString = "${app.disposal.receipt-sync.interval-millis:15000}")
    @Transactional
    public void sweep() {
        if (!scheduledEnabled) return;
        for (AuthorizationRow row : repository.awaitingReceipt(BATCH)) apply(row);
    }

    private void apply(AuthorizationRow row) {
        Map<String, Object> command = devices.findCommand(row.executionCommandId());
        if (command == null) return;
        String commandStatus = String.valueOf(command.get("status"));
        final String next;
        if (SUCCESS.contains(commandStatus)) next = DisposalRules.COMPLETED;
        else if (FAILURE.contains(commandStatus)) next = DisposalRules.FAILED;
        else return;   // 仍在途：不猜结局。
        OffsetDateTime at = clock.now().atOffset(ZoneOffset.UTC);
        String resultCode = text(command.get("result_code"));
        String resultDetail = text(command.get("result_detail"));
        // 按当前状态而不是版本号做乐观并发：这条迁移由系统推进，没有哪个调用方持有版本号。
        // 返回 0 说明有人刚把它停了或撤了——那是人的决定，系统不覆盖。
        if (repository.transitionFromStatus(row.authorizationId(), DisposalRules.EXECUTING, next, at,
                resultCode == null ? "DEVICE_" + commandStatus : resultCode, resultDetail) != 1) return;
        repository.insertEvent(UUID.randomUUID().toString(), row.authorizationId(), "RECEIPT", null,
                "设备回执：" + commandStatus, write(Map.of("status", next, "command_status", commandStatus,
                        "command_id", row.executionCommandId())), at);
        repository.insertEvent(UUID.randomUUID().toString(), row.authorizationId(),
                DisposalRules.COMPLETED.equals(next) ? "COMPLETE" : "FAIL", null, null,
                write(Map.of("status", next)), at);
        if (DisposalRules.COMPLETED.equals(next) && DisposalRules.COUNTERMEASURE.equals(row.actionType())) {
            jammingChain.scheduleAfterComplete(row.authorizationId());
        }
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("cannot serialize receipt snapshot", ex); }
    }
}
