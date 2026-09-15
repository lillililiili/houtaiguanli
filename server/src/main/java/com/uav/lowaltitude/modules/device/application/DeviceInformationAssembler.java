package com.uav.lowaltitude.modules.device.application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Protocol field coverage is independent of values: absent and stale data remain visible. */
@Component
public class DeviceInformationAssembler {
    private final JsonNode definitions;
    public DeviceInformationAssembler(ObjectMapper mapper) {
        try (var input = new ClassPathResource("device-information-fields.json").getInputStream()) {
            definitions = mapper.readTree(input);
        } catch (IOException ex) { throw new IllegalStateException("设备信息字段定义加载失败", ex); }
    }

    public Section section(String code, String title, String source, String kind, JsonNode values,
            Long observedAt, Long receivedAt, long now, String... groups) {
        List<Field> fields = new ArrayList<>();
        for (String group : groups) for (JsonNode definition : definitions.path(group)) {
            String path = definition.path("path").asText();
            JsonNode value = values == null ? NullNode.instance : values.at(path);
            boolean missing = value.isMissingNode() || value.isNull()
                    || (value.isTextual() && value.asText().isBlank() && !definition.path("allow_empty").asBoolean());
            String type = definition.path("type").asText();
            boolean valid = missing || switch (type) {
                case "number" -> value.isNumber();
                case "integer" -> value.isIntegralNumber();
                case "array" -> value.isArray();
                case "object" -> value.isObject();
                case "boolean" -> value.isBoolean();
                default -> value.isTextual();
            };
            JsonNode enums = definition.path("enum_labels");
            if (!missing && enums.isObject() && !enums.has(value.asText())) valid = false;
            boolean required = definition.path("required").asBoolean(false);
            JsonNode condition = definition.path("required_if");
            if (condition.isObject() && values != null)
                required |= values.at(condition.path("path").asText()).equals(condition.get("value"));
            if (definition.has("required_without_array")) {
                JsonNode alternative = values == null ? NullNode.instance : values.at(definition.get("required_without_array").asText());
                required |= !alternative.isArray() || alternative.isEmpty();
            }
            String status = "CATALOG".equals(kind) ? (missing ? "NOT_CONFIGURED" : "CONFIGURED")
                    : missing ? "NOT_REPORTED" : !valid ? "INVALID"
                    : receivedAt == null || receivedAt > now || now - receivedAt > 30_000
                        || (observedAt != null && (observedAt > now || now - observedAt > 30_000)) ? "STALE" : "RECEIVED";
            if ("REDACTED".equals(kind)) { status = "REDACTED"; value = NullNode.instance; }
            JsonNode nullable = definition.path("nullable_if");
            if (missing && !"REDACTED".equals(kind) && nullable.isObject() && values != null
                    && values.at(nullable.path("path").asText()).equals(nullable.get("value"))) status = "NOT_APPLICABLE";
            String note = definition.path("note").asText("");
            JsonNode label = definition.path("enum_labels").get(value.asText());
            if (!missing && label != null) note = label.asText() + (note.isBlank() ? "" : "；" + note);
            fields.add(new Field(path.substring(1), definition.path("label").asText(),
                    missing ? NullNode.instance : value, definition.path("unit").asText(""),
                    required, status, note));
        }
        return new Section(code, title, source, observedAt, receivedAt, List.copyOf(fields));
    }

    public record Field(String key, String label, JsonNode value, String unit, boolean required, String status, String note) { }
    public record Section(String code, String title, String source, Long observedAt, Long receivedAt, List<Field> fields) { }
}
