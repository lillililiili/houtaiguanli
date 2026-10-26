package com.uav.lowaltitude.modules.automationrule.application;

import java.time.LocalTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.automationrule.api.ResponseOrderDtos.*;
import com.uav.lowaltitude.modules.automationrule.infrastructure.ResponseOrderRepository;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class ResponseOrderPolicyService {
    private final ResponseOrderRepository repo;
    private final AccessService access;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;
    public ResponseOrderPolicyService(ResponseOrderRepository repo,AccessService access,IdempotencyGuard idempotency,AuditService audit,AppClock clock,ObjectMapper json) {
        this.repo=repo;this.access=access;this.idempotency=idempotency;this.audit=audit;this.clock=clock;this.json=json;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Policy get(){read();return view(repo.policy(false));}
    @Transactional
    public Policy save(Input p,String key){
        read();access.requireBusinessData("responsePlans.auth");validate(p);
        var before=repo.policy(true);
        idempotency.claim(key,"response-order:"+encode(p));
        if(before.version()!=p.expectedVersion())throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","处置顺序已更新，请重新读取后保存");
        long now=clock.nowMillis();repo.update(p,now);var after=repo.policy(false);var actor=AuthContext.require();
        repo.history(UUID.randomUUID().toString(),after.version(),actor.userId(),actor.account(),now,encode(before),encode(after));
        audit.record(actor.userId(),actor.account(),"USER","responsePlans","response_order_changed","response_order_policy","uav",
                "version="+after.version()+"; before="+encode(before)+"; after="+encode(after),"SUCCESS","","");
        return view(after);
    }
    @Transactional(readOnly=true)
    public Page history(int page,int size){read();if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw bad("分页参数无效");
        return new Page(repo.history(page,size).stream().map(r->new Change(r.id(),r.version(),r.actor(),r.at(),decode(r.before()),decode(r.after()))).toList(),page,size,repo.historyCount());}
    private Policy view(Policy p){boolean manage=access.permissionCodesForUser(AuthContext.require().userId()).contains("responsePlans.auth");
        return new Policy(p.version(),p.enabled(),p.minimumRisk(),p.predictionEnabled(),p.observedEnabled(),p.scopeMode(),p.airspaceIds(),p.airspaceNames(),p.scheduleMode(),p.startTime(),p.endTime(),p.timezone(),p.maxWaitSeconds(),manage);}
    private void read(){access.requireBusinessData("responsePlans.read");if(!"ALL".equals(AuthContext.require().scopeMode()))throw new ApiException(HttpStatus.FORBIDDEN,"GLOBAL_RULE_SCOPE_REQUIRED","处置顺序需要平台全部数据范围");}
    private void validate(Input p){
        if(!Set.of("HIGH","MEDIUM").contains(p.minimumRisk())||!Set.of("ALL","AIRSPACES").contains(p.scopeMode())||!Set.of("ALL_DAY","DAILY").contains(p.scheduleMode())||!"Asia/Shanghai".equals(p.timezone()))throw bad("处置顺序选项无效");
        if(!p.predictionEnabled()&&!p.observedEnabled())throw bad("请至少选择一个适用阶段");
        if(p.maxWaitSeconds()<5||p.maxWaitSeconds()>300)throw bad("最长优先等待为5至300秒");
        try{LocalTime.parse(p.startTime());LocalTime.parse(p.endTime());}catch(RuntimeException invalid){throw bad("时间须为HH:mm");}
        if(!p.startTime().matches("\\d{2}:\\d{2}")||!p.endTime().matches("\\d{2}:\\d{2}"))throw bad("时间须为HH:mm");
        if("DAILY".equals(p.scheduleMode())&&p.startTime().equals(p.endTime()))throw bad("开始和结束时间必须不同");
        if(new HashSet<>(p.airspaceIds()).size()!=p.airspaceIds().size()||("AIRSPACES".equals(p.scopeMode())&&p.airspaceIds().isEmpty()))throw bad("请选择不重复的适用空域");
        for(String id:p.airspaceIds())if(!repo.airspaceExists(id))throw bad("所选空域不存在或已删除");
    }
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private Policy decode(String v){try{return json.readValue(v,Policy.class);}catch(Exception e){throw new IllegalStateException(e);}}
    private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
}
