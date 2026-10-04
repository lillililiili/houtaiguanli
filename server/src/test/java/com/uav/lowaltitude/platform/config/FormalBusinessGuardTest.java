package com.uav.lowaltitude.platform.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.modules.device.application.*;
import com.uav.lowaltitude.modules.device.domain.MqttConfiguration.*;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.identity.application.*;
import com.uav.lowaltitude.modules.responseplan.application.ResponsePlanService;
import com.uav.lowaltitude.modules.responseplan.api.ResponsePlanDtos.*;
import com.uav.lowaltitude.modules.responseplan.infrastructure.ResponsePlanRepository;
import com.uav.lowaltitude.platform.api.ApiException;

class FormalBusinessGuardTest {
    private final SimulationPolicy formal=new SimulationPolicy(SimulationPolicyTest.environment("production"));
    @Test void replayConnectorCreationAndEnableValidationAreRejectedBeforeNetworkOrWrites() {
        var service=new MqttConfigurationService(null,null,mock(DeviceAccessPolicy.class),null,null,null,null,null,null,formal);
        assertThatThrownBy(()->service.create(new BrokerInput("replay","127.0.0.1",1883,false,null,null,"127.0.0.0/8","replay","org","district",null),"key"))
                .isInstanceOf(ApiException.class).hasMessageContaining("模拟");
        assertThatThrownBy(()->service.validateConnection(new Broker("broker","replay","127.0.0.1",1883,false,"id",null,null,"127.0.0.0/8","replay","org","district",true,0,null,null)))
                .isInstanceOf(ApiException.class);
        assertThat(service.sourceAllowed("replay")).isFalse();
        assertThat(service.sourceAllowed("live")).isTrue();
    }
    @Test void simulatedResponsePlanCannotBeCreatedPublishedOrRewrittenAsLive() {
        var repository=mock(ResponsePlanRepository.class);
        var service=new ResponsePlanService(repository,mock(AccessService.class),null,null,null,null,null,formal);
        var input=new Input("airspace","plan","basis","steps","manual","failure","mock",0L,null,null);
        assertThatThrownBy(()->service.create(input,"key")).isInstanceOf(ApiException.class);
        var historical=new Version("plan","version","airspace","airspace",1,"plan","basis","steps","manual","failure","replay",0,null,"DRAFT",0,0,0,null,null,null,null);
        when(repository.get("version")).thenReturn(historical);
        assertThatThrownBy(()->service.publish("version",new Change(0L,"publish"),"key")).isInstanceOf(ApiException.class);
        var live=new Input("airspace","plan","basis","steps","manual","failure","live",0L,null,0L);
        assertThatThrownBy(()->service.update("version",live,"key")).isInstanceOf(ApiException.class);
        verify(repository,never()).createPlan(anyString(),anyString(),anyLong());
    }
    @Test void simulatedCommissionCannotBeCreated() {
        var devices=mock(DeviceRepository.class);
        when(devices.find("device")).thenReturn(Map.of("enabled",true,"source_mode","replay","simulated",true));
        var clock=mock(com.uav.lowaltitude.platform.time.AppClock.class);
        var access=mock(DeviceAccessPolicy.class);
        when(access.requireCommissionOperate()).thenReturn(mock(com.uav.lowaltitude.platform.security.AuthUser.class));
        var commissions=mock(com.uav.lowaltitude.modules.device.infrastructure.CommissionRepository.class);
        when(commissions.deviceInScope(eq("device"),any())).thenReturn(true);
        var service=new CommissionService(commissions,devices,access,clock,new AppProperties(),null,null,null,formal);
        assertThatThrownBy(()->service.create("device",null)).isInstanceOf(ApiException.class);
    }
}
