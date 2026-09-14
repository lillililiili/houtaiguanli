package com.uav.lowaltitude.modules.fusion.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigService;
import com.uav.lowaltitude.modules.fusion.FusionContracts.PointKind;
import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceEstimate;
import com.uav.lowaltitude.testsupport.SourceTypeCatalogFixture;

/**
 * 阶段 8.5 契约基线（迁移 070 + 冻结接口扩展）：来源目录八行且只有雷达 CONFIRMED、凌云协议引用已登记、
 * 参数缺省精度含 AOA/DCD/RID、SourceEstimate 旧签名仍可构造且新字段为 null。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class Stage85ContractTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired FusionConfigService config;

    @Test
    void catalogHasEightSourceTypesAndOnlyRadarIsConfirmed() {
        // 八行目录、只有雷达 CONFIRMED、其余 DEMO：口径统一在夹具里（阶段 10.3）。
        SourceTypeCatalogFixture.assertCatalog(jdbc);
        List<Map<String, Object>> rows = jdbc.queryForList("select source_type, schema_status, spec_ref from source_type_catalog order by source_type");
        assertThat(rows).filteredOn(r -> !SourceTypeCatalogFixture.CONFIRMED_TYPES.contains(r.get("source_type"))).allSatisfy(r -> {
            // 三路字段现在有出处（凌云协议 A），但联调前不得标 CONFIRMED。
            assertThat(String.valueOf(r.get("spec_ref"))).contains("凌云协议");
        });
    }

    @Test
    void accuracyDefaultsCoverLingyunSourceTypes() {
        FusionParams params = config.params("demo-v1");
        Map<String, Double> defaults = params.accuracyDefaults();
        assertThat(defaults).containsKeys("RADAR", "EO", "TDOA", "FIVE_G_A", "FUSION_BOX", "AOA", "DCD", "RID");
        assertThat(params.weights()).containsKeys("AOA", "DCD", "RID");
        // AOA 只给方位：位置权重必须为 0，否则会把不存在的位置当成观测。
        assertThat(params.weights().get("AOA").get("position")).isEqualTo(0.0);
    }

    @Test
    void legacySourceEstimateConstructorStillCompilesWithNullPilotFields() {
        SourceEstimate e = new SourceEstimate("s", "code", "TDOA", "DEMO", "l", "t", "o", java.time.Instant.EPOCH, 118.0, 37.0, 60.0,
                null, null, null, null, null, null, "SN-1", 0.9, PointKind.MEAS, Map.of());
        assertThat(e.pilotLongitude()).isNull();
        assertThat(e.classSource()).isNull();
    }
}
