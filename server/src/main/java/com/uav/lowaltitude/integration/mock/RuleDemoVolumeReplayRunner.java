package com.uav.lowaltitude.integration.mock;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
 * 演示体量回放（决策 15-46）：对 {@link LocalStage7DemoVolumeSeeder} 的两组场景各跑一次 REPLAY，
 * 只为多出一批可看的研判与告警，不设期望、不做回归判定（契约回归仍由 {@link RuleReplayRunner} 负责）。
 * 同数据集 + 同版本已跑过则跳过；评估失败只记日志，不阻断启动。开关与契约回放相同：app.rule-engine.replay.run-on-start。
 */
@Component
@Profile("!production & local")
@ConditionalOnProperty(prefix = "app.dev-seed", name = "enabled", havingValue = "true")
@Order(71)
public class RuleDemoVolumeReplayRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RuleDemoVolumeReplayRunner.class);
    private record Group(String dataset, Instant asOf, List<String> scenarios) { }
    private static final List<Group> GROUPS = List.of(
            new Group("stage7-demo-volume-day", LocalStage7RuleEngineSeeder.T0, LocalStage7DemoVolumeSeeder.DAY_SCENARIOS),
            new Group("stage7-demo-volume-night", LocalStage7DemoVolumeSeeder.T_NIGHT, LocalStage7DemoVolumeSeeder.NIGHT_SCENARIOS));

    private final RuleEngineProperties properties;
    private final RuleEngineRepository engine;
    private final RuleRunService runs;
    private final JdbcTemplate jdbc;

    public RuleDemoVolumeReplayRunner(RuleEngineProperties properties, RuleEngineRepository engine, RuleRunService runs, JdbcTemplate jdbc) {
        this.properties = properties; this.engine = engine; this.runs = runs; this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getReplay().isRunOnStart()) return;
        RuleSetRow set = engine.findRuleSetByCode(LocalStage7RuleEngineSeeder.RULE_SET_CODE);
        if (set == null || set.activeVersionId() == null) { log.info("demo volume replay skipped: no active version"); return; }
        for (Group group : GROUPS) {
            if (replayExists(group.dataset(), set.activeVersionId())) { log.info("demo volume replay skipped: {} already replayed", group.dataset()); continue; }
            OffsetDateTime asOf = group.asOf().atOffset(ZoneOffset.UTC);
            RunHandle run = runs.start(LocalStage7RuleEngineSeeder.RULE_SET_CODE, RunMode.ACTIVE, "REPLAY", group.dataset(), null, asOf);
            List<Subject> subjects = group.scenarios().stream()
                    .map(s -> new Subject(SubjectKind.TARGET, LocalStage7DemoVolumeSeeder.targetId(s), null, null, null)).toList();
            RunSummary summary = runs.runBatch(run, subjects, asOf);
            if (summary.errors().isEmpty()) log.info("demo volume replay {}: evaluated {}", group.dataset(), summary.evaluatedCount());
            else log.warn("demo volume replay {}: evaluated {}, errors {}", group.dataset(), summary.evaluatedCount(), summary.errors());
        }
    }

    private boolean replayExists(String dataset, String versionId) {
        Integer count = jdbc.queryForObject("select count(*) from rule_run where trigger_kind='REPLAY' and replay_dataset_code=? and rule_set_version_id=?",
                Integer.class, dataset, versionId);
        return count != null && count > 0;
    }
}
