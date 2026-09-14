package com.uav.lowaltitude.integration.mock;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.RunMode;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.Subject;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SubjectKind;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineProperties;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository;
import com.uav.lowaltitude.modules.assessment.engine.RuleEngineRepository.RuleSetRow;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService.RunHandle;
import com.uav.lowaltitude.modules.assessment.engine.RuleRunService.RunSummary;

/**
 * 回放回归：以 REPLAY 模式对 LocalStage7RuleEngineSeeder 的十个场景运行 LEGALITY-DEMO 当前 ACTIVE 版本，
 * 逐场景比对契约期望的四态与原因码，不符即抛异常（run-on-start 打开时启动失败）。
 * 已有同数据集 + 同版本的 rule_run 则跳过（PostgreSQL 上还有部分唯一索引兜底），不会覆盖任何 legality_review。
 * 只在非 production 注册；启动时是否执行由 app.rule-engine.replay.run-on-start（默认 false）决定，测试直接调用 {@link #replay()}。
 */
@Component
@Profile("!production")
@Order(70)
public class RuleReplayRunner implements ApplicationRunner {
    public static final String DATASET = "stage7-demo";
    private static final Logger log = LoggerFactory.getLogger(RuleReplayRunner.class);
    /** 契约十个场景的期望：四态、必须出现的违规原因码、必须出现的未知原因码。 */
    static final List<Expectation> EXPECTATIONS = List.of(
            new Expectation("legal", "LEGAL", List.of(), List.of()),
            new Expectation("deviation", "ABNORMAL", List.of("ROUTE_DEVIATION"), List.of()),
            new Expectation("airspace-limit", "ILLEGAL", List.of("AIRSPACE_ALTITUDE_EXCEEDED"), List.of()),
            new Expectation("plan-altitude", "ABNORMAL", List.of("PLAN_ALTITUDE_EXCEEDED"), List.of()),
            new Expectation("boundary", "UNDETERMINED", List.of(), List.of("BOUNDARY_POLICY_UNKNOWN")),
            new Expectation("no-plan", "ILLEGAL", List.of("NO_AUTHORIZATION"), List.of()),
            new Expectation("degraded", "UNDETERMINED", List.of(), List.of("LOW_CONFIDENCE", "TRACK_BRIDGED")),
            new Expectation("datum-mismatch", "UNDETERMINED", List.of(), List.of("ALTITUDE_DATUM_OR_RANGE_UNKNOWN")),
            new Expectation("merge", "ILLEGAL", List.of(), List.of()),
            new Expectation("cross-scope", "ILLEGAL", List.of("NO_AUTHORIZATION"), List.of()));

