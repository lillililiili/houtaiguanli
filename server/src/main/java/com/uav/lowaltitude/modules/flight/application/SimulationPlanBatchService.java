package com.uav.lowaltitude.modules.flight.application;

import java.util.TreeSet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.flight.api.SimulationPlanBatchController.*;
import com.uav.lowaltitude.modules.flight.infrastructure.SimulationPlanBatchRepository;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.config.SimulationPolicy;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
@Profile(SimulationPolicy.PROFILE)
public class SimulationPlanBatchService {
    private final DeviceAccessPolicy interfaces;
    private final AccessService access;
    private final FlightReadService flights;
    private final SimulationPlanBatchRepository repository;
    private final ObjectMapper json;
    private final AppClock clock;
    private final AuditService audit;
    public SimulationPlanBatchService(DeviceAccessPolicy interfaces,AccessService access,FlightReadService flights,
            SimulationPlanBatchRepository repository,ObjectMapper json,AppClock clock,AuditService audit) {
        this.interfaces=interfaces;this.access=access;this.flights=flights;this.repository=repository;
        this.json=json;this.clock=clock;this.audit=audit;
    }
    @Transactional
    public Activation activate(Input input) {
        var actor=interfaces.requireInterfacesOperate();
        access.requireTuple(input.ownerOrgId(),input.districtId());
        if(input.startedAt()>clock.nowMillis()+30000)throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","模拟批次开始时间不能在未来");
        if(!repository.lockScope(input.ownerOrgId(),input.districtId()))
            throw new ApiException(HttpStatus.NOT_FOUND,"SIMULATION_SCOPE_NOT_FOUND","模拟批次单位或区域不存在或已停用");
        var plans=new TreeSet<>(input.planIds());var history=new TreeSet<>(input.historicalPlanIds());
        history.removeAll(plans);
        var all=new TreeSet<>(plans);all.addAll(history);
        for(String id:all) {
            flights.flightPlan(id); // Independently enforce flight:read and the full data-scope tuple.
            if(!repository.localReplayPlan(id,input.ownerOrgId(),input.districtId()))
                throw new ApiException(HttpStatus.CONFLICT,"SIMULATION_PLAN_REQUIRED","批次只能关联本单位、区域的本地 replay 模拟任务");
        }
        Input normalized=new Input(input.batchId(),input.startedAt(),input.ownerOrgId(),input.districtId(),plans.stream().toList(),history.stream().toList());
        String body;
        try { body=json.writeValueAsString(normalized); } catch(JsonProcessingException e) { throw new IllegalStateException(e); }
        var previous=repository.find(input.batchId());
        var current=repository.current(input.ownerOrgId(),input.districtId());
        if(previous!=null && !previous.body().equals(body))throw conflict("同一模拟批次的任务清单已变化");
        if(current!=null && !current.id().equals(input.batchId()) && (previous!=null || current.startedAt()>=input.startedAt()))
            throw conflict("已有更新的模拟批次，不能重新激活旧批次");
        if(previous==null) {
            repository.insert(input.batchId(),input.ownerOrgId(),input.districtId(),input.startedAt(),clock.nowMillis(),actor.userId(),body);
            plans.forEach(id->repository.member(input.batchId(),id,true));
            history.forEach(id->repository.member(input.batchId(),id,false));
            repository.activate(input.ownerOrgId(),input.districtId(),input.batchId());
            audit.record(actor.userId(),actor.account(),"simulation_plan_batch_activate","simulation_plan_batch",input.batchId(),
                "切换当前模拟任务批次；原批次="+(current==null?"无":current.id())+"；当前任务="+plans.size()+"；登记历史任务="+history.size(),null);
        }
        return new Activation(input.batchId(),plans.size(),history.size());
    }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,"SIMULATION_BATCH_CONFLICT",message); }
}
