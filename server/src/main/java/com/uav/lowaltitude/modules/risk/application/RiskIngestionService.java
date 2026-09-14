package com.uav.lowaltitude.modules.risk.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.IngestRow;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.IngestionPlanRow;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.RiskRow;
import com.uav.lowaltitude.platform.api.ApiException;

/** 仅供可信适配器调用；这里没有 HTTP 创建入口，合法性结论也不会自动制造风险。 */
@Service
public class RiskIngestionService {
    private static final Set<String> SEVERITIES=Set.of("LOW","MEDIUM","HIGH","CRITICAL");
    private static final Set<String> MODES=Set.of("mock","replay","live");
    private final RiskRepository repository;
    private final com.uav.lowaltitude.platform.number.BusinessNumberService numbers;
    public RiskIngestionService(RiskRepository repository, com.uav.lowaltitude.platform.number.BusinessNumberService numbers){this.repository=repository;this.numbers=numbers;}

    @Transactional
    public String ingest(TrustedRiskFact fact){
        validate(fact);
        IngestionPlanRow plan=repository.ingestionPlan(fact.planId());
        if(plan==null)throw invalid("计划不存在");
        if(!plan.routeVersionId().equals(fact.routeVersionId()))throw invalid("计划与航线版本不一致");
        // 以来源根行串行化同一 source 的入库，避免并发唯一冲突把 PostgreSQL 事务置为 aborted 后再查询。
        if(!repository.lockSource(fact.sourceId(),fact.sourceMode()))throw invalid("来源无效或未启用");
        if(fact.assessmentId()!=null&&!repository.assessmentMatches(fact.assessmentId(),fact.planId(),fact.routeVersionId()))throw invalid("研判不属于同一计划与航线版本");
        if(fact.targetId()!=null&&!repository.targetMatchesScope(fact.targetId(),plan.ownerOrgId(),plan.districtId()))throw invalid("目标不属于计划完整范围");
        if(fact.trackId()!=null&&!repository.trackMatches(fact.trackId(),fact.targetId(),plan.ownerOrgId(),plan.districtId()))throw invalid("轨迹不属于目标或计划完整范围");
        String id=UUID.randomUUID().toString();
        RiskRow existing=repository.findBySource(fact.sourceId().trim(),fact.sourceRiskId().trim());
        if(existing!=null)return existing.riskId();
            // AGL/AMSL 缺少可信换算时只能保存 UNKNOWN；入库绝不能凭数值推导“安全”。
            repository.insert(new IngestRow(id,fact.sourceId().trim(),fact.sourceRiskId().trim(),fact.planId().trim(),fact.routeVersionId().trim(),
                    nullable(fact.assessmentId()),nullable(fact.targetId()),nullable(fact.trackId()),fact.riskType().trim(),fact.severity().trim(),
                    fact.reasonCode().trim(),fact.reasonText().trim(),fact.occurredAt(),fact.receivedAt(),fact.observedAltitudeM(),
                    nullable(fact.observedAltitudeDatum()),fact.sourceMode().trim(),plan.ownerOrgId(),plan.districtId(),
                    // 来源编号能给人看就沿用，技术键（C04:规则:计划:目标:窗口）才取平台编号。
                    com.uav.lowaltitude.platform.number.BusinessNumberService.readable(fact.sourceRiskId())?null
                        :numbers.next(com.uav.lowaltitude.platform.number.BusinessNumberService.RISK,fact.receivedAt().toInstant())));
        return id;
    }

    private static void validate(TrustedRiskFact f){
        if(f==null)throw invalid("风险事实不能为空");
        required(f.sourceId(),36,"来源");required(f.sourceRiskId(),128,"来源风险 ID");required(f.planId(),36,"计划");
        required(f.routeVersionId(),36,"航线版本");required(f.riskType(),64,"风险类型");required(f.reasonCode(),64,"风险原因");
        required(f.reasonText(),1000,"风险依据");
        if(f.receivedAt()==null)throw invalid("接收时间不能为空");
        if(f.severity()==null||!SEVERITIES.contains(f.severity().trim()))throw invalid("风险等级无效");
        if(f.sourceMode()==null||!MODES.contains(f.sourceMode().trim()))throw invalid("来源模式无效");
        if((f.observedAltitudeM()==null)!=(f.observedAltitudeDatum()==null))throw invalid("高度值与基准必须同时提供或同时未知");
        if(f.observedAltitudeDatum()!=null&&!Set.of("AGL","AMSL").contains(f.observedAltitudeDatum().trim()))throw invalid("高度基准无效");
        if(f.trackId()!=null&&(f.targetId()==null||f.targetId().isBlank()))throw invalid("轨迹必须关联同一目标");
    }
    private static void required(String value,int max,String field){if(value==null||value.trim().isEmpty()||value.trim().length()>max)throw invalid(field+"无效");}
    private static String nullable(String value){return value==null||value.isBlank()?null:value.trim();}
    private static ApiException invalid(String message){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_RISK_FACT",message);}

    public record TrustedRiskFact(String sourceId,String sourceRiskId,String planId,String routeVersionId,String assessmentId,
            String targetId,String trackId,String riskType,String severity,String reasonCode,String reasonText,OffsetDateTime occurredAt,
            OffsetDateTime receivedAt,BigDecimal observedAltitudeM,String observedAltitudeDatum,String sourceMode){ }
}
