package com.uav.lowaltitude.modules.integrationconfig.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.modules.fusion.ingest.NormalizedObservationMapper;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.integrationconfig.api.LocalObservationSimulatorDtos.*;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalObservationSimulatorRepository;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalObservationSimulatorRepository.SourceBinding;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.LocalObservationSimulatorRepository.WeatherBinding;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

class LocalObservationCoordinateTest {
    private final DeviceAccessPolicy interfaces = mock(DeviceAccessPolicy.class);
    private final AccessService access = mock(AccessService.class);
    private final LocalObservationSimulatorRepository repository = mock(LocalObservationSimulatorRepository.class);
    private final FusionInboxRepository inbox = mock(FusionInboxRepository.class);
    private final ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final LocalObservationSimulatorService service = new LocalObservationSimulatorService(interfaces, access,
            repository, inbox, json, mock(AppClock.class), mock(AuditService.class));

    LocalObservationCoordinateTest() {
        when(interfaces.requireInterfacesOperate()).thenReturn(new AuthUser("actor", "account", "name", "role", 1, false, "ALL"));
        when(repository.sourceBinding("source")).thenReturn(new SourceBinding("source", "device", "org", "district", true, true));
        when(repository.weatherBinding("device")).thenReturn(new WeatherBinding("source", "device", "org", "district", true, true));
        when(access.canAccessTuple("org", "district")).thenReturn(true);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "GCJ02", "BD09", "CGCS2000", "urn:WGS84"})
    void untrustedTargetAndWeatherCoordinatesNeverWriteBusinessData(String declaration) {
        assertThatThrownBy(() -> service.acceptTargetObservations(target(declaration, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.acceptWeatherObservation(weather(declaration))).isInstanceOf(ApiException.class);
        verify(inbox, never()).insertEnvelope(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString());
        verify(repository, never()).insertWeatherObservation(anyString(), anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void nestedConflictingDeclarationCannotBeOverriddenByValidPacketDeclaration() {
        assertThatThrownBy(() -> service.acceptTargetObservations(target("WGS84", "GCJ02"))).isInstanceOf(ApiException.class);
        verifyNoInteractions(inbox);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WGS84", "WGS-84"})
    void acceptedDeclarationSurvivesTheInboxAndIsValidatedAgain(String declaration) {
        service.acceptTargetObservations(target(declaration, null));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(inbox).insertEnvelope(eq("sim-normalized:source"), eq("message"), anyLong(), eq("source"), anyString(), payload.capture());
        var frame = new NormalizedObservationMapper(json).map(new FusionInboxRepository.InboxRow("inbox",
                "sim-normalized:source", "message", "source", 1200L, payload.getValue()));
        assertThat(frame.items()).hasSize(1);
        assertThat(frame.items().get(0).longitude()).isEqualTo(113.375);
    }

    private TargetObservationsInput target(String packet, String item) {
        return new TargetObservationsInput("message", "source", 1200L, "org", "district",
                List.of(new TargetItem("external", null, new BigDecimal("113.375"), new BigDecimal("23.625"),
                        null, null, null, null, null, null, null, null, null, null, null, item)), packet);
    }

    private WeatherObservationInput weather(String declaration) {
        return new WeatherObservationInput("weather-message", "device", 1200L, new BigDecimal("102.5"),
                new BigDecimal("25.5"), null, null, null, null, null, null, null, null, declaration);
    }
}
