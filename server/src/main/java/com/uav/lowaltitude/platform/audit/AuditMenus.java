package com.uav.lowaltitude.platform.audit;

import java.util.List;
import java.util.Objects;

/** Stable event/object contracts identify the menu, including older generic system records. */
final class AuditMenus {
    private record Rule(String column, String value, boolean prefix, String menu) {
        boolean matches(String module, String action, String objectType, String objectId) {
            if (column.equals("object_id")) return "request".equals(objectType) && objectId != null
                    && (objectId.equals(value) || objectId.startsWith(value + "/"));
            String actual = switch (column) {
                case "action" -> action;
                case "object_type" -> objectType;
                default -> module;
            };
            return actual != null && (prefix ? actual.startsWith(value) : Objects.equals(actual, value));
        }
        String sql() {
            // All identifiers and literals below are application constants, never request input.
            if (column.equals("object_id")) return "(a.object_type = 'request' AND (a.object_id = '" + value
                    + "' OR LEFT(a.object_id, " + (value.length() + 1) + ") = '" + value + "/'))";
            return (prefix ? "LEFT(a." + column + ", " + value.length() + ")" : "a." + column)
                    + " = '" + value + "'";
        }
    }

    private static Rule action(String prefix, String menu) { return new Rule("action", prefix, true, menu); }
    private static Rule object(String type, String menu) { return new Rule("object_type", type, false, menu); }
    private static Rule module(String code, String menu) { return new Rule("module_code", code, false, menu); }
    private static Rule request(String path, String menu) { return new Rule("object_id", "/api/v1/" + path, false, menu); }
    private static final List<Rule> RULES = List.of(
            request("commission-tasks", "commissioning"), request("device-maintenance-tasks", "maintenance"),
            request("device-events", "maintenance"), request("device-incidents", "maintenance"),
            request("auth/profile", "profile"), request("auth/change-password", "profile"),
            action("commission_", "commissioning"), object("commission_task", "commissioning"),
            action("device_maintenance_", "maintenance"), action("device_incident_", "maintenance"),
            object("device_maintenance_task", "maintenance"), object("device_incident", "maintenance"),
            action("password_", "profile"), action("profile_", "profile"),
            action("mqtt_broker_", "interfaces"), action("external_interface_", "interfaces"),
            action("advisory_voice_recording_", "interfaces"), action("local_interface_", "interfaces"),
            object("mqtt", "interfaces"), object("external_interface", "interfaces"),
            object("local_interface", "interfaces"), object("integration_source", "interfaces"),
            object("device", "devices"), object("device_command", "monitoring"),
            object("eo_tracking_task", "fusion"), action("eo_auto_", "fusion"),
            module("commission", "commissioning"), module("monitor", "monitoring"),
            module("mqtt", "interfaces"), module("integration", "interfaces"),
            module("organizations", "users"), module("user", "users"), module("role", "roles"),
            module("device", "devices"), module("alarm", "alarms"), module("risks", "risk"),
            module("flight", "flights"), module("rules", "responsePlans"));

    private AuditMenus() { }

    static String resolve(String module, String action, String objectType, String objectId) {
        for (Rule rule : RULES) if (rule.matches(module, action, objectType, objectId)) return rule.menu();
        return module == null || module.isBlank() ? "system" : module;
    }

    static String sql() {
        StringBuilder sql = new StringBuilder("CASE ");
        for (Rule rule : RULES) sql.append("WHEN ").append(rule.sql()).append(" THEN '").append(rule.menu()).append("' ");
        return sql.append("ELSE COALESCE(NULLIF(TRIM(a.module_code), ''), 'system') END").toString();
    }
}
