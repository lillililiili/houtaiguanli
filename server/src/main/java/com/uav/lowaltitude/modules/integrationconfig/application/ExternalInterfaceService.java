package com.uav.lowaltitude.modules.integrationconfig.application;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.flight.application.FlightReadService;
import com.uav.lowaltitude.modules.integrationconfig.api.ExternalInterfaceDtos.*;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.ExternalInterfaceRepository;
import com.uav.lowaltitude.modules.integrationconfig.infrastructure.ExternalInterfaceRepository.Row;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class ExternalInterfaceService {
    private final ExternalInterfaceRepository repository;
    private final DeviceAccessPolicy access;
    private final FlightReadService flights;
    private final AppClock clock;
    private final AuditService audit;
    private final com.uav.lowaltitude.integration.mock.MockWeatherForecast mockWeather;
    public ExternalInterfaceService(ExternalInterfaceRepository repository, DeviceAccessPolicy access,
            FlightReadService flights, AppClock clock, AuditService audit,
            com.uav.lowaltitude.integration.mock.MockWeatherForecast mockWeather) {
        this.repository=repository; this.access=access; this.flights=flights; this.clock=clock; this.audit=audit; this.mockWeather=mockWeather;
    }
    public Configuration get(String kind) { access.requireInterfacesRead(); return dto(required(kind)); }
    @Transactional
    public Configuration save(String kind, Input input) {
        var user = access.requireInterfacesOperate();
        required(kind);
        Input p = normalized(input);
        if (!java.util.Set.of("live","mock").contains(p.sourceMode())) throw bad("数据模式无效");
        if ("mock".equals(p.sourceMode())) {
            if (!"WEATHER_FORECAST".equals(kind)) throw bad("仅天气预报支持模拟模式");
            if (!mockWeather.available()) throw bad("当前环境不允许天气模拟");
            if (p.areaName()==null) throw bad("请填写模拟预报区域");
        }
        if (p.sourceCode()!=null && !p.sourceCode().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw bad("来源标识仅支持字母、数字、下划线和短横线");
        if (p.direction()!=null && !java.util.Set.of("PUSH","PULL").contains(p.direction())) throw bad("接入方式无效");
        if ("WEATHER_FORECAST".equals(kind) && (p.direction()!=null || p.sourceCode()!=null || p.allowedCidrs()!=null))
            throw bad("天气预报配置不接受计划输入字段");
        if ("FLIGHT_PLAN".equals(kind) && (p.areaName()!=null || p.intervalMinutes()!=null || p.validityMinutes()!=null))
            throw bad("计划输入配置不接受天气预报字段");
        if (p.credentialRef()!=null && !p.credentialRef().matches("env:[A-Za-z_][A-Za-z0-9_]*"))
            throw bad("凭据请填写 env:环境变量名，不填写密钥本身");
        if (p.endpoint()!=null) {
            try {
                URI uri=URI.create(p.endpoint());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                        || uri.getRawQuery()!=null || uri.getFragment()!=null) throw bad("服务地址须为 HTTPS 地址，不能包含凭据、查询参数或片段");
            } catch (IllegalArgumentException e) { throw bad("服务地址格式不正确"); }
        }
        if ("PUSH".equals(p.direction()) && p.endpoint()!=null) throw bad("推送接收地址待协议对齐后由服务端提供");
        if (repository.update(kind,p,clock.nowMillis())!=1)
            throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","配置已被修改，请重新加载后保存");
        audit.record(user.userId(),user.account(),"external_interface_config_save","external_interface",kind,"保存接口配置（"+p.sourceMode()+"）",null);
        return dto(required(kind));
    }
    @Transactional(readOnly=true)
    public ForecastAvailability forecast(String planId) {
        // The existing plan reader enforces action permission, object existence and data scope first.
        var plan=flights.flightPlan(planId);
        Row row=required("WEATHER_FORECAST");
        if ("mock".equals(row.sourceMode()) && row.updatedAt()!=null) {
            if (!mockWeather.available()) return new ForecastAvailability(plan.planId(), "AWAITING_ADAPTER", "当前环境不允许天气模拟", null);
            boolean stale=clock.nowMillis() >= row.updatedAt()+com.uav.lowaltitude.integration.mock.MockWeatherForecast.DURATION_MILLIS;
            return new ForecastAvailability(plan.planId(), stale ? "STALE" : "READY",
                stale ? "本批模拟预报已过期" : null, mockWeather.forecast(row.areaName(),row.updatedAt()));
        }
        return new ForecastAvailability(plan.planId(), status(row), row.updatedAt()==null
            ? "天气预报尚未接入" : "天气预报尚未接通，暂无预报数据", null);
    }
    private Row required(String kind) {
        if (!java.util.Set.of("FLIGHT_PLAN","WEATHER_FORECAST").contains(kind))
            throw new ApiException(HttpStatus.NOT_FOUND,"INTERFACE_NOT_FOUND","接口配置不存在");
        Row row=repository.find(kind);
        if(row==null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CONFIG_UNAVAILABLE","接口配置暂不可用");
        return row;
    }
    private String status(Row row) {
        if (row.updatedAt()==null) return "NOT_CONFIGURED";
        if ("mock".equals(row.sourceMode()) && mockWeather.available())
            return clock.nowMillis() >= row.updatedAt()+com.uav.lowaltitude.integration.mock.MockWeatherForecast.DURATION_MILLIS ? "STALE" : "SIMULATED";
        return "AWAITING_ADAPTER";
    }
    private Configuration dto(Row r) { return new Configuration(r.kind(),r.name(),r.sourceCode(),r.direction(),
        r.endpoint(),r.credentialRef(),r.allowedCidrs(),r.areaName(),r.intervalMinutes(),r.validityMinutes(),r.version(),r.updatedAt(),
        status(r),"SIMULATED".equals(status(r)),r.updatedAt()==null ? "尚未配置"
            : ("mock".equals(r.sourceMode()) ? "模拟预报批次有效期为 24 小时" : "配置已保存，待确认协议并接入适配器"),r.sourceMode()); }
    private static String clean(String s) { return s==null || s.isBlank() ? null : s.trim(); }
    private static Input normalized(Input p) { return new Input(p.version(),clean(p.name()),clean(p.sourceCode()),clean(p.direction()),
        clean(p.endpoint()),clean(p.credentialRef()),clean(p.allowedCidrs()),clean(p.areaName()),p.intervalMinutes(),p.validityMinutes(),clean(p.sourceMode())==null ? "live" : clean(p.sourceMode())); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
}
