package com.uav.lowaltitude.modules.integrationconfig.domain;

import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Only the simulated lifecycle clock may change while replaying the same flight. */
public final class LocalPlanIdentity {
    private static final Set<String> LIFECYCLE = Set.of("PENDING", "EXECUTING", "COMPLETED");
    private LocalPlanIdentity() {}

    public static JsonNode businessPayload(JsonNode input) {
        if (!(input instanceof ObjectNode)) return input;
        ObjectNode result = input.deepCopy();
        result.remove("message_id");
        if (LIFECYCLE.contains(result.path("status_code").asText("PENDING"))) result.remove("status_code");
        if (!result.hasNonNull("source_mode")) result.put("source_mode", "mock");
        return result;
    }

    public static boolean same(JsonNode left, JsonNode right) {
        return businessPayload(left).equals(businessPayload(right));
    }
}
