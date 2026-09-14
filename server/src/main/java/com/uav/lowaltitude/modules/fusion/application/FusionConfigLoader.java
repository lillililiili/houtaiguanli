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

    public FusionConfigLoader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public FusionParams active() {
        List<String[]> rows = jdbc.query("SELECT config_version, CAST(params AS VARCHAR) AS params_text FROM fusion_config WHERE status='ACTIVE'",
                (rs, i) -> new String[] { rs.getString("config_version"), rs.getString("params_text") });
        if (rows.size() != 1) throw new IllegalStateException("fusion_config 必须恰有一个 ACTIVE 版本，当前 " + rows.size() + " 个");
        return FusionParamsImpl.fromJson(rows.get(0)[0], rows.get(0)[1]);
    }
}
