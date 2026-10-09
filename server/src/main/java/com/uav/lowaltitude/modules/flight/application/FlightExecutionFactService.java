package com.uav.lowaltitude.modules.flight.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.flight.domain.FlightExecutionFacts.*;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightExecutionFactRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class FlightExecutionFactService {
    private final FlightExecutionFactRepository repo;private final DeviceAccessPolicy interfaces;private final AccessService access;
    private final SimulationPolicy simulation;private final ObjectMapper json;private final AppClock clock;private final Validator validator;private final AuditService audit;
    public FlightExecutionFactService(FlightExecutionFactRepository repo,DeviceAccessPolicy interfaces,AccessService access,SimulationPolicy simulation,ObjectMapper json,AppClock clock,Validator validator,AuditService audit){this.repo=repo;this.interfaces=interfaces;this.access=access;this.simulation=simulation;this.json=json;this.clock=clock;this.validator=validator;this.audit=audit;}
    @Transactional
    public Accepted acceptSimulation(Input input){
        simulation.requireSimulation();var actor=interfaces.requireInterfacesOperate();
        validate(input);var target=repo.lockTarget(input.targetId(),input.trackId());
        if(target==null)throw missing();access.requireTuple(target.org(),target.district());
        if(!Set.of("mock","replay").contains(target.mode()))throw new ApiException(HttpStatus.CONFLICT,"SIMULATION_SOURCE_MISMATCH","模拟入口不能写入真实目标执行事实");
        var result=store(input,target);
        if(!result.duplicate())audit.record(actor.userId(),actor.account(),"flight_execution_fact_received","flight_execution_fact",result.factId(),"独立模拟执行事实；target_id="+input.targetId(),null);
        return result;
    }
    /** Reserved for a trusted adapter; source identity is supplied by its authenticated connection, never a public DTO alone. */
    @Transactional
    public Accepted acceptFromSource(String authenticatedSource,Input input){
        validate(input);if(!Objects.equals(authenticatedSource,input.sourceId()))throw missing();
        var target=repo.lockTarget(input.targetId(),input.trackId());if(target==null)throw missing();
        simulation.requireSourceMode(target.mode());
        var accepted=store(input,target);
        if(!accepted.duplicate())audit.record(null,"execution-fact-adapter",null,"flight","flight_execution_fact_received","flight_execution_fact",accepted.factId(),"source_id="+authenticatedSource,"SUCCESS","","");
        return accepted;
    }
    private Accepted store(Input input,FlightExecutionFactRepository.Target target){
        if(!Objects.equals(input.sourceMode(),target.mode())||!Objects.equals(target.mode(),repo.sourceMode(input.sourceId())))throw new ApiException(HttpStatus.CONFLICT,"EXECUTION_SOURCE_MISMATCH","执行事实来源未启用或来源模式不一致");
        String payload;String hash;
        try{payload=json.writeValueAsString(input);hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
        var previous=repo.existing(input);
        if(previous!=null){if(!hash.equals(previous.hash()))throw conflict("同源同编号同版本内容冲突");return new Accepted(previous.id(),previous.mode(),true);}
        var latest=repo.latestMessage(input);
        if(latest!=null && (input.version()<=latest.version()||!Objects.equals(input.targetId(),latest.targetId())||!Objects.equals(input.trackId(),latest.trackId())||!Objects.equals(input.executionId(),latest.executionId())))throw conflict("更正必须递增版本，且不能改绑目标、轨迹或执行实例");
        String id=UUID.randomUUID().toString();repo.insert(id,input,target,clock.nowMillis(),hash,payload);return new Accepted(id,target.mode(),false);
    }
    private void validate(Input i){
        if(i==null||!validator.validate(i).isEmpty())throw bad("执行事实字段不完整或格式无效");
        if(i.validFrom()>=i.validTo()||i.validFrom()>clock.nowMillis())throw bad("执行事实有效时段无效");
        for(var event:Arrays.asList(i.takeoff(),i.landing()))if(event!=null && (event.occurredAt()<i.validFrom()||event.occurredAt()>=i.validTo()||event.occurredAt()>clock.nowMillis()))throw bad("起降事件时间必须在事实有效时段内且不能在未来");
        if(i.takeoff()!=null&&i.landing()!=null&&i.landing().occurredAt()<i.takeoff().occurredAt())throw bad("降落时间早于起飞时间");
        if(i.landing()!=null&&!"LANDED".equals(i.phase()))throw bad("有降落事件时执行阶段必须为已降落");
    }
    private static ApiException bad(String m){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",m);}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"EXECUTION_SUBJECT_NOT_FOUND","执行事实关联的目标或来源不可用");}
    private static ApiException conflict(String m){return new ApiException(HttpStatus.CONFLICT,"EXECUTION_FACT_CONFLICT",m);}
}
