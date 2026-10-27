package com.uav.lowaltitude.modules.alarm.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.uav.lowaltitude.modules.alarm.application.UavRiskCaseService;
import com.uav.lowaltitude.platform.api.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

class UavRiskCaseControllerTest {
    @ParameterizedTest
    @EnumSource(value=HttpStatus.class,names={"FORBIDDEN","NOT_FOUND","CONFLICT","SERVICE_UNAVAILABLE"})
    void committedVerificationIsNotReportedAsARejectedWriteWhenItsProjectionFails(HttpStatus projectionStatus) {
        var service=mock(UavRiskCaseService.class);
        String risk=id(),key=id();
        var original=new ApiException(projectionStatus,"READ_FAILURE","Read unavailable");
        when(service.detail(risk)).thenThrow(original);
        assertThatThrownBy(()->new UavRiskCaseController(service).verify(risk,"{}",key))
                .isInstanceOfSatisfying(ApiException.class,error->{
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getCode()).isEqualTo("VERIFICATION_RESPONSE_UNAVAILABLE");
                    assertThat(error.getCause()).isSameAs(original);
                });
        var ordered=inOrder(service);ordered.verify(service).verify(risk,"{}",key);ordered.verify(service).detail(risk);
    }

    @Test void rejectedVerificationKeepsItsOriginalErrorAndDoesNotReadAResponse() {
        var service=mock(UavRiskCaseService.class);
        String risk=id(),key=id();
        var rejected=new ApiException(HttpStatus.CONFLICT,"RISK_CASE_CHANGED","Changed");
        doThrow(rejected).when(service).verify(risk,"{}",key);
        assertThatThrownBy(()->new UavRiskCaseController(service).verify(risk,"{}",key)).isSameAs(rejected);
        verify(service,never()).detail(anyString());
    }
    private static String id(){return UUID.randomUUID().toString();}
}
