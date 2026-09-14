package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.uav.lowaltitude.integration.replay.FusionReplayDatasetGenerator;

/** 阶段 8 种子：test profile 下三来源与回放数据存在、重跑不重复、目标全部 source_mode='replay'。 */
@SpringBootTest(properties = {
        "app.dev-seed.enabled=true", "app.fusion.enabled=false", "app.fusion.replay.run-on-start=false",
        "spring.datasource.url=jdbc:h2:mem:stage8_seed_" + "${random.uuid}" + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class LocalStage8FusionReplaySeederTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired LocalStage8FusionReplaySeeder seeder;
    @Autowired com.uav.lowaltitude.integration.replay.FusionReplayRunner runner;
    @Autowired ApplicationArguments arguments;

    /** 直连数据集的四个 inbox 前缀（阶段 8.5 起种子摄取的是设备原始报文，不再是自建回放信封）。 */
    private static final String DIRECT_SOURCES = "(source like 'lingyun:%' or source like 'eo-edge:%' or source like 'live-radar:%')";

    @Test
    void seedsReplaySourcesAndDatasetIdempotently() {
        Map<String, Long> before = counts();
        seeder.run(arguments);
        // 重跑只会跳过已写入的记录，不产生第二份来源、设备、观测或目标。
        assertThat(counts()).isEqualTo(before);

        assertThat(jdbc.queryForList("select source_code from integration_source where source_mode='replay' order by source_code", String.class))
                .contains(FusionReplayDatasetGenerator.AOA, FusionReplayDatasetGenerator.EO, FusionReplayDatasetGenerator.RADAR, FusionReplayDatasetGenerator.TDOA);
        // 只断言本种子登记的来源：迁移 040 也有一条 source_mode='replay' 的规则引擎来源（source_type 为空是它的正常状态）。
        assertThat(jdbc.queryForObject("select count(*) from integration_source where source_id like 'seed-stage8-%' and source_type is null", Long.class)).isZero();
        // 阶段 8.5 起多一台 AOA：它只给方位不给位置，必须是独立来源才能演示"有身份线索但不参与位置关联"。
        assertThat(jdbc.queryForObject("select count(*) from device where device_id like 'seed-stage8%' and enabled=true", Long.class)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where " + DIRECT_SOURCES, Long.class)).isPositive();
        // 摄取完成：没有留下未处理或失败的回放帧。
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where " + DIRECT_SOURCES + " and status<>'DONE'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from source_observation", Long.class)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from target where unified=true", Long.class)).isPositive();
        // 回放产物全部落在 replay 分区：不得混进 mock/live 的统一目标库。
        assertThat(jdbc.queryForObject("select count(*) from target where unified=true and source_mode<>'replay'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from source_observation where source_mode<>'replay'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from track where layer='RAW' and link_id is null", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from track_point tp join track t on t.track_id=tp.track_id where t.layer='RAW' and tp.point_kind<>'MEAS'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from target_track_status s join target t on t.target_id=s.target_id where t.source_mode='replay'", Long.class)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from target_lineage where op='CREATE'", Long.class)).isPositive();
    }

    /**
     * 升级路径：旧构建已把数据集灌进库（例如 1692e10 之前生成的 SenseData 载荷），新构建生成的同键报文哈希不同。
     * 种子不能因此让整个应用起不来——已灌过的数据集应原样保留、跳过重灌，而不是抛 SOURCE_MESSAGE_CONFLICT。
     */
    @Test
    void skipsReloadWhenDatasetAlreadyLoadedByAnOlderBuild() {
        seeder.run(arguments);
        Map<String, Long> before = counts();
        // 把库里已有的一条直连报文改成"旧构建的哈希"，模拟数据集内容随版本漂移。
        int changed = jdbc.update("update inbox_message set payload_hash='0000000000000000000000000000000000000000000000000000000000000000' where inbox_id in "
                + "(select inbox_id from inbox_message where " + DIRECT_SOURCES + " order by inbox_id limit 1)");
        assertThat(changed).isEqualTo(1);
        seeder.run(arguments);
        assertThat(counts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where payload_hash='0000000000000000000000000000000000000000000000000000000000000000'", Long.class)).isEqualTo(1L);
    }

    /**
     * 决策 16-7：**两个数据集的"灌过没有"必须各判各的。**
     *
     * 这是 16.1 捅出娄子的那处机制：`alreadyLoadedV2()` 原本按 `lingyun:`/`eo-edge:` **前缀合计**判断，
     * 而阶段 16 的数据集来源也叫 `lingyun:radar:S16R1`——它的行会把 stage85 的缺行掩盖掉，
     * 守卫看着"灌满了"其实没有。（16.1 里是另一半：数据集变大让条数判断失效，旧库重灌撞哈希、应用起不来。）
     *
     * 把 24 条 stage85 的行移走：真实情况下就是这 24 条没灌进去，守卫必须说"没灌满"。
     * 前缀合计的写法会因为 stage16 的 24 条而仍然算作 180，这条用例就是钉它。
     */
    @Test
    void eachDatasetIsJudgedOnItsOwnRowsNotOnASharedPrefixCount() {
        seeder.run(arguments);
        assertThat(runner.alreadyLoadedV2()).isTrue();
        assertThat(runner.alreadyLoadedStage16()).isTrue();

        jdbc.update("update inbox_message set source='archived:s85' where inbox_id in ("
                + "select inbox_id from inbox_message where source='lingyun:radar:S85R1' order by inbox_id limit 24)");
        try {
            assertThat(runner.alreadyLoadedV2()).as("stage85 缺行不该被 stage16 的行掩盖").isFalse();
            assertThat(runner.alreadyLoadedStage16()).as("stage16 不受 stage85 影响").isTrue();
        } finally {
            jdbc.update("update inbox_message set source='lingyun:radar:S85R1' where source='archived:s85'");
        }
    }

    /** 两个数据集不许写出同一个 (source, record_no)——撞了的话连全新库都装不上。 */
    @Test
    void theTwoDatasetsNeverShareAMessageKey() {
        assertThat(jdbc.queryForObject("select count(*) from (select source, source_msg_id from inbox_message"
                + " group by source, source_msg_id having count(*) > 1) dup", Long.class)).isZero();
    }

    @Test
    void profileAndPropertyGatesExcludeProductionEvenWhenLocalIsAlsoActive() {
        Profile profile = LocalStage8FusionReplaySeeder.class.getAnnotation(Profile.class);
        ConditionalOnProperty property = LocalStage8FusionReplaySeeder.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(profile).isNotNull();
        Profiles expression = Profiles.of(profile.value());
        assertThat(expression.matches(name -> Set.of("production", "local").contains(name))).isFalse();
        assertThat(expression.matches(name -> Set.of("production").contains(name))).isFalse();
        assertThat(expression.matches(name -> Set.of("test").contains(name))).isTrue();
        assertThat(expression.matches(name -> Set.of("local").contains(name))).isTrue();
        assertThat(property.havingValue()).isEqualTo("true");
        // 回放写入器与适配器同样不在生产注册（生产不生成、不消费回放数据）。
        Profiles runner = Profiles.of(com.uav.lowaltitude.integration.replay.FusionReplayRunner.class.getAnnotation(Profile.class).value());
        assertThat(runner.matches(name -> Set.of("production").contains(name))).isFalse();
        Profiles adapter = Profiles.of(com.uav.lowaltitude.integration.replay.ReplayAdapterPort.class.getAnnotation(Profile.class).value());
        assertThat(adapter.matches(name -> Set.of("production").contains(name))).isFalse();
    }

    @Test
    void replayInboxRowsCarryContractEnvelopeIdentity() {
        // 阶段 8.5：inbox 里存的就是设备原样发来的报文，source 用契约 §2 的前缀，不再夹一层自建回放信封。
        List<String> sources = jdbc.queryForList("select distinct source from inbox_message where " + DIRECT_SOURCES + " order by source", String.class);
        assertThat(sources).isNotEmpty();
        assertThat(sources).allSatisfy(source -> assertThat(source).matches("^(lingyun:[a-z0-9]+:|eo-edge:|live-radar:).+"));
        assertThat(sources).anySatisfy(source -> assertThat(source).startsWith("lingyun:aoa:"));
        assertThat(sources).anySatisfy(source -> assertThat(source).startsWith("eo-edge:"));
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where " + DIRECT_SOURCES + " and (payload_hash is null or source_id is null or payload is null)", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where " + DIRECT_SOURCES + " and payload_hash not like '________________________________________________________________'", Long.class)).isZero();
        // ops 的 live-device 行不受影响（本测试库里没有，断言恒为 0 只是守住"融合不碰 ops inbox"这条边界）。
        assertThat(jdbc.queryForObject("select count(*) from inbox_message where source like 'live-device:%' and status='PROCESSING'", Long.class)).isZero();
    }

    private Map<String, Long> counts() {
        return Map.of(
                "integration_source", count("integration_source where source_id like 'seed-stage8-%'"),
                "device", count("device where device_id like 'seed-stage8-%'"),
                "inbox", count("inbox_message where " + DIRECT_SOURCES),
                "observation", count("source_observation"),
                "target", count("target where unified=true"),
                "link", count("target_source_link l join target t on t.target_id=l.target_id where t.unified=true"),
                "track", count("track where layer='RAW'"),
                "lineage", count("target_lineage"));
    }

    private long count(String fromWhere) { return jdbc.queryForObject("select count(*) from " + fromWhere, Long.class); }
}
