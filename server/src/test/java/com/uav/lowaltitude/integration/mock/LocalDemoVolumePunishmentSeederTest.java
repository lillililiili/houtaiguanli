package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.handoff.application.HandoffMaterialAssembler;

/**
 * 处罚体量种子必须走完演示主线：已核实 → 已完成反制 → 已完成干扰 → 处罚交接。
 * 该 Runner 只在 local 注册，测试里手动 new，避免把体量行写进共享 test 夹具。
 */
@SpringBootTest
@ActiveProfiles("test")
class LocalDemoVolumePunishmentSeederTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired HandoffMaterialAssembler materials;
    @Autowired ObjectMapper json;

    @Test
    @Transactional
    void sourceChainCompletesCountermeasureThenChainedJammingBeforeHandoff() {
        new LocalDemoVolumePunishmentSeeder(jdbc, materials, json).run(new DefaultApplicationArguments());

        String eventId = "seed-vol-pcase-event-01";
        assertThat(jdbc.queryForObject("select state_code from uav_event where event_id=?", String.class, eventId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_kind='UAV_EVENT'"
                + " and subject_id=? and action_type='COUNTERMEASURE' and status='COMPLETED'", Long.class, eventId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where subject_kind='UAV_EVENT'"
                + " and subject_id=? and action_type='JAMMING' and status='COMPLETED'"
                + " and chained_from_authorization_id is not null", Long.class, eventId))
                .isEqualTo(1L);
        String parent = jdbc.queryForObject("select authorization_id from disposal_authorization where subject_kind='UAV_EVENT'"
                + " and subject_id=? and action_type='COUNTERMEASURE'", String.class, eventId);
        assertThat(jdbc.queryForObject("select chained_from_authorization_id from disposal_authorization"
                + " where subject_kind='UAV_EVENT' and subject_id=? and action_type='JAMMING'", String.class, eventId))
                .isEqualTo(parent);
        assertThat(jdbc.queryForObject("select count(*) from handoff where source_kind='UAV_EVENT' and source_id=?"
                + " and handoff_type='UAV_PUNISHMENT'", Long.class, eventId)).isEqualTo(1L);
    }

    @Test
    @Transactional
    void rerunIsIdempotent() {
        LocalDemoVolumePunishmentSeeder seeder = new LocalDemoVolumePunishmentSeeder(jdbc, materials, json);
        seeder.run(new DefaultApplicationArguments());
        seeder.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("select count(*) from punishment_case where case_id like 'seed-vol-case-%'", Long.class))
                .isEqualTo(6L);
        assertThat(jdbc.queryForObject("select count(*) from disposal_authorization where authorization_id like 'seed-vol-pcase-%'",
                Long.class)).isEqualTo(12L);
    }
}
