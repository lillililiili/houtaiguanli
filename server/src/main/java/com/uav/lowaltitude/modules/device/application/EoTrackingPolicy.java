package com.uav.lowaltitude.modules.device.application;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.uav.lowaltitude.modules.device.infrastructure.EoTrackingRepository;
import com.uav.lowaltitude.modules.device.domain.EoEdgeConfiguration.Binding;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class EoTrackingPolicy {
    private final EoTrackingRepository repository;
    private final AppClock clock;
    private final boolean enabled;
    private final boolean mqttEnabled;
    @Value("${app.eo-edge.auto-track-source-modes:live,replay}")
    private String autoSourceModes="live,replay";
    private final com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch departure;
    private final com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository edges;
    private final long positionAge, heartbeatAge, demandAge;
    public EoTrackingPolicy(EoTrackingRepository repository, AppClock clock,
            com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch departure,
            com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository edges,
            @Value("${app.mqtt.enabled:true}") boolean mqttEnabled,
            @Value("${app.eo-edge.auto-track.enabled:${app.eo-edge.auto-track-enabled:false}}") boolean enabled,
            @Value("${app.eo-edge.position-max-age-millis:15000}") long positionAge,
            @Value("${app.eo-edge.heartbeat-timeout-millis:30000}") long heartbeatAge,
            @Value("${app.eo-edge.demand-max-age-millis:300000}") long demandAge) {
        this.repository=repository;this.clock=clock;this.enabled=enabled;
        this.mqttEnabled=mqttEnabled;this.departure=departure;
        this.edges=edges;
        this.positionAge=positionAge;this.heartbeatAge=heartbeatAge;this.demandAge=demandAge;
    }
    public boolean enabled() { return enabled && mqttEnabled; }
    public boolean enabledFor(Map<String,Object> target) {
        if(target==null) return false;
        String targetMode=mode(target);
        return enabled() && targetMode!=null && Arrays.stream(autoSourceModes.split(",")).map(String::trim).anyMatch(targetMode::equals);
    }
    public long cutoff() { return clock.nowMillis()-positionAge; }
    public long heartbeatCutoff() {return clock.nowMillis()-heartbeatAge;}
    public long now() {return clock.nowMillis();}
    public boolean deviceReady(Binding b) {
        long now=clock.nowMillis();
        return b!=null && b.enabled() && Integer.valueOf(0).equals(b.workState()) && b.lastHeartbeatAt()!=null
                && b.lastHeartbeatAt()>=now-heartbeatAge && b.lastHeartbeatAt()<=now;
    }
    public String block(Map<String,Object> target) {
        if(target==null) return "TARGET_NOT_FOUND";
        if(edges.uncertainTaskByTarget(text(target,"target_id"))!=null) return "EO_RESULT_UNKNOWN";
        if(text(target,"unknown_fields").contains("\"location\"")) return "TARGET_POSITION_UNAVAILABLE";
        if(position(target)==null) return "TARGET_POSITION_UNAVAILABLE";
        long observed=millis(target.get("observed_at")), now=clock.nowMillis();
        if(observed<now-positionAge || observed>now || Set.of("SHORT_LOST","TERMINATED","MERGE","SPLIT").contains(text(target,"track_status")))
            return "TARGET_POSITION_STALE";
        if(!supportsClass(target)) return "EO_CLASS_UNSUPPORTED";
        if(mode(target)==null || target.get("owner_org_id")==null || target.get("district_id")==null) return "EO_DEVICE_UNAVAILABLE";
        return null;
    }
    public static boolean supportsClass(Map<String,Object> target) {
        return target!=null && Set.of("UAV","BIRD").contains(text(target,"object_type_code"));
    }
    public List<DemandReason> demand(String target) {
        long now=clock.nowMillis(), cutoff=now-demandAge;
        List<DemandReason> result=new ArrayList<>();
        var alarms=repository.alarms(target,cutoff,now);
        if(alarms.stream().anyMatch(a -> !"CONFIRMED".equals(text(a,"state_code")))) result.add(new DemandReason("ALARM_VERIFY","告警待核实，需要目标观察"));
        if(alarms.stream().anyMatch(a -> "CONFIRMED".equals(text(a,"state_code"))
                && departure.assess(text(a,"event_id"),millis(a.get("basis_at")),now)!=com.uav.lowaltitude.modules.alarm.application.PilotDepartureWatch.Presence.LEFT))
            result.add(new DemandReason("ALARM_OBSERVE","已确认告警，需要持续观察"));
        var evaluation=repository.evaluation(target);
        if(evaluation!=null && millis(evaluation.get("observed_at"))>=cutoff && millis(evaluation.get("observed_at"))<=now
                && millis(evaluation.get("evaluated_at"))<=now) {
            String status=text(evaluation,"legal_status");
            if(Set.of("ILLEGAL","ABNORMAL").contains(status)) result.add(new DemandReason("LEGALITY_OBSERVE","当前违法或异常研判，需要目标观察"));
            // No visual-evidence reason is defined in the current rule protocol. UNDETERMINED is not a trigger.
        }
        if(repository.risks(target,cutoff,now).stream().anyMatch(EoTrackingPolicy::visualRisk))
            result.add(new DemandReason("RISK_OBSERVE","非气象飞行风险，需要目标观察"));
        return List.copyOf(result);
    }
    static boolean visualRisk(Map<String,Object> risk) {
        String type=text(risk,"risk_type"), reason=text(risk,"reason_code");
        if("WEATHER".equals(type) || reason.matches(".*(MISSING|UNKNOWN|UNAVAILABLE|UNDETERMINED|NOT_PROVIDED).*")) return false;
        if("SPACE_OBJECT_NEAR_ROUTE".equals(reason) && "LOW".equals(text(risk,"severity"))) return false;
        return Set.of("ROUTE_DEVIATION","AIRSPACE_CONFLICT","SPACE_OBJECT_IN_CORRIDOR","SPACE_OBJECT_IN_AIRPORT_ZONE",
                "PROHIBITED_AIRSPACE_OVERLAP","COLLISION","COLLISION_RISK").contains(reason)
                || Set.of("HIGH","CRITICAL").contains(text(risk,"severity"));
    }
    public Map<String,Object> bootstrap(Map<String,Object> target) {
        double[] p=position(target); String id=text(target,"target_id");
        boolean bird="BIRD".equals(text(target,"object_type_code"));
        double speed=number(target.get("speed_mps")), angle=Math.toRadians(number(target.get("heading_deg")));
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("longitude",p[0]);data.put("latitude",p[1]);data.put("altitude",number(target.get("altitude_amsl_m")));
        data.put("speedX",speed*Math.cos(angle));data.put("speedY",speed*Math.sin(angle));data.put("speedZ",0d);
        data.put("dataId",id);data.put("length",0d);data.put("width",0d);data.put("height",0d);
        data.put("objectType",bird?40:30);data.put("probability",number(target.get("classification_confidence")));
        return Map.of("objectData",data,"aiData",Map.of("className",bird?"bird":"drone","isDetect",1,"isTrack",1),
                "extention",Map.of("mode","full-auto","bootstrapSourceId",id,"bootstrapSourceType",0,"msgId",UUID.randomUUID().toString()));
    }
    public static String mode(Map<String,Object> target) {
        return switch(text(target,"source_mode")) {case "mock","replay" -> "replay";case "live" -> "live";default -> null;};
    }
    private static double number(Object value) {return value instanceof Number n?n.doubleValue():0d;}
    static double[] position(Map<String,Object> target) {
        if(target.get("srid") instanceof Number n && n.intValue()!=4326) return null;
        String value=text(target,"position");
        var m=java.util.regex.Pattern.compile("(?:SRID=4326;)?POINT\\s*\\(\\s*([-+0-9.Ee]+)\\s+([-+0-9.Ee]+)\\s*\\)").matcher(value);
        if(!m.matches()) return null;
        try {double x=Double.parseDouble(m.group(1)),y=Double.parseDouble(m.group(2));
            return Double.isFinite(x)&&Double.isFinite(y)&&Math.abs(x)<=180&&Math.abs(y)<=90?new double[]{x,y}:null;
        } catch(NumberFormatException ex) {return null;}
    }
    public static long millis(Object value) {
        if(value instanceof Timestamp t) return t.getTime();
        if(value instanceof OffsetDateTime t) return t.toInstant().toEpochMilli();
        if(value instanceof java.time.Instant t) return t.toEpochMilli();
        return 0;
    }
    public static String text(Map<String,Object> row,String field) {Object v=row.get(field);return v==null?"":String.valueOf(v);}
    public record DemandReason(String code,String label) { }
}
