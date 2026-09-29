package com.uav.lowaltitude.modules.airspace.application;

import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uav.lowaltitude.modules.airspace.infrastructure.AirspaceWriteRepository;
import com.uav.lowaltitude.modules.airspace.infrastructure.UpstreamAirspaceRepository;
import com.uav.lowaltitude.modules.airspace.infrastructure.UpstreamAirspaceRepository.Delivery;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class UpstreamAirspaceService {
    public static final String MOCK_SOURCE="local-airspace-upstream";
    private static final Set<String> FIELDS=Set.of("message_id","revision","action","airspace_no","name","kind_code",
        "boundary","min_altitude_m","max_altitude_m","altitude_datum","valid_from","valid_to","change_reason",
        "owner_org_id","district_id","effective_at");
    private static final Set<String> WITHDRAW_FIELDS=Set.of("message_id","revision","action","airspace_no","effective_at","change_reason");
    private final AirspaceWriteService writes;
    private final AirspaceWriteRepository airspaces;
    private final UpstreamAirspaceRepository deliveries;
    private final DeviceAccessPolicy interfaces;
    private final AccessControlService access;
    private final ObjectMapper json;
    private final AuditService audit;
    private final AppClock clock;
    private final String liveSource, liveUser;
    public UpstreamAirspaceService(AirspaceWriteService writes,AirspaceWriteRepository airspaces,UpstreamAirspaceRepository deliveries,
            DeviceAccessPolicy interfaces,AccessControlService access,ObjectMapper json,AuditService audit,AppClock clock,
            @Value("${app.airspace.upstream.source-id:}") String liveSource,@Value("${app.airspace.upstream.user-id:}") String liveUser) {
        this.writes=writes;this.airspaces=airspaces;this.deliveries=deliveries;this.interfaces=interfaces;this.access=access;
        this.json=json;this.audit=audit;this.clock=clock;this.liveSource=liveSource;this.liveUser=liveUser;
    }
    @Transactional public Map<String,Object> receiveLive(String raw) {
        var actor=interfaces.requireInterfacesOperate();
        if(liveSource.isBlank()||liveUser.isBlank())throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"UPSTREAM_NOT_CONFIGURED","尚未配置上级空域来源及接收专用账号");
        if(!actor.userId().equals(liveUser))throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","当前账号未绑定上级空域接收接口");
        return receive(raw,liveSource,"live");
    }
    @Transactional public Map<String,Object> receiveMock(String raw) {
        interfaces.requireInterfacesOperate();
        return receive(raw,MOCK_SOURCE,"mock");
    }
    @Transactional(readOnly=true) public Map<String,Object> mockContext() {
        interfaces.requireInterfacesRead();
        var decision=access.require(PermissionCode.AIRSPACE_READ);
        return Map.of("scopes",deliveries.scopes(decision),"items",deliveries.recent(MOCK_SOURCE,decision).stream().map(d->{
            var result=receipt(d,"mock");result.put("payload",tree(d.payload()));return result;
        }).toList());
    }
    private Map<String,Object> receive(String raw,String sourceId,String mode) {
        var decision=writes.requireManage();
        if(raw!=null&&raw.length()>120000)throw bad("空域报文不能超过120000字符");
        ObjectNode body=(ObjectNode)writes.strictObject(raw,FIELDS);
        String message=AirspaceWriteService.requiredText(body,"message_id",64);
        if(!message.matches("[A-Za-z0-9_-]{1,64}"))throw bad("消息编号格式无效");
        JsonNode revisionNode=body.get("revision");
        if(revisionNode==null||!revisionNode.isIntegralNumber()||!revisionNode.canConvertToLong()||revisionNode.longValue()<1)throw bad("revision 必须为正整数");
        long revision=revisionNode.longValue();
        String action=AirspaceWriteService.requiredText(body,"action",16), no=AirspaceWriteService.requiredText(body,"airspace_no",64);
        if(!Set.of("UPSERT","WITHDRAW").contains(action))throw bad("action 必须为 UPSERT 或 WITHDRAW");
        String reason=AirspaceWriteService.requiredText(body,"change_reason",256);
        // One source row serializes its message check, business update and receipt commit, including first insertion.
        var source=deliveries.lockSource(sourceId);
        if(source==null||!source.enabled()||!mode.equals(source.mode())||!"AIRSPACE_PUSH_V1".equals(source.protocol()))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"UPSTREAM_SOURCE_UNAVAILABLE","上级空域来源未启用或来源模式/协议不匹配");
        Delivery old=deliveries.message(sourceId,message);
        if(old!=null) {
            if(airspaces.findAirspace(old.airspaceId(),AirspaceWriteService.scopeUser(decision))==null)throw missing();
            if(!tree(old.payload()).equals(body))throw conflict("SOURCE_MESSAGE_CONFLICT","同一消息编号的内容已变化");
            return receipt(old,mode);
        }
        String id=airspaces.findAirspaceIdByNo(no);
        var head=id==null?null:airspaces.lockAirspace(id,AirspaceWriteService.scopeUser(decision));
        if(id!=null&&(head==null||!deliveries.sourceMatches(id,sourceId,mode)))throw missing();
        Delivery latest=id==null?null:deliveries.latest(sourceId,id);
        if(latest!=null&&revision<=latest.revision())throw conflict("UPSTREAM_REVISION_CONFLICT","下发版本必须大于已接收版本");
        String versionId;
        long effective;
        if("UPSERT".equals(action)) {
            ObjectNode data=body.deepCopy();data.remove(List.of("message_id","revision","action"));
            var parsed=writes.parseCreate(data.toString());
            if(!deliveries.scopeAllowed(decision,parsed.ownerOrgId(),parsed.districtId()))throw missing();
            effective=parsed.validFrom().toEpochMilli();
            if(latest!=null&&effective<=latest.effectiveAt())throw conflict("VERSION_OVERLAP","生效时间必须晚于上次下发的生效/撤销时间");
            String key="upstream-"+sourceId+"-"+message;
            if(head==null) {
                var created=writes.createUpstream(data.toString(),key,sourceId,mode);
                id=created.airspaceId();versionId=created.airspaceVersionId();
            } else {
                if(!head.name().equals(parsed.name())||!head.ownerOrgId().equals(parsed.ownerOrgId())||!head.districtId().equals(parsed.districtId()))
                    throw conflict("UPSTREAM_IDENTITY_CONFLICT","更新须保持原空域名称和归属");
                data.remove(List.of("airspace_no","name","owner_org_id","district_id"));data.put("expected_version",head.version());
                versionId=writes.addUpstreamVersion(id,data.toString(),key).airspaceVersionId();
            }
        } else {
            writes.strictObject(raw,WITHDRAW_FIELDS);
            if(head==null||latest==null)throw missing();
            if("WITHDRAW".equals(latest.action()))throw conflict("INVALID_TRANSITION","空域已撤销，重复发送请使用原消息编号");
            Instant at=AirspaceWriteService.requiredTime(body,"effective_at");effective=at.toEpochMilli();
            var version=airspaces.findLatestVersion(id);
            if(!at.isAfter(version.validFrom())||effective<=latest.effectiveAt())throw conflict("VERSION_OVERLAP","撤销时间必须晚于最新版本生效时间");
            if(version.validTo()!=null&&!at.isBefore(version.validTo()))throw conflict("INVALID_TRANSITION","撤销时间必须早于原失效时间");
            if(airspaces.closeVersion(version.airspaceVersionId(),at)!=1||airspaces.bumpAirspaceVersion(id,head.version(),clock.now())!=1)
                throw AirspaceWriteService.versionConflict();
            versionId=version.airspaceVersionId();
        }
        var actor=AuthContext.require();
        Delivery accepted=new Delivery(sourceId,message,id,versionId,revision,action,effective,body.toString(),clock.nowMillis());
        deliveries.insert(accepted,actor.userId());
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"airspace","airspace_upstream_received","airspace",id,
            "source="+sourceId+"; message="+message+"; revision="+revision+"; action="+action+"; reason="+reason,"SUCCESS","","");
        return receipt(accepted,mode);
    }
    private Map<String,Object> receipt(Delivery d,String mode) {
        var body=tree(d.payload());
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("message_id",d.messageId());result.put("airspace_id",d.airspaceId());result.put("airspace_no",body.path("airspace_no").asText());
        result.put("airspace_version_id",d.versionId());result.put("revision",d.revision());result.put("action",d.action());
        result.put("state","ACCEPTED");result.put("source_mode",mode);result.put("received_at",d.receivedAt());
        return result;
    }
    private JsonNode tree(String text) { try{return json.readTree(text);}catch(Exception e){throw new IllegalStateException("空域回执内容无效",e);} }
    private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
    private static ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","空域或归属不存在、不在权限范围或不属于当前来源");}
}
