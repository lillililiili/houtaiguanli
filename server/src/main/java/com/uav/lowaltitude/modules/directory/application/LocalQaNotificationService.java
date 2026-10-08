package com.uav.lowaltitude.modules.directory.application;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.NotificationInput;
import com.uav.lowaltitude.modules.directory.api.LocalQaNotificationController.Input;
import com.uav.lowaltitude.modules.directory.api.LocalQaNotificationController.Setting;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository.SettingRow;
import com.uav.lowaltitude.modules.flight.application.FlightReadService;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.integrationconfig.application.LocalInterfaceSimulatorService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
@Profile("((local & qa) | test) & !prod & !production")
@ConditionalOnProperty(prefix="app.qa.notification-setup", name="enabled", havingValue="true")
public class LocalQaNotificationService {
    static final String ENDPOINT="local-qa-expiring-mock";
    private final AccessService access;
    private final DirectoryService directory;
    private final DirectoryRepository repo;
    private final FlightReadService flights;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    public LocalQaNotificationService(AccessService access,DirectoryService directory,DirectoryRepository repo,
            FlightReadService flights,IdempotencyGuard idempotency,AuditService audit,AppClock clock) {
        this.access=access;this.directory=directory;this.repo=repo;this.flights=flights;
        this.idempotency=idempotency;this.audit=audit;this.clock=clock;
    }
    private void authorize() {
        access.require("interfaces.op");access.require("notificationSettings.auth");access.require("organizations.auth");
        if(!"ALL".equals(AuthContext.require().scopeMode()))throw new ApiException(HttpStatus.FORBIDDEN,"QA_GLOBAL_SCOPE_REQUIRED","本地测试通道准备需要全局管理范围");
    }
    @Transactional(readOnly=true)
    public List<Setting> list() {
        authorize();var actor=AuthContext.require();var result=new ArrayList<Setting>();
        var scope=new AccessDecision(actor.userId(),ScopeMode.ALL);
        for(int page=1;;page++) { var batch=repo.settings(null,page,100,scope);batch.items().forEach(r->result.add(view(r)));
            if((long)page*100>=batch.total())break; }
        return List.copyOf(result);
    }
    @Transactional
    public Setting prepare(Input input,String key) {
        authorize();
        var plan=flights.flightPlan(input.planId());LocalInterfaceSimulatorService.requireSimulated(plan.sourceMode());
        var subjects=directory.subjects(input.planId());
        String purpose=input.purpose(), org=null, contact=null, binding=null, recipient=null;
        String route=purpose, id=switch(purpose) {
            case "RISK_NOTICE" -> "risk-superior";
            case "ADVISORY_SMS" -> "advisory-sms";
            case "ADVISORY_VOICE" -> "advisory-voice";
            default -> null;
        };
        if(id!=null) {
            if(input.contactId()!=null)throw bad("全局模拟通道不接受指定联系人");
            if("RISK_NOTICE".equals(purpose))recipient=NotificationDirectoryService.SUPERIOR_RECIPIENT;
            else if(!"LINKED".equals(subjects.associationStatus()))throw conflict("请先关联有效飞手、运营单位和报送单位");
        } else {
            org="PLAN_FEEDBACK".equals(purpose)?subjects.reportingOrgId():subjects.operatorOrgId();
            if(org==null||input.contactId()==null)throw bad("请先关联任务单位并指定对应业务联系人");
            directory.organization(org);var c=directory.contact(input.contactId());
            String requiredRole=switch(purpose){case "PLAN_FEEDBACK"->"PLAN_LIAISON";case "DEVICE_MAINTENANCE"->"MAINTENANCE";default->"UNIT_LIAISON";};
            if(!org.equals(c.orgId())||!c.roles().contains(requiredRole)||!c.enabled()||(c.validUntil()!=null&&c.validUntil()<=clock.nowMillis()))
                throw conflict("联系人归属、业务角色或有效期不符合通知用途");
            contact=c.contactId();binding="PLAN_FEEDBACK".equals(purpose)?subjects.sourceBindingId():null;
            if(binding!=null&&!repo.bindingSourceEnabled(binding))throw conflict("任务报送来源关联不可用");
            route=purpose+":"+(binding==null?org:binding);
            id=UUID.nameUUIDFromBytes((ENDPOINT+":"+route).getBytes(StandardCharsets.UTF_8)).toString();
            if("PLAN_FEEDBACK".equals(purpose)) {
                var existing=repo.settingForBinding(binding);
                if(existing!=null)id=existing.id();
            }
            if("UAV_PUNISHMENT".equals(purpose))recipient=id;
        }
        var before=repo.setting(id);
        if(before!=null&&(!Set.of("NONE","MOCK").contains(before.channelType())
                ||(before.enabled()&&!ENDPOINT.equals(before.endpointRef()))))throw conflict("已有非本轮测试通道配置，不能覆盖");
        if(input.expectedVersion()!=(before==null?0:before.version()))throw conflict("配置版本已变化，请重新读取");
        idempotency.claim(key,"qa-notification:"+repo.encode(input));
        long now=clock.nowMillis();
        var body=new NotificationInput(purpose,org,contact,binding,"MOCK",ENDPOINT,true,now+20*60000L,input.expectedVersion());
        if(before==null) {
            try {
                if(recipient!=null&&"UAV_PUNISHMENT".equals(purpose))repo.insertRecipient(recipient,"QA模拟接收方："+directory.organization(org).name());
                repo.insertSetting(id,route,recipient,body,now);
            } catch(DuplicateKeyException e) { throw conflict("已有相同用途的配置，请读取后重试"); }
        } else if(repo.updateSetting(id,route,body,now)!=1)throw conflict("配置版本已变化，请重新读取");
        var actor=AuthContext.require();audit.record(actor.userId(),actor.account(),"local_qa_notification_prepare","local_interface",id,
                "准备20分钟MOCK通道; purpose="+purpose+"; plan_id="+input.planId(),null);
        return view(repo.setting(id));
    }
    private static Setting view(SettingRow row) {return new Setting(row.id(),row.purpose(),row.channelType(),row.enabled(),row.validUntil(),row.version(),row.recipientId());}
    private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
    private static ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,"QA_NOTIFICATION_CONFLICT",message);}
}
