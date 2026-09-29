package com.uav.lowaltitude.modules.device.application;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceTrendRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class DeviceTrendService {
    private final DeviceAccessPolicy access;
    private final DeviceRepository devices;
    private final DeviceTrendRepository trends;
    private final AppClock clock;
    public DeviceTrendService(DeviceAccessPolicy access, DeviceRepository devices, DeviceTrendRepository trends, AppClock clock) {
        this.access=access; this.devices=devices; this.trends=trends; this.clock=clock;
    }
    public Trends get(String id, String range) {
        access.requireMonitoringRead();
        Map<String,Object> device=devices.find(id);
        if(device==null) throw new ApiException(HttpStatus.NOT_FOUND,"DEVICE_NOT_FOUND","设备不存在");
        long duration=switch(range) { case "1h" -> 3_600_000L; case "24h" -> 86_400_000L; case "7d" -> 604_800_000L;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","时间范围仅支持 1h、24h、7d"); };
        long step=duration==3_600_000L?60_000L:duration==86_400_000L?900_000L:3_600_000L;
        long end=clock.nowMillis(), start=end-duration;
        boolean simulated=Boolean.TRUE.equals(device.get("simulated"));
        String protocol=String.valueOf(device.getOrDefault("protocol_code",""));
        boolean mqtt=protocol.startsWith("LINGYUN_MQTT") || protocol.startsWith("EO_EDGE_MQTT");
        return new Trends(id,protocol,range,start,end,step,simulated,"RADAR_TCP_V3_0_0".equals(protocol) || trends.sensing(id),
                convert(trends.series(id,start,end,step,simulated,mqtt,false),start,step),
                convert(trends.series(id,start,end,step,simulated,mqtt,true),start,step));
    }
    private List<Bucket> convert(List<Map<String,Object>> rows,long start,long step) {
        return rows.stream().map(r -> new Bucket(String.valueOf(r.get("code")),start+((Number)r.get("bucket")).longValue()*step,
                ((Number)r.get("samples")).longValue(), number(r,"average"),number(r,"minimum"),number(r,"maximum"),
                number(r,"latest"),((Number)r.get("last_at")).longValue(),number(r,"interval_seconds"))).toList();
    }
    private static Double number(Map<String,Object> row,String key) { return row.get(key) instanceof Number n?n.doubleValue():null; }
    public record Bucket(String code,long at,long samples,Double average,Double minimum,Double maximum,Double latest,long lastAt,Double intervalSeconds) {}
    public record Trends(String deviceId,String protocolCode,String range,long from,long to,long bucketMs,boolean simulated,boolean sensingSupported,List<Bucket> metrics,List<Bucket> reports) {}
}
