package com.uav.lowaltitude.modules.integrationconfig.api;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.geo.JsonCoordinateContract;
import com.uav.lowaltitude.platform.api.ApiException;

import jakarta.validation.Validator;

/** Preserve raw coordinate declarations until they have passed validation. */
@Component
public class LocalObservationInputParser {
    private final ObjectMapper strict;
    private final Validator validator;

    public LocalObservationInputParser(ObjectMapper json, Validator validator) {
        this.strict = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.validator = validator;
    }

    public <T> T read(String payload, Class<T> type, boolean targetItems) {
        try {
            JsonNode root = strict.readTree(payload);
            if (root == null || !root.isObject()) throw invalid("请求须为 JSON 对象");
            if (declares(root) || JsonCoordinateContract.hasCoordinates(root, "longitude", "latitude"))
                JsonCoordinateContract.requireWgs84(root);
            if (targetItems && root.path("items").isArray()) {
                for (JsonNode item : root.path("items")) {
                    if (declares(item) || JsonCoordinateContract.hasCoordinates(item, "longitude", "latitude",
                            "pilot_longitude", "pilot_latitude", "pilotLongitude", "pilotLatitude"))
                        JsonCoordinateContract.requireWgs84(root, item);
                }
            }
            // Retain the application's existing snake_case contract and unknown
            // field policy. The camel spelling above is inspected, not promoted.
            T input = strict.treeToValue(root, type);
            var violations = validator.validate(input);
            if (!violations.isEmpty()) {
                String message = violations.stream().map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .sorted().findFirst().orElse("参数无效");
                throw invalid(message);
            }
            return input;
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数格式不正确");
        } catch (IllegalArgumentException ex) {
            throw invalid(ex.getMessage());
        }
    }

    private static boolean declares(JsonNode node) {
        return node != null && node.isObject() && (node.has("coordinate_system") || node.has("coordinateSystem"));
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
