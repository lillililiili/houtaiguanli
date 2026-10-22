package com.uav.lowaltitude.modules.prediction.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.prediction.domain.PredictionEngine;
import com.uav.lowaltitude.modules.prediction.domain.PredictionStability;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Immutable, content-addressed parameters; every forecast freezes the complete configuration. */
@Component
public class PredictionConfiguration {
    public record Parameters(PredictionEngine.Config forecast, PredictionStability.Config stability) { }
    private final Parameters parameters;
    private final String serialized, version;
    public PredictionConfiguration(ObjectMapper json, @Value("${app.prediction.parameters:}") String configured) {
        try {
            parameters = configured.isBlank()
                    ? new Parameters(PredictionEngine.Config.defaults(), PredictionStability.Config.defaults())
                    : json.readValue(configured, Parameters.class);
            if (parameters.forecast() == null || parameters.stability() == null)
                throw new IllegalArgumentException("Both forecast and stability parameters are required");
            serialized = json.writeValueAsString(parameters);
            version = "k03-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(serialized.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid K03 configuration", e); }
    }
    public Parameters parameters() { return parameters; }
    public String serialized() { return serialized; }
    public String version() { return version; }
}
