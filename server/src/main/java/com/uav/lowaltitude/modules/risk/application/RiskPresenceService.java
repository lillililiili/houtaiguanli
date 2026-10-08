package com.uav.lowaltitude.modules.risk.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.assessment.engine.RuleParamLoader;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.risk.infrastructure.*;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.RiskRow;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskPresenceRepository.Observation;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * Physical presence and durable clearance never replace the risk verification/notification state.
 * 状态取值：CURRENT / CLEARED / EXPIRED（依据的有效时段已结束）/ NOT_STARTED / EXCLUDED / UNKNOWN。
 */
@Service
public class RiskPresenceService {
    private static final AccessDecision SYSTEM = new AccessDecision("system:risk-presence",ScopeMode.ALL);
    private final RiskPresenceRepository evidence;
    private final RiskRepository risks;
    private final SpaceRiskRepository spatial;
    private final WeatherRiskRepository weather;
    private final RuleParamLoader parameters;
    private final ObjectMapper json;
    private final AppClock clock;
    public RiskPresenceService(RiskPresenceRepository evidence, RiskRepository risks, SpaceRiskRepository spatial,
            WeatherRiskRepository weather, RuleParamLoader parameters, ObjectMapper json, AppClock clock) {
        this.evidence=evidence;this.risks=risks;this.spatial=spatial;this.weather=weather;this.parameters=parameters;this.json=json;this.clock=clock;
    }
    public record Presence(String status,String reason,Long observedAt,boolean eligibleForCurrentList) {
        public Presence(String status,String reason,Long observedAt) { this(status,reason,observedAt,true); }
    }
    private record Decision(Presence presence,Observation point,BigDecimal boundary,int freshSeconds,String ruleVersion,String freshVersion) { }
    public Presence read(RiskRow row,long now) {
        if ("EXCLUDED".equals(row.state())) return new Presence("EXCLUDED","风险已人工排除，原核验历史保留",null);
        var cleared=evidence.clearance(row.riskId());
        if(cleared!=null) return new Presence("CLEARED","新的有效实测位置已确认离开原风险范围，解除证据已保存",cleared.observedAt());
        if("WEATHER".equals(row.riskType())) {
            var fact=weather.find(row.riskId());
            if(fact==null)return unknown("缺少气象有效时段，当前影响待确认");
            if(fact.validFrom()>=fact.validTo()||fact.publishedAt()>now)return unknown("气象依据时间异常，当前影响待确认");
            if(now<fact.validFrom())return new Presence("NOT_STARTED","气象风险尚未进入有效时段",null);
            // 有效时段结束即"已过期"：依据本身写明了到什么时候，这不是"待确认"（ZT-47）。
            // 过期不等于解除：没有新的实测依据证明天气条件消失，历史与通知记录照旧保留。
            if(now>=fact.validTo())return new Presence("EXPIRED","气象依据的有效时段已结束，不再计入当前风险",fact.validTo());
            return new Presence("CURRENT","气象风险仍在有效时段内",null);
        }
        Decision decision=assess(row,now);
        // Only the existing trusted evaluation pipeline can freeze clearance; GET never mutates facts.
        return "CLEARED".equals(decision.presence.status())?unknown("新位置已在原风险范围外，等待空间评估确认并保存解除依据"):decision.presence;
    }
    @Transactional
    public void recordC04Clearances() {
        if(!evidence.available())return;
        long now=clock.nowMillis();
        for(String id:evidence.pendingC04()) {
            RiskRow row=risks.lock(id,SYSTEM);
            if(row==null||evidence.clearance(id)!=null)continue;
            Decision decision=assess(row,now);
            if("CLEARED".equals(decision.presence.status()))
                evidence.append(id,row.targetId(),row.routeVersionId(),decision.ruleVersion,decision.freshVersion,row.sourceMode(),
                        decision.point,decision.boundary,decision.freshSeconds,Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC));
        }
    }
    private Decision assess(RiskRow row,long now) {
        if("EXCLUDED".equals(row.state()))return blocked("原风险已排除，不追加物理解除结论");
        if(!"SPACE_OBJECT".equals(row.riskType())||!evidence.available()||!evidence.c04Fact(row.riskId()))
            return blocked("缺少当前持续或解除依据，风险状态待确认");
        if(evidence.predictionOnlyOrigin(row.riskId()))
            return new Decision(new Presence("UNKNOWN","原评估仅基于预测或插值位置，不计入当前风险；历史记录保留",null,false),null,null,0,null,null);
        if("replay".equals(row.sourceMode()))return blocked(risks.simulatedObservation(row)
                ?"模拟风险尚无有效的持续或解除依据，当前影响待确认"
                :"回放风险缺少独立的有效评估时钟，不能用当前时间确认解除");
        if(!Set.of("live","mock").contains(row.sourceMode())||!risks.targetReferenceVisible(row)||!risks.planReferenceVisible(row)||!risks.routeReferenceVisible(row))
            return blocked("原目标、任务或航线关联范围不可确认");
        var fact=spatial.findFact(row.riskId());
        var fresh=spatial.activeRuleSetVersion("LEGALITY-DEMO");
        if(fact==null||fresh==null)return blocked("缺少原风险规则或当前观测有效策略");
        try {
            var original=parameters.load(fact.ruleSetVersionId());
            var freshness=parameters.load(fresh.ruleSetVersionId());
            String boundaryStatus=original.paramStatus("C04","corridor_near_m"), freshnessStatus=freshness.paramStatus("C03","fresh_seconds");
            if(!allowedParameter(boundaryStatus,row.sourceMode())||!allowedParameter(freshnessStatus,row.sourceMode()))
                return blocked("规则或观测有效策略未经当前来源模式确认");
            int seconds=freshness.integer("C03","fresh_seconds");
            BigDecimal near=original.number("C04","corridor_near_m");
            if(seconds<=0||near.signum()<0)return blocked("风险范围或观测有效策略参数无效");
            Observation p=evidence.observation(row.targetId(),row.routeVersionId(),row.planId());
            if(p==null||p.observedAt()==null||p.receivedAt()==null||p.latestAt()==null||p.distance()==null||p.halfWidth()==null||p.halfWidth().signum()<0)
                return blocked("缺少实测位置或原航线几何，当前影响待确认");
            long observed=p.observedAt().toInstant().toEpochMilli(),received=p.receivedAt().toInstant().toEpochMilli();
            long originalAt=Math.max(row.receivedAt().toInstant().toEpochMilli(),fact.windowTo().toInstant().toEpochMilli());
            if(row.occurredAt()!=null)originalAt=Math.max(originalAt,row.occurredAt().toInstant().toEpochMilli());
            if(observed<=originalAt||observed>now||received<observed||received>now||now-observed>seconds*1000L||received-observed>seconds*1000L)
                return blocked("没有风险发生后时效内的新实测位置；停报、迟到或未来时间不能确认解除");
            if(!"MEAS".equals(p.kind())||!p.sameLocation()||p.peers()!=0||!p.observedAt().equals(p.latestAt())||!p.determined()||positionUnknown(p.unknownFields()))
                return blocked("最新位置缺少一致可靠实测依据，预测、矛盾或失联不能确认解除");
            if(!p.sourceScopeValid()||!row.sourceMode().equals(p.targetMode())||!row.sourceMode().equals(p.sourceMode())
                    ||(p.observationMode()!=null&&!row.sourceMode().equals(p.observationMode()))
                    ||!geometryMode(row.sourceMode(),p.planMode())||!geometryMode(row.sourceMode(),p.routeMode()))
                return blocked("位置来源或任务航线模式不匹配，不能串用模拟与实际依据");
            if(p.sourceObservedAt()==null||p.sourceReceivedAt()==null)return blocked("位置缺少可追溯的原始来源观测");
            long sourceObserved=p.sourceObservedAt().toInstant().toEpochMilli(),sourceReceived=p.sourceReceivedAt().toInstant().toEpochMilli();
            if(sourceObserved<=originalAt||sourceReceived<sourceObserved||now-sourceObserved>seconds*1000L
                    ||sourceReceived-sourceObserved>seconds*1000L)
                return blocked("原始位置观测不是风险发生后时效内的有效依据");
            if(p.accuracy()==null||p.accuracy().signum()<=0)return blocked("位置精度未知，无法确认已跨出风险边界");
            BigDecimal boundary=near.max(p.halfWidth());
            String status=p.distance().subtract(p.accuracy()).compareTo(boundary)>0?"CLEARED"
                    :p.distance().add(p.accuracy()).compareTo(boundary)<=0?"CURRENT":"UNKNOWN";
            String reason=switch(status){case "CLEARED"->"新的有效实测位置已明确离开原风险范围";case "CURRENT"->"新的有效实测位置仍在原风险范围内";default->"位置精度范围与风险边界重叠，当前影响待确认";};
            return new Decision(new Presence(status,reason,observed),p,boundary,seconds,fact.ruleSetVersionId(),fresh.ruleSetVersionId());
        } catch(ApiException invalid) { return blocked("风险规则或观测有效策略不可用，当前影响待确认"); }
    }
    private boolean positionUnknown(String text) {
        try {
            var node=json.readTree(text);if(node.isTextual())node=json.readTree(node.asText());
            if(!node.isArray())return true;
            for(var item:node) {
                String field=item.isTextual()?item.asText():item.path("field").asText("");
                if(field.isBlank()||Set.of("location","position","longitude","latitude","observed_at").contains(field))return true;
            }
            return false;
        } catch(Exception invalid){return true;}
    }
    private static boolean allowedParameter(String status,String mode){return "CONFIRMED".equals(status)||("mock".equals(mode)&&"DEMO".equals(status));}
    private static boolean geometryMode(String mode,String geometryMode){return mode.equals(geometryMode)||("mock".equals(mode)&&"live".equals(geometryMode));}
    private static Presence unknown(String reason){return new Presence("UNKNOWN",reason,null);}
    private static Decision blocked(String reason){return new Decision(unknown(reason),null,null,0,null,null);}
}
