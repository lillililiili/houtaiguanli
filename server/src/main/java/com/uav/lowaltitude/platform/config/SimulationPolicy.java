package com.uav.lowaltitude.platform.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.platform.api.ApiException;

/** Explicit QA isolation; production always wins over other active profiles. */
@Component
public class SimulationPolicy {
    public static final String PROFILE = "((local & qa) | test) & !prod & !production";
    private final Environment environment;

    public SimulationPolicy(Environment environment) { this.environment = environment; }
    @jakarta.annotation.PostConstruct
    public void validateConfiguration() {
        if (allowed()) return;
        if (!"live".equals(environment.getProperty("app.source-mode", "live")))
            throw new IllegalStateException("Formal environment requires app.source-mode=live");
        for (String property : java.util.List.of("app.dev-seed.enabled", "app.device.mock-adapter.enabled",
                "app.fusion.replay.run-on-start", "app.rule-engine.replay.run-on-start",
                "app.rule-engine.allow-demo-active")) {
            if (environment.getProperty(property, Boolean.class, false))
                throw new IllegalStateException("Simulation configuration is forbidden: " + property);
        }
        if ("mock".equals(environment.getProperty("app.handoff.channel", "none")))
            throw new IllegalStateException("Mock handoff channel is forbidden in formal environment");
    }
    public boolean allowed() { return environment.acceptsProfiles(Profiles.of(PROFILE)); }
    public boolean includes(String sourceMode) { return allowed() || "live".equals(sourceMode); }
    public void requireSimulation() {
        if (!allowed()) throw new ApiException(HttpStatus.CONFLICT, "SIMULATION_DISABLED",
                "正式环境不允许模拟操作；历史模拟记录仅供查看");
    }
    public void requireSourceMode(String sourceMode) {
        if (!"live".equals(sourceMode)) requireSimulation();
    }
}
