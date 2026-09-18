package com.uav.lowaltitude.modules.responseplan.application;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import com.uav.lowaltitude.modules.responseplan.api.ResponsePlanDtos.*;
import com.uav.lowaltitude.modules.responseplan.infrastructure.ResponsePlanRepository;
import com.uav.lowaltitude.modules.responseplan.infrastructure.ResponsePlanRepository.BindingRow;
import com.uav.lowaltitude.modules.identity.application.*;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class ResponsePlanService {
    private final ResponsePlanRepository repo;
    private final AccessService access;
    private final AccessControlService businessAccess;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;
    public ResponsePlanService(ResponsePlanRepository repo,AccessService access,AccessControlService businessAccess,IdempotencyGuard idempotency,AuditService audit,AppClock clock,ObjectMapper json){this.repo=repo;this.access=access;this.businessAccess=businessAccess;this.idempotency=idempotency;this.audit=audit;this.clock=clock;this.json=json;}
    @Transactional(readOnly=true) public Page<Version> list(int page,int size){read();pages(page,size);return repo.list(page,size);}
    @Transactional(readOnly=true) public List<Version> versions(String id){read();var versions=repo.versions(id);if(versions.isEmpty())throw missing();return versions;}
    @Transactional(readOnly=true) public Page<AirspaceOption> options(int page,int size,String keyword){read();pages(page,size);if(keyword!=null&&keyword.length()>128)throw bad("查询名称最多128字");return repo.airspaces(page,size,keyword);}
    @Transactional public Version create(Input b,String key){write();validate(b);airspace(b.airspaceId());claim(key,"create",b);String plan=UUID.randomUUID().toString();repo.createPlan(plan,b.airspaceId(),clock.nowMillis());return insert(plan,b);}
    @Transactional public Version newVersion(String plan,Input b,String key){write();validate(b);var versions=repo.versions(plan);if(versions.isEmpty())throw missing();repo.lockPlan(plan);versions=repo.versions(plan);if(!versions.get(0).airspaceId().equals(b.airspaceId()))throw bad("新版本不能变更预案的归属空域");if(versions.stream().anyMatch(v->"DRAFT".equals(v.status())))throw conflict("DRAFT_EXISTS","已有草稿，请先完成该草稿");claim(key,"new-version:"+plan,b);return insert(plan,b);}
    private Version insert(String plan,Input b){String id=UUID.randomUUID().toString();repo.insert(id,plan,repo.nextRevision(plan),b,clock.nowMillis());record("response_plan_draft_created",id,"新建预案草稿");return require(id);}
    @Transactional public Version update(String id,Input b,String key){write();validate(b);Version v=require(id);if(!v.airspaceId().equals(b.airspaceId()))throw bad("不能变更预案的归属空域");if(b.expectedVersion()==null)throw bad("缺少当前版本号");claim(key,"update:"+id,b);if(repo.update(id,b,clock.nowMillis())!=1)throw conflict("VERSION_CONFLICT","草稿已更新或发布，请刷新后操作");record("response_plan_draft_updated",id,"previous_version="+v.version());return require(id);}
    @Transactional public Version publish(String id,Change b,String key){write();require(id);repo.lockVersion(id);Version v=require(id);if(v.validTo()!=null&&v.validTo()<=clock.nowMillis())throw conflict("PLAN_EXPIRED","已过期的草稿不能发布");claim(key,"publish:"+id,b);if(repo.publish(id,b.expectedVersion(),clock.nowMillis(),actor())!=1)throw conflict("VERSION_CONFLICT","预案已更新或不是草稿，请刷新后操作");record("response_plan_published",id,b.reason());return require(id);}
    @Transactional public Version withdraw(String id,Change b,String key){write();require(id);claim(key,"withdraw:"+id,b);if(repo.withdraw(id,b.expectedVersion(),clock.nowMillis(),b.reason())!=1)throw conflict("VERSION_CONFLICT","预案已更新或不是已发布版本，请刷新后操作");record("response_plan_withdrawn",id,b.reason());return require(id);}
    @Transactional(readOnly=true) public AirspacePlans forAirspace(String id,int page,int size,boolean admin){if(admin)read();else businessAccess.require(PermissionCode.AIRSPACE_READ);airspace(id);pages(page,size);return view(id,page,size);}
    @Transactional public AirspacePlans bind(String id,BindingInput b,String key){write();airspace(id);repo.lockAirspace(id);BindingRow before=repo.current(id);String expected=blank(b.expectedBindingId());if(!Objects.equals(before==null?null:before.id(),expected))throw conflict("BINDING_CONFLICT","空域关联已更新，请刷新后操作");
        String version=blank(b.versionId());
        if(version!=null){Version v=require(version);repo.lockVersion(version);v=require(version);
            if(!repo.sameScope(id,v.airspaceId()))throw conflict("PLAN_SCOPE_MISMATCH","预案只能关联到同一组织及区域内的空域");
            if(!"PUBLISHED".equals(v.status())||(v.validTo()!=null&&v.validTo()<=clock.nowMillis()))throw conflict("PLAN_UNAVAILABLE","只能关联已发布且未过期的预案版本");
            if(before!=null&&before.versionId().equals(version))throw conflict("BINDING_UNCHANGED","该版本已经关联此空域");
        }else if(before==null)throw conflict("BINDING_ABSENT","此空域没有当前关联");
        claim(key,"bind:"+id,b);long now=clock.nowMillis();if(before!=null)repo.end(before.id(),now,actor(),b.reason());
        if(version!=null)repo.bind(UUID.randomUUID().toString(),id,version,now,actor(),b.reason());
        record(version==null?"response_plan_unbound":"response_plan_bound",id,"previous_binding="+expected+"; version_id="+version+"; reason="+b.reason());return view(id,1,20);
    }
    private AirspacePlans view(String id,int page,int size){long now=clock.nowMillis();int active=repo.activeAirspaceVersions(id,now);var history=repo.history(id,page,size);return new AirspacePlans(id,now,"GUIDANCE_ONLY",binding(repo.current(id),now,active),new Page<>(history.items().stream().map(row->binding(row,now,active)).toList(),page,size,history.total()));}
    private Binding binding(BindingRow row,long now,int active){if(row==null)return null;Version v=require(row.versionId());String state,reason;
        if(row.endedAt()!=null){state="HISTORICAL";reason="已解除或替换关联，仅供历史查看";}
        else if("WITHDRAWN".equals(v.status())){state="WITHDRAWN";reason="预案已停用："+v.withdrawnReason();}
        else if(!"PUBLISHED".equals(v.status())){state="UNPUBLISHED";reason="预案尚未发布";}
        else if(now<v.validFrom()){state="SCHEDULED";reason="尚未到预案生效时间";}
        else if(v.validTo()!=null&&now>=v.validTo()){state="EXPIRED";reason="预案已超过有效期";}
        else if(active!=1){state=active==0?"AIRSPACE_INACTIVE":"UNKNOWN";reason=active==0?"空域当前没有有效规则版本":"空域有效版本重叠，无法确认适用性";}
        else {state="APPLICABLE";reason="已发布且处于有效期内，适用于当前空域";}
        return new Binding(row.id(),row.airspaceId(),v,row.at(),row.by(),row.reason(),row.endedAt(),row.endedBy(),row.endReason(),state,reason);
    }
    private Version require(String id){Version v=repo.get(id);if(v==null)throw missing();return v;}
    private void airspace(String id){if(!repo.visibleAirspace(id))throw missing();}
    private void read(){access.requireBusinessData("responsePlans.read");}
    private void write(){access.requireBusinessData("responsePlans.auth");}
    private void record(String action,String id,String detail){var u=AuthContext.require();audit.record(u.userId(),u.account(),u.roleCode(),"responsePlans",action,"response_plan",id,detail,"SUCCESS","","");}
    private String actor(){return AuthContext.require().account();}
    private void claim(String key,String operation,Object b){try{idempotency.claim(key,"response-plan:"+operation+":"+json.writeValueAsString(b));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
    private static void validate(Input b){if(b.validTo()!=null&&b.validTo()<=b.validFrom())throw bad("结束时间必须晚于开始时间");}
    private static void pages(int p,int s){if(p<1||s<1||s>100||(long)(p-1)*s>Integer.MAX_VALUE)throw bad("分页参数无效");}
    private static String blank(String v){return v==null||v.isBlank()?null:v.trim();}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"RESPONSE_PLAN_NOT_FOUND","预案或空域不存在，或不在当前数据范围内");}
    private static ApiException bad(String msg){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",msg);}
    private static ApiException conflict(String code,String msg){return new ApiException(HttpStatus.CONFLICT,code,msg);}
}
