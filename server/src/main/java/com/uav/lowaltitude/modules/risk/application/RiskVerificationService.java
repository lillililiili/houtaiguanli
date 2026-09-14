package com.uav.lowaltitude.modules.risk.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.risk.api.RiskDtos.PageDto;
import com.uav.lowaltitude.modules.risk.api.RiskDtos.RiskDto;
import com.uav.lowaltitude.modules.risk.api.RiskDtos.VerificationDto;
import com.uav.lowaltitude.modules.risk.api.RiskDtos.VerifyRequest;
import com.uav.lowaltitude.modules.risk.domain.RiskState;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.RiskRow;
import com.uav.lowaltitude.modules.risk.infrastructure.RiskRepository.VerificationRow;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class RiskVerificationService {
    private final AccessControlService access; private final RiskRepository repository; private final RiskReadService read;
    private final IdempotencyGuard idempotency; private final AppClock clock; private final AuditService audit;
    private final ObjectMapper objectMapper;
    public RiskVerificationService(AccessControlService access,RiskRepository repository,RiskReadService read,
            IdempotencyGuard idempotency,AppClock clock,AuditService audit,ObjectMapper objectMapper){this.access=access;this.repository=repository;this.read=read;this.idempotency=idempotency;this.clock=clock;this.audit=audit;this.objectMapper=objectMapper;}

    @Transactional(readOnly=true)
    public PageDto<VerificationDto> history(String riskId,MultiValueMap<String,String> values){
        AccessDecision decision=access.require(PermissionCode.RISK_READ);
        Page page=page(values); String id=RiskReadService.id(riskId);
        if(repository.find(id,decision)==null)throw RiskReadService.notFound();
        long total=repository.countVerifications(id,decision);
        return new PageDto<>(repository.verifications(id,decision,page.offset(),page.size).stream().map(RiskVerificationService::dto).toList(),page.page,page.size,total);
    }

    @Transactional
    public RiskDto verify(String riskId,String rawRequest,String key){
        // 两个动作权限均先于 ID、请求体、对象与幂等解析，避免错误次序泄露存在性或重放事实。
        AccessDecision readDecision=access.require(PermissionCode.RISK_READ);
        access.require(PermissionCode.RISK_VERIFY);
        VerifyRequest request=parse(rawRequest);
        String id=RiskReadService.id(riskId);
        String conclusion=conclusion(request),note=note(request); long expected=version(request);
        RiskRow row=repository.lock(id,readDecision); if(row==null)throw RiskReadService.notFound();
        idempotency.claim(key,operation(id,conclusion,note,expected));
        if(row.version()!=expected)throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","风险已被其他操作更新");
        String next=RiskState.next(row.state(),conclusion);
        OffsetDateTime at=clock.now().atOffset(ZoneOffset.UTC);
        // 条件写失败说明锁外仍发生竞争；不允许追加与实际状态不一致的核验历史。
        if(repository.update(id,expected,next,at)!=1)throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","风险已被其他操作更新");
        AuthUser actor=AuthContext.require();
        repository.appendVerification(UUID.randomUUID().toString(),id,conclusion,note,row.state(),next,expected,actor.userId(),at);
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"risk","risk_verified","flight_risk",id,
                "conclusion="+conclusion+"; version="+(expected+1),"SUCCESS","","");
        return read.dto(new RiskRow(row.riskId(),row.sourceRiskId(),row.planId(),row.routeVersionId(),row.assessmentId(),row.targetId(),row.trackId(),
                row.riskType(),row.severity(),next,row.reasonCode(),row.reasonText(),row.occurredAt(),row.receivedAt(),row.observedAltitudeM(),
                row.observedAltitudeDatum(),row.heightRelation(),row.sourceCode(),row.sourceMode(),row.ownerOrgId(),row.districtId(),row.createdAt(),at,expected+1,
                row.sourceName(),row.ownerOrgName(),row.districtName(),row.planNo(),row.targetNo(),row.riskNo()));
    }

    private VerifyRequest parse(String rawRequest){
        if(rawRequest==null||rawRequest.isBlank())throw invalidRequest();
        try(JsonParser parser=objectMapper.getFactory().createParser(rawRequest)){
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node=objectMapper.readTree(parser);
            if(node==null||!node.isObject()||node.size()!=3
                    ||!node.has("conclusion")||!node.has("note")||!node.has("expected_version")
                    ||!node.get("conclusion").isTextual()||!node.get("note").isTextual()
                    ||!node.get("expected_version").isIntegralNumber()||!node.get("expected_version").canConvertToLong()
                    ||parser.nextToken()!=null)throw invalidRequest();
            return new VerifyRequest(node.get("conclusion").textValue(),node.get("note").textValue(),node.get("expected_version").longValue());
        }catch(java.io.IOException ex){throw invalidRequest();}
    }

    private static Page page(MultiValueMap<String,String> values){
        if(!values.keySet().stream().allMatch(Set.of("page","size")::contains))throw bad("参数无效");
        int page=integer(values,"page",1),size=integer(values,"size",20);if(page<1||size<1||size>100)throw bad("分页参数无效");return new Page(page,size);
    }
    private static int integer(MultiValueMap<String,String> values,String name,int fallback){if(!values.containsKey(name))return fallback;
        List<String> found=values.get(name);if(found==null||found.size()!=1||found.get(0)==null||found.get(0).isBlank())throw bad("分页参数无效");
        try{return Integer.parseInt(found.get(0));}catch(NumberFormatException ex){throw bad("分页参数无效");}}
    private static String conclusion(VerifyRequest request){String value=request==null||request.conclusion()==null?"":request.conclusion().trim();if(!Set.of("CONFIRMED","EXCLUDED").contains(value))throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CONCLUSION","核验结论无效");return value;}
    private static String note(VerifyRequest request){String value=request==null||request.note()==null?"":request.note().trim();if(value.isEmpty()||value.length()>1000)throw bad("核验说明长度必须为1至1000");return value;}
    private static long version(VerifyRequest request){if(request==null||request.expectedVersion()==null||request.expectedVersion()<0)throw bad("expected_version 无效");return request.expectedVersion();}
    private static VerificationDto dto(VerificationRow row){return new VerificationDto(row.historyId(),row.version(),row.previousState(),
            row.resultingState(),row.conclusion(),row.note(),row.actorId(),row.createdAt().toInstant().toEpochMilli(),row.actorName());}
    private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
    private static ApiException invalidRequest(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","请求体无效");}
    private static String operation(String id,String conclusion,String note,long expected){
        // 不能用分隔符直接拼字段：ID/说明可含同一分隔符，会把不同请求误识别为 replay。长度前缀覆盖全部业务字段且无歧义。
        return framed("flight_risk")+framed(id)+framed(conclusion)+framed(note)+framed(Long.toString(expected));
    }
    private static String framed(String value){return value.getBytes(StandardCharsets.UTF_8).length+":"+value;}
    private record Page(int page,int size){int offset(){try{return Math.multiplyExact(page-1,size);}catch(ArithmeticException ex){throw bad("分页参数无效");}}}
}
