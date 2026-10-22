package com.uav.lowaltitude.modules.integrationconfig.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.uav.lowaltitude.modules.flight.application.LocalRouteInputService;
import com.uav.lowaltitude.modules.handoff.application.LocalInterfaceReceiptService;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalObservationSimulatorDtos.*;
import com.uav.lowaltitude.modules.integrationconfig.application.LocalInterfaceSimulatorService;
import com.uav.lowaltitude.modules.integrationconfig.application.LocalObservationSimulatorService;
import com.uav.lowaltitude.platform.api.GlobalExceptionHandler;
import com.uav.lowaltitude.platform.audit.AuditService;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

/** HTTP decoding is tested before DTO construction; no database or seed context. */
class LocalObservationRawJsonApiTest {
    private final LocalObservationSimulatorService observations = mock(LocalObservationSimulatorService.class);
    private ValidatorFactory validators;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        validators = Validation.buildDefaultValidatorFactory();
        var json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var controller = new LocalInterfaceSimulatorController(mock(LocalInterfaceSimulatorService.class), observations,
                mock(LocalInterfaceReceiptService.class), mock(LocalRouteInputService.class),
                new LocalObservationInputParser(json, validators.getValidator()));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter(json))
                .setControllerAdvice(new GlobalExceptionHandler(mock(AuditService.class))).build();
        when(observations.acceptTargetObservations(any())).thenReturn(new AcceptedInput("message", "source", "device", 1));
        when(observations.acceptWeatherObservation(any())).thenReturn(new AcceptedInput("message", "source", "device", 1));
    }

    @AfterEach void close() { validators.close(); }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"coordinateSystem\":\"GCJ02\"", "\"coordinate_system\":null",
            "\"coordinate_system\":\"\"", "\"coordinateSystem\":4326",
            "\"coordinate_system\":\"WGS84\",\"coordinateSystem\":\"BD09\"",
            "\"coordinate_system\":\"GCJ02\",\"coordinate_system\":\"WGS84\""
    })
    void nestedDeclarationsCannotDisappearDuringDtoMapping(String declaration) throws Exception {
        mvc.perform(post("/api/v1/local-interface-simulator/target-observations")
                .contentType(MediaType.APPLICATION_JSON).content(target(",\"longitude\":120.25,\"latitude\":31.75," + declaration)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false));
        verifyNoInteractions(observations);
    }

    @Test
    void validPackageDeclarationIsInheritedAndNoPositionRemainsAllowed() throws Exception {
        mvc.perform(post("/api/v1/local-interface-simulator/target-observations").contentType(MediaType.APPLICATION_JSON)
                .content(target(",\"longitude\":120.25,\"latitude\":31.75")))
                .andExpect(status().isOk());
        var input = ArgumentCaptor.forClass(TargetObservationsInput.class);
        verify(observations).acceptTargetObservations(input.capture());
        assertThat(input.getValue().coordinateSystem()).isEqualTo("WGS84");
        assertThat(input.getValue().items().get(0).coordinateSystem()).isNull();
        reset(observations);
        mvc.perform(post("/api/v1/local-interface-simulator/target-observations").contentType(MediaType.APPLICATION_JSON)
                .content(target("").replace(",\"coordinate_system\":\"WGS84\"", "")))
                .andExpect(status().isOk());
        verify(observations).acceptTargetObservations(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"coordinateSystem\":\"BD09\"", "\"coordinateSystem\":null",
            "\"coordinate_system\":\"GCJ02\",\"coordinate_system\":\"WGS84\""
    })
    void weatherRawDeclarationsAreCheckedBeforeUnknownFieldsAreDiscarded(String declaration) throws Exception {
        String payload = "{\"message_id\":\"message\",\"device_id\":\"device\",\"observed_at\":1200,"
                + "\"longitude\":109.75,\"latitude\":22.25,\"coordinate_system\":\"WGS84\"," + declaration + "}";
        mvc.perform(post("/api/v1/local-interface-simulator/weather-observations").contentType(MediaType.APPLICATION_JSON)
                .content(payload)).andExpect(status().isBadRequest());
        verifyNoInteractions(observations);
    }

    @Test
    void beanValidationAndTrailingJsonChecksRemainInForce() throws Exception {
        for (String payload : new String[] {target("").replace("\"observed_at\":1200", "\"observed_at\":0"),
                target("").replace("\"external_target_id\":\"target\"", "\"external_target_id\":\"\""),
                target("") + "{}"}) {
            mvc.perform(post("/api/v1/local-interface-simulator/target-observations").contentType(MediaType.APPLICATION_JSON)
                    .content(payload)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(observations);
    }

    private String target(String fields) {
        return "{\"message_id\":\"message\",\"source_id\":\"source\",\"observed_at\":1200,\"coordinate_system\":\"WGS84\","
                + "\"items\":[{\"external_target_id\":\"target\"" + fields + "}]}";
    }
}
