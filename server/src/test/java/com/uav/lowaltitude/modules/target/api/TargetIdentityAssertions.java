package com.uav.lowaltitude.modules.target.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;

/** The same read contract is exercised against H2 and disposable PostgreSQL. */
final class TargetIdentityAssertions {
    interface Read { JsonNode get(String path) throws Exception; }

    static void verify(JdbcTemplate jdbc, String targetId, Read read) throws Exception {
        long total = read.get("/api/v1/targets?size=100").path("data").path("total").asLong();
        jdbc.update("update target set unified=true,uav_sn='STALE-SN' where target_id=?", targetId);
        String config = jdbc.queryForObject("select config_version from fusion_config order by config_version fetch first 1 rows only", String.class);
        jdbc.update("insert into target_attribute_selection(target_id,identity_clue,selected_at,config_version,updated_at) values(?,null,?,?,?)",
                targetId, OffsetDateTime.now(), config, OffsetDateTime.now());
        assertSn(read, targetId, null, total);
        jdbc.update("update target_attribute_selection set identity_clue='CURRENT-SN' where target_id=?", targetId);
        assertSn(read, targetId, "CURRENT-SN", total);
        jdbc.update("update target set uav_sn=null where target_id=?", targetId);
        assertSn(read, targetId, "CURRENT-SN", total);
        jdbc.update("update target_attribute_selection set identity_clue=null where target_id=?", targetId);
        assertSn(read, targetId, null, total);
        jdbc.update("update target set unified=false,uav_sn='LEGACY-SN' where target_id=?", targetId);
        assertSn(read, targetId, "LEGACY-SN", total);
    }

    private static void assertSn(Read read, String targetId, String expected, long total) throws Exception {
        JsonNode page = read.get("/api/v1/targets?size=100").path("data");
        assertThat(page.path("total").asLong()).isEqualTo(total);
        int matches = 0;
        for (JsonNode item : page.path("items")) {
            if (targetId.equals(item.path("target_id").asText())) {
                matches++;
                assertThat(item.path("uav_sn").asText(null)).isEqualTo(expected);
            }
        }
        assertThat(matches).isEqualTo(1);
        assertThat(read.get("/api/v1/targets/" + targetId).path("data").path("uav_sn").asText(null)).isEqualTo(expected);
    }
}
