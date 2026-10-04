package com.uav.lowaltitude.modules.fusion.application;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.fusion.FusionContracts.FusionParams;
import com.uav.lowaltitude.modules.fusion.domain.FusionParamsImpl;

/**
 * 读取唯一 ACTIVE 的 fusion_config 并解析为参数视图。没有或多于一个 ACTIVE 都是部署错误：
 * 引擎不能在"随便挑一个版本"的情况下产生看似正常的融合结果。
 */
@Component
public class FusionConfigLoader {
    private final JdbcTemplate jdbc;
    private final com.uav.lowaltitude.platform.config.SimulationPolicy simulation;

    public FusionConfigLoader(JdbcTemplate jdbc, com.uav.lowaltitude.platform.config.SimulationPolicy simulation) { this.jdbc = jdbc; this.simulation=simulation; }

    public FusionParams active() {
        List<String[]> rows = jdbc.query("SELECT config_version, CAST(params AS VARCHAR) AS params_text FROM fusion_config WHERE status='ACTIVE'",
                (rs, i) -> new String[] { rs.getString("config_version"), rs.getString("params_text") });
        if (rows.size() != 1) throw new IllegalStateException("fusion_config 必须恰有一个 ACTIVE 版本，当前 " + rows.size() + " 个");
        FusionParams base=FusionParamsImpl.fromJson(rows.get(0)[0], rows.get(0)[1]);
        return simulationParameters(base);
    }

    /** Supplement only the explicitly simulated source, without editing or replacing active config. */
    public FusionParams simulationParameters(FusionParams base) {
        if (!simulation.allowed()) return base;
        var settings=jdbc.queryForMap("SELECT * FROM simulator_fusion_parameters WHERE source_type='SIM_NORMALIZED'");
        var accuracy=new java.util.HashMap<>(base.accuracyDefaults());
        accuracy.put("SIM_NORMALIZED",((Number)settings.get("accuracy_m")).doubleValue());
        var weights=new java.util.HashMap<>(base.weights());
        weights.put("SIM_NORMALIZED",java.util.Map.of(
                "position",((Number)settings.get("position_weight")).doubleValue(),
                "motion",((Number)settings.get("motion_weight")).doubleValue(),
                "class",((Number)settings.get("class_weight")).doubleValue(),
                "identity",((Number)settings.get("identity_weight")).doubleValue()));
        return new FusionParams() {
            public String configVersion(){return base.configVersion();}
            public double number(String group,String key){return base.number(group,key);}
            public int integer(String group,String key){return base.integer(group,key);}
            public java.util.Map<String,Double> accuracyDefaults(){return java.util.Map.copyOf(accuracy);}
            public java.util.Map<String,java.util.Map<String,Double>> weights(){return java.util.Map.copyOf(weights);}
        };
    }
}
