package com.uav.lowaltitude.modules.integrationconfig.domain;

import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A declared simulation batch is business identity; only transport IDs and lifecycle clocks may change within it. */
public final class LocalPlanIdentity {
    private static final Set<String> LIFECYCLE = Set.of("PENDING", "EXECUTING", "COMPLETED");
    private LocalPlanIdentity() {}

    public static JsonNode businessPayload(JsonNode input) {
        if (!(input instanceof ObjectNode)) return input;
        ObjectNode result = input.deepCopy();
        result.remove("message_id");
        // Legacy requests carry no batch. Preserve that identity when a new client explicitly sends null.
        // A non-null batch stays in the payload, so another rehearsal cannot reuse a previous batch's plan.
        if (!result.hasNonNull("simulation_batch_id")) result.remove("simulation_batch_id");
        if (LIFECYCLE.contains(result.path("status_code").asText("PENDING"))) result.remove("status_code");
        if (!result.hasNonNull("source_mode")) result.put("source_mode", "mock");
        return result;
    }

    public static boolean same(JsonNode left, JsonNode right) {
        return businessPayload(left).equals(businessPayload(right));
    }
}