    private final RuleEngineProperties properties;
    private final RuleEngineRepository engine;
    private final RuleRunService runs;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RuleReplayRunner(RuleEngineProperties properties, RuleEngineRepository engine, RuleRunService runs, JdbcTemplate jdbc, ObjectMapper json) {
        this.properties = properties; this.engine = engine; this.runs = runs; this.jdbc = jdbc; this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getReplay().isRunOnStart()) return;
        ReplayReport report = replay();
        if (!report.mismatches().isEmpty()) throw new IllegalStateException("回放回归结论不符: " + String.join("; ", report.mismatches()));
    }

    /**
     * 执行一次回放并返回报告；结论不符时同样抛 IllegalStateException（调用方无需再判断）。
     * 种子未运行（没有规则集或没有 ACTIVE 版本）时返回 skipped，不伪造运行记录。
     */
    public ReplayReport replay() {
        RuleSetRow set = engine.findRuleSetByCode(LocalStage7RuleEngineSeeder.RULE_SET_CODE);
        if (set == null || set.activeVersionId() == null) {
            log.info("rule replay skipped: rule set {} has no active version", LocalStage7RuleEngineSeeder.RULE_SET_CODE);
            return new ReplayReport(null, true, 0, List.of());
        }
        if (replayExists(set.activeVersionId())) {
            log.info("rule replay skipped: dataset {} already replayed on version {}", DATASET, set.activeVersionId());
            return new ReplayReport(null, true, 0, List.of());
        }
        OffsetDateTime asOf = LocalStage7RuleEngineSeeder.T0.atOffset(ZoneOffset.UTC);
        RunHandle run = runs.start(LocalStage7RuleEngineSeeder.RULE_SET_CODE, RunMode.ACTIVE, "REPLAY", DATASET, null, asOf);
        List<Subject> subjects = new ArrayList<>();
        for (Expectation e : EXPECTATIONS) subjects.add(subject(e.scenario()));
        // merge 场景第二次评估：同一目标同一 as_of 落在去重窗内，只应合并不应再建告警。
        subjects.add(subject("merge"));
        RunSummary summary = runs.runBatch(run, subjects, asOf);
        List<String> mismatches = verify(run.runId());
        if (!summary.errors().isEmpty()) mismatches.add("评估失败: " + String.join("; ", summary.errors()));
        if (!mismatches.isEmpty()) throw new IllegalStateException("回放回归结论不符: " + String.join("; ", mismatches));
        return new ReplayReport(run.runId(), false, summary.evaluatedCount(), List.of());
    }

    private boolean replayExists(String versionId) {
        Integer count = jdbc.queryForObject("select count(*) from rule_run where trigger_kind='REPLAY' and replay_dataset_code=? and rule_set_version_id=?",
                Integer.class, DATASET, versionId);
        return count != null && count > 0;
    }

    private List<String> verify(String runId) {
        List<String> mismatches = new ArrayList<>();
        for (Expectation e : EXPECTATIONS) {
            List<Map<String, Object>> rows = jdbc.queryForList("select legal_status,violation_reasons,unknown_reasons from rule_evaluation where run_id=? and target_id=? order by evaluated_at desc,evaluation_id desc",
                    runId, LocalStage7RuleEngineSeeder.targetId(e.scenario()));
            if (rows.isEmpty()) { mismatches.add(e.scenario() + ": 没有研判"); continue; }
            Map<String, Object> row = rows.get(0);
            String status = String.valueOf(row.get("legal_status"));
            if (!e.legalStatus().equals(status)) mismatches.add(e.scenario() + ": 期望 " + e.legalStatus() + " 实际 " + status);
            List<String> violations = strings(row.get("violation_reasons")), unknowns = strings(row.get("unknown_reasons"));
            for (String reason : e.violationReasons()) if (!violations.contains(reason)) mismatches.add(e.scenario() + ": 缺少违规原因 " + reason + " 实际 " + violations);
            for (String reason : e.unknownReasons()) if (!unknowns.contains(reason)) mismatches.add(e.scenario() + ": 缺少未知原因 " + reason + " 实际 " + unknowns);
        }
        // merge：同目标两次 ILLEGAL 只允许一条告警，第二次进入合并组。
        Integer alarms = jdbc.queryForObject("select count(*) from alarm where target_id=?", Integer.class, LocalStage7RuleEngineSeeder.targetId("merge"));
        if (alarms == null || alarms != 1) mismatches.add("merge: 期望恰一条告警，实际 " + alarms);
        Integer merged = jdbc.queryForObject("select count(*) from alarm_merge_member m join rule_evaluation e on e.evaluation_id=m.evaluation_id where e.run_id=? and e.target_id=? and m.member_kind='MERGED'",
                Integer.class, runId, LocalStage7RuleEngineSeeder.targetId("merge"));
        if (merged == null || merged != 1) mismatches.add("merge: 期望一条 MERGED 成员，实际 " + merged);
        return mismatches;
    }

    /** JSON 列在 H2 上回读为 byte[]（PostgreSQL 为 PGobject/String）：先还原成文本再解析。 */
    private List<String> strings(Object stored) {
        List<String> output = new ArrayList<>();
        if (stored == null) return output;
        try {
            String text = stored instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(stored);
            JsonNode node = json.readTree(text);
            if (node.isTextual()) node = json.readTree(node.textValue());
            for (JsonNode item : node) if (item.isTextual()) output.add(item.textValue());
        } catch (Exception ex) {
            output.add("<unreadable>");
        }
        return output;
    }

    private static Subject subject(String scenario) {
        return new Subject(SubjectKind.TARGET, LocalStage7RuleEngineSeeder.targetId(scenario), null, null, null);
    }

    record Expectation(String scenario, String legalStatus, List<String> violationReasons, List<String> unknownReasons) { }

    /** skipped=true 表示没有新运行（无 ACTIVE 版本或同数据集已回放）。 */
    public record ReplayReport(String runId, boolean skipped, int evaluatedCount, List<String> mismatches) { }
}
