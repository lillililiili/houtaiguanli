package com.uav.lowaltitude.modules.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.identity.api.SystemDtos.*;
import com.uav.lowaltitude.modules.identity.api.UnitDelegationDtos.*;
import com.uav.lowaltitude.modules.identity.domain.*;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.*;
import com.uav.lowaltitude.modules.identity.infrastructure.*;
import com.uav.lowaltitude.modules.identity.infrastructure.UnitDelegationMapper.Delegation;
import com.uav.lowaltitude.modules.identity.infrastructure.UnitDelegationMapper.Preview;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

@Service
public class UnitDelegationService {
    private final UnitDelegationMapper units;
    private final IdentityAdminMapper identity;
    private final UserMapper users;
    private final SessionMapper sessions;
    private final UserDataScopeService scopes;
    private final AccessService access;
    private final PasswordPolicy passwords;
    private final PasswordEncoder encoder;
    private final IdempotencyGuard keys;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;

    public UnitDelegationService(UnitDelegationMapper units, IdentityAdminMapper identity, UserMapper users,
            SessionMapper sessions, UserDataScopeService scopes, AccessService access, PasswordPolicy passwords,
            PasswordEncoder encoder, IdempotencyGuard keys, AuditService audit, AppClock clock, ObjectMapper json) {
        this.units=units; this.identity=identity; this.users=users; this.sessions=sessions; this.scopes=scopes;
        this.access=access; this.passwords=passwords; this.encoder=encoder; this.keys=keys;
        this.audit=audit; this.clock=clock; this.json=json;
    }

    public DelegationResponse get(String orgId) {
        platform();
        return detail(orgId);
    }

    @Transactional
    public PreviewResponse preview(String orgId, DelegationRequest request) {
        platform();
        lock(orgId);
        validateRequest(orgId,request);
        List<UserAdminRow> members=members(orgId);
        List<UserImpact> userImpacts=members.stream().map(u->new UserImpact(u.getUserId(),u.getAccount(),
                u.getName(),u.getRoleCode(),u.getRoleName(),DataScope.of(u.getScopeMode(),u.getScopeOrgRule()).name(),
                "OWN_ORG",false)).toList();
        LinkedHashSet<String> roleCodes=new LinkedHashSet<>();
        members.forEach(u->roleCodes.add(u.getRoleCode()));
        units.roles(orgId).forEach(r->roleCodes.add(r.getRoleCode()));
        List<RoleImpact> roleImpacts=roleCodes.stream().map(code-> {
            RoleRow r=identity.findRole(code);
            List<String> removed=new ArrayList<>();
            Map<String,PermissionAssignment> limits=permissionMap(request.permissions());
            for(PermissionRow p:identity.listPermissionsForRole(code)) {
                PermissionAssignment cap=limits.get(p.getPermissionCode());
                String nextLevel=cap==null?"NONE":(AccessService.level(p.getPermissionLevel())<=AccessService.level(cap.level())
                        ?p.getPermissionLevel():cap.level());
                List<String> changes=new ArrayList<>();
                if(!p.getPermissionLevel().equals(nextLevel))
                    changes.add(permissionLevelLabel(p.getPermissionLevel())+"→"+permissionLevelLabel(nextLevel));
                if(p.isMenuEnabled()&&(cap==null||!cap.menuEnabled()||AccessService.level(nextLevel)==0))
                    changes.add("菜单关闭");
                if(!changes.isEmpty()) removed.add(permissionLabel(p)+"："+String.join("；",changes));
            }
            Set<String> actions=actionSet(request.actions());
            identity.listActionsForRole(code).stream().filter(this::effectiveLegacyAction)
                    .filter(a->!actions.contains(a.getPermissionCode()))
                    .forEach(a->removed.add((a.getName()==null?a.getPermissionCode():a.getName())+"：允许→无权限"));
            return new RoleImpact(code,r.getName(),removed);
        }).toList();
        List<CrossOrgImpact> cross=crossImpacts(orgId,members);
        List<String> warnings=new ArrayList<>();
        if(!cross.isEmpty()) warnings.add("清单按人员当前读权限及原数据范围统计；纳管后这些跨单位记录将被本单位范围排除，实际业务访问还受来源与业务状态限制，历史数据与归属保持不变。");
        int links=units.crossOrgPlanLinks(orgId).size();
        if(links>0) warnings.add("本单位作为运营单位关联的 "+links+" 条任务归属其他单位，纳管后本单位账号不能查看这些任务；请由平台处理业务归属协调。");
        if(request.administratorUserId()==null) warnings.add("本次不设置单位管理员；单位仍保持纳管和本单位数据范围，由平台维护人员及角色。");
        long expires=clock.nowMillis()+10*60_000L;
        String token=UUID.randomUUID().toString();
        units.savePreview(new Preview(token,orgId,AuthContext.require().userId(),requestHash(request),
                snapshot(orgId),expires,false));
        return new PreviewResponse(token,expires,orgId,request.expectedVersion(),userImpacts,roleImpacts,cross,warnings);
    }

    @Transactional
    public DelegationResponse apply(String orgId, DelegationRequest request, String key,
            SystemManagementService.RequestMeta meta) {
        platform();
        lock(orgId);
        Set<String> lockedRoles=new TreeSet<>();
        members(orgId).forEach(u->lockedRoles.add(u.getRoleCode()));
        units.roles(orgId).forEach(r->lockedRoles.add(r.getRoleCode()));
        lockedRoles.forEach(identity::lockRoleMutation);
        RolePlan rolePlan=validateRequest(orgId,request);
        keys.claim(key,"unit-delegation:"+orgId+":"+requestHash(request)+":"+request.previewToken());
        Preview p=request.previewToken()==null?null:units.preview(request.previewToken());
        if(p==null || p.consumed() || p.expiresAt()<clock.nowMillis() || !orgId.equals(p.orgId())
                || !AuthContext.require().userId().equals(p.actorId()) || !requestHash(request).equals(p.requestHash())
                || !snapshot(orgId).equals(p.snapshotHash())) {
            throw error(HttpStatus.CONFLICT,"DELEGATION_PREVIEW_STALE","单位人员、角色或授权已变化，请重新预览后确认");
        }
        DelegationResponse before=detail(orgId);
        List<ManagementPermission> management=managementPermissions(orgId,request.managementPermissions());
        List<UserResponse> membersBefore=members(orgId).stream().map(this::user).toList();
        long now=clock.nowMillis();
        Delegation old=units.delegation(orgId);
        if(old==null) units.insertDelegation(orgId,request.administratorUserId(),now);
        else changed(units.updateDelegation(orgId,request.administratorUserId(),request.expectedVersion(),now));
        units.deletePermissions(orgId); units.deleteActions(orgId);
        request.permissions().forEach(a->units.insertPermission(orgId,a.permissionCode(),a.level(),a.menuEnabled()));
        actionSet(request.actions()).forEach(a->units.insertAction(orgId,a));
        units.deleteManagementPermissions(orgId);
        management.forEach(g->units.insertManagementPermission(orgId,g.moduleKey(),g.level()));

        Map<String,String> createdRoles=new HashMap<>();
        for(DelegationNewRole definition:request.newRoles()) {
            String code=roleCode();
            try { units.insertRole(code,definition.name().trim(),empty(definition.description()),true,now,orgId); }
            catch(DuplicateKeyException e) { throw error(HttpStatus.CONFLICT,"DUPLICATE_ROLE","本单位已存在同名角色"); }
            identity.insertEmptyRolePermissions(code);
            writeRole(code,definition.permissions(),definition.actions());
            createdRoles.put(definition.clientKey(),code);
            record("unit_role_created","role",code,Map.of("org_id",orgId,"name",definition.name().trim(),
                    "permissions",definition.permissions(),"actions",definition.actions(),"creation_mode","MANUAL"),meta);
        }
        for(UserAdminRow u:members(orgId)) {
            String role=u.getRoleCode();
            MemberRoleAssignment assignment=rolePlan.assignments().get(u.getUserId());
            if(assignment!=null) role=assignment.roleCode()!=null?assignment.roleCode():createdRoles.get(assignment.newRoleKey());
            if(!role.equals(u.getRoleCode())) {
                changed(identity.updateUserAccess(u.getUserId(),role,u.getScopeMode(),now,u.getVersion()));
                u=identity.findAdminUser(u.getUserId(),now);
            }
            changed(identity.updateUserScope(u.getUserId(),"ASSIGNED","OWN_ORG",now,u.getVersion()));
            scopes.refreshUser(u.getUserId());
            sessions.expireAllForUser(u.getUserId());
        }
        for(RoleRow role:units.roles(orgId)) {
            if(rolePlan.retiring().containsKey(role.getRoleCode())) continue;
            boolean altered=clipRole(role.getRoleCode(),request);
            if(altered) {
                changed(identity.bumpRoleVersion(role.getRoleCode(),role.getVersion(),now));
                identity.bumpPermissionVersionForRole(role.getRoleCode());
                sessions.expireAllForRole(role.getRoleCode());
            }
        }
        for(RoleRow oldRole:rolePlan.retiring().values()) {
            RoleRow current=identity.findRole(oldRole.getRoleCode());
            if(current.getUserCount()!=0) throw error(HttpStatus.CONFLICT,"ROLE_IN_USE","副本仍有人员引用，未删除；请完整分配人员后重试");
            identity.deleteRolePermissions(current.getRoleCode());
            changed(identity.deleteCustomRole(current.getRoleCode(),oldRole.getVersion()));
            record("unit_role_deleted","role",oldRole.getRoleCode(),Map.of("org_id",orgId,"name",oldRole.getName(),
                    "reason","平台确认等价权限并完成全部人员迁移后清理旧单位副本","copy_origin",RoleCopyOrigins.from(oldRole)),meta);
        }
        if(old!=null && old.administratorUserId()!=null) sessions.expireAllForUser(old.administratorUserId());
        if(request.administratorUserId()!=null) sessions.expireAllForUser(request.administratorUserId());
        changed(units.consumePreview(p.token()));
        DelegationResponse after=detail(orgId);
        record("unit_delegation_updated","organization",orgId,
                Map.of("before",before,"after",after,"members_before",membersBefore,
                        "members_after",members(orgId).stream().map(this::user).toList(),"preview_token",p.token(),
                        "role_assignment_mode","EXPLICIT","role_plan",Map.of("new_roles",request.newRoles(),
                                "member_assignments",request.memberAssignments(),"retire_role_codes",request.retireRoleCodes())),meta);
        return after;
    }

    public UnitCatalog catalog() {
        Delegation d=administrator(false);
        return new UnitCatalog(d.orgId(),identity.findOrganization(d.orgId()).getName(),d.administratorUserId(),
                units.permissions(d.orgId()).stream().map(this::permission).toList(),catalogEntries().actionModules(),
                units.actions(d.orgId()).stream().map(a->new ActionAssignment(a,"OP")).toList());
    }

    public PageResponse<UserResponse> listUsers(String keyword,String status,String roleCode,PageQuery page) {
        Delegation d=administrator(false);
        String q=keyword==null?"":keyword.trim().toLowerCase(Locale.ROOT);
        List<UserResponse> all=members(d.orgId()).stream().filter(u->(status==null||status.isBlank()||status.equals(u.getStatus()))
                &&(roleCode==null||roleCode.isBlank()||roleCode.equals(u.getRoleCode()))
                &&(q.isEmpty()||contains(u.getAccount(),q)||contains(u.getName(),q)||contains(u.getPhone(),q)))
                .map(this::user).toList();
        int from=(int)Math.min(all.size(),(long)(page.page()-1)*page.size());
        return new PageResponse<>(all.subList(from,Math.min(all.size(),from+page.size())),page.page(),page.size(),all.size());
    }
    public UserResponse getUser(String id) { return user(member(administrator(false).orgId(),id,false)); }

    @Transactional
    public UserResponse createUser(UserCreationRequest request,String key,SystemManagementService.RequestMeta meta) {
        Delegation d=administrator(true);
        validateMemberAssignment(d.orgId(),request.orgId(),request.userType(),request.dataScope(),request.roleCode());
        keys.claim(key,"unit-user-create:"+d.orgId()+":"+request);
        String account=request.account().trim();
        if(!account.matches("[A-Za-z0-9._-]{3,64}")) throw bad("INVALID_ACCOUNT","登录账号只能包含字母、数字、点、下划线和连字符，长度为3至64位");
        if(users.findByAccount(account)!=null) throw error(HttpStatus.CONFLICT,"DUPLICATE_ACCOUNT","登录账号已存在");
        passwords.validateTemporary(request.temporaryPassword(),account);
        String id=UUID.randomUUID().toString();
        try {
            identity.insertUser(id,account,request.name().trim(),clean(request.phone()),d.orgId(),request.roleCode(),
                    encoder.encode(request.temporaryPassword()),"ASSIGNED","OWN_ORG",clock.nowMillis());
        } catch(DuplicateKeyException e) { throw error(HttpStatus.CONFLICT,"DUPLICATE_ACCOUNT","登录账号已存在"); }
        scopes.refreshUser(id);
        record("unit_user_created","user",id,Map.of("org_id",d.orgId(),"account",account,"role_code",request.roleCode()),meta);
        return user(member(d.orgId(),id,false));
    }

    @Transactional
    public UserResponse updateUser(String id,UserProfileRequest request,String key,SystemManagementService.RequestMeta meta) {
        Delegation d=administrator(true);
        UserAdminRow current=member(d.orgId(),id,true);
        String role=request.roleCode()==null||request.roleCode().isBlank()?current.getRoleCode():request.roleCode();
        validateMemberAssignment(d.orgId(),request.orgId(),request.userType(),request.dataScope(),role);
        keys.claim(key,"unit-user-update:"+id+":"+request);
        changed(identity.updateUserProfile(id,request.name().trim(),clean(request.phone()),d.orgId(),clock.nowMillis(),request.expectedVersion()));
        if(!role.equals(current.getRoleCode())) {
            changed(identity.updateUserAccess(id,role,"ASSIGNED",clock.nowMillis(),request.expectedVersion()+1));
            sessions.expireAllForUser(id);
        }
        UserResponse after=user(member(d.orgId(),id,false));
        record("unit_user_updated","user",id,Map.of("org_id",d.orgId(),"before",user(current),"after",after),meta);
        return after;
    }

    @Transactional
    public UserResponse updateAccess(String id,UserAccessRequest request,String key,SystemManagementService.RequestMeta meta) {
        Delegation d=administrator(true);
        UserAdminRow current=member(d.orgId(),id,true);
        validateMemberAssignment(d.orgId(),d.orgId(),"FRONTEND","OWN_ORG",request.roleCode());
        keys.claim(key,"unit-user-access:"+id+":"+request);
        changed(identity.updateUserAccess(id,request.roleCode(),"ASSIGNED",clock.nowMillis(),request.expectedVersion()));
        sessions.expireAllForUser(id);
        record("unit_user_access_updated","user",id,Map.of("org_id",d.orgId(),"from_role",current.getRoleCode(),"to_role",request.roleCode(),"reason",request.reason()),meta);
        return user(member(d.orgId(),id,false));
    }

    @Transactional
    public UserResponse status(String id,UserStatusRequest request,String key,SystemManagementService.RequestMeta meta) {
        Delegation d=administrator(true); UserAdminRow before=member(d.orgId(),id,true);
        keys.claim(key,"unit-user-status:"+id+":"+request);
        changed(identity.setUserStatus(id,request.status(),clock.nowMillis(),request.expectedVersion()));
        sessions.expireAllForUser(id);
        record("unit_user_status_changed","user",id,Map.of("org_id",d.orgId(),"from",before.getStatus(),"to",request.status()),meta);
        return user(member(d.orgId(),id,false));
    }

    @Transactional
    public void resetPassword(String id,PasswordResetRequest request,String key,SystemManagementService.RequestMeta meta) {
        Delegation d=administrator(true); UserAdminRow u=member(d.orgId(),id,true);
        keys.claim(key,"unit-user-password:"+id+":"+request.expectedVersion());
        passwords.validateTemporary(request.temporaryPassword(),u.getAccount());
        changed(identity.resetUserPassword(id,encoder.encode(request.temporaryPassword()),clock.nowMillis(),request.expectedVersion()));
        sessions.expireAllForUser(id);
        record("unit_user_password_reset","user",id,Map.of("org_id",d.orgId()),meta);
    }

    @Transactional
    public void deleteUser(String id,int version,String reason,String key,SystemManagementService.RequestMeta meta) {
        Delegation d=administrator(true); UserAdminRow u=member(d.orgId(),id,true);
        keys.claim(key,"unit-user-delete:"+id+":"+version+":"+reason);
        String tombstone=u.getAccount().substring(0,Math.min(39,u.getAccount().length()))+"~deleted~"+id.replace("-","").substring(0,16);
        changed(identity.softDeleteUser(id,tombstone,AuthContext.require().userId(),clock.nowMillis(),version));
        identity.deleteUserScopes(id); sessions.expireAllForUser(id);
        record("unit_user_deleted","user",id,Map.of("org_id",d.orgId(),"reason",reason,"account",u.getAccount()),meta);
    }

    public List<UnitRole> roles() {
        Delegation d=administrator(false);
        return units.roles(d.orgId()).stream().map(r->role(r,d)).toList();
    }
    public UnitRole getRole(String code) {
        Delegation d=administrator(false); return role(unitRole(d.orgId(),code,false),d);
    }
    @Transactional
    public UnitRole createRole(UnitRoleCreateRequest request,String key,SystemManagementService.RequestMeta meta) {
        Delegation d=administrator(true);
        List<ActionAssignment> actions=request.actions()==null?List.of():request.actions();
        validateRoleGrants(d.orgId(),request.permissions(),actions);
        keys.claim(key,"unit-role-create:"+d.orgId()+":"+request);
        String code=roleCode();
        try { units.insertRole(code,request.name().trim(),empty(request.description()),true,clock.nowMillis(),d.orgId()); }
        catch(DuplicateKeyException e) { throw error(HttpStatus.CONFLICT,"DUPLICATE_ROLE","本单位已存在同名角色"); }
        identity.insertEmptyRolePermissions(code);
        writeRole(code,request.permissions(),actions);
        record("unit_role_created","role",code,Map.of("org_id",d.orgId(),"name",request.name(),"permissions",request.permissions(),"actions",actions),meta);
        return role(identity.findRole(code),d);
    }
    @Transactional
    public UnitRole updateRole(String code,RoleUpdateRequest request,String key,SystemManagementService.RequestMeta meta) {
        Delegation d=administrator(true); unitRole(d.orgId(),code,true);
        keys.claim(key,"unit-role-update:"+code+":"+request);
        changed(identity.updateRoleDescription(code,empty(request.description()),clock.nowMillis(),request.expectedVersion()));
        record("unit_role_updated","role",code,Map.of("org_id",d.orgId(),"description",empty(request.description())),meta);
        return role(identity.findRole(code),d);
    }
    @Transactional
    public UnitRole updateRolePermissions(String code,RoleAccessRequest request,String key,SystemManagementService.RequestMeta meta) {
        Delegation d=administrator(true); unitRole(d.orgId(),code,true);
        validateRoleGrants(d.orgId(),request.permissions(),request.actions());
        keys.claim(key,"unit-role-permissions:"+code+":"+request);
        UnitRole before=role(identity.findRole(code),d);
        changed(identity.bumpRoleVersion(code,request.expectedVersion(),clock.nowMillis()));
        writeRole(code,request.permissions(),request.actions());
        identity.bumpPermissionVersionForRole(code); sessions.expireAllForRole(code);
        UnitRole after=role(identity.findRole(code),d);
        record("unit_role_permissions_updated","role",code,Map.of("org_id",d.orgId(),"before",before,"after",after),meta);
        return after;
    }
    @Transactional
    public UnitRole roleStatus(String code,EnabledRequest request,String key,SystemManagementService.RequestMeta meta) {
        Delegation d=administrator(true); unitRole(d.orgId(),code,true);
        keys.claim(key,"unit-role-status:"+code+":"+request);
        changed(units.roleStatus(code,request.enabled(),request.expectedVersion(),clock.nowMillis()));
        identity.bumpPermissionVersionForRole(code); sessions.expireAllForRole(code);
        record("unit_role_status_changed","role",code,Map.of("org_id",d.orgId(),"enabled",request.enabled()),meta);
        return role(identity.findRole(code),d);
    }
    @Transactional
    public void deleteRole(String code,int version,String reason,String key,SystemManagementService.RequestMeta meta) {
        Delegation d=administrator(true); RoleRow r=unitRole(d.orgId(),code,true);
        keys.claim(key,"unit-role-delete:"+code+":"+version+":"+reason);
        if(r.getVersion()!=version) changed(0);
        if(r.getUserCount()>0) throw error(HttpStatus.CONFLICT,"ROLE_IN_USE","角色仍有用户，不能删除");
        identity.deleteRolePermissions(code); changed(identity.deleteCustomRole(code,version));
        record("unit_role_deleted","role",code,Map.of("org_id",d.orgId(),"name",r.getName(),"reason",reason),meta);
    }

    private void platform() { access.requireBackend(); access.require("users.auth"); }
    private void lock(String orgId) {
        if(units.lockOrg(orgId)==null) throw error(HttpStatus.NOT_FOUND,"ORG_NOT_FOUND","单位不存在");
        OrgRow org=identity.findOrganization(orgId);
        if(!org.isEnabled()) throw bad("ORG_DISABLED","单位已停用");
    }
    private Delegation administrator(boolean lock) {
        var actor=AuthContext.require();
        if(actor.mustChangePassword()) throw error(HttpStatus.FORBIDDEN,"PASSWORD_CHANGE_REQUIRED","请先修改临时密码");
        Delegation d=units.administrator(actor.userId());
        if(d==null) throw error(HttpStatus.FORBIDDEN,"UNIT_ADMIN_REQUIRED","当前账号不是有效单位管理员");
        if(lock) { lock(d.orgId()); d=units.administrator(actor.userId()); }
        if(d==null) throw error(HttpStatus.FORBIDDEN,"UNIT_ADMIN_REQUIRED","单位管理员任命已变化");
        return d;
    }
    private UserAdminRow member(String orgId,String id,boolean mutate) {
        UserAdminRow u=identity.findAdminUser(id,clock.nowMillis());
        if(u==null||"DELETED".equals(u.getStatus())||!orgId.equals(u.getOrgId())
                ||!UnitDelegationPolicy.member(u.getUserId(),u.getRoleCode())) {
            throw error(HttpStatus.NOT_FOUND,"USER_NOT_FOUND","本单位不存在该用户");
        }
        if(mutate && id.equals(AuthContext.require().userId())) throw error(HttpStatus.FORBIDDEN,"UNIT_ADMIN_SELF_PROTECTED","单位管理员本人由平台维护，请使用个人资料修改基本信息");
        return u;
    }
    private List<UserAdminRow> members(String orgId) { return units.users(orgId,clock.nowMillis()); }
    private RoleRow unitRole(String orgId,String code,boolean mutate) {
        RoleRow r=identity.findRole(code);
        if(r==null||!orgId.equals(units.roleOwner(code))) throw error(HttpStatus.NOT_FOUND,"ROLE_NOT_FOUND","本单位不存在该角色");
        if(mutate && code.equals(users.findById(AuthContext.require().userId()).getRoleCode()))
            throw error(HttpStatus.FORBIDDEN,"UNIT_ADMIN_ROLE_PROTECTED","单位管理员使用的角色由平台维护");
        return r;
    }
    private void validateMemberAssignment(String orgId,String submittedOrg,String type,String scope,String role) {
        if(!orgId.equals(submittedOrg)|| (type!=null&&!"FRONTEND".equals(type)) ||(scope!=null&&!"OWN_ORG".equals(scope)))
            throw error(HttpStatus.FORBIDDEN,"UNIT_MEMBER_SCOPE_FIXED","只能维护本单位前台人员，数据范围固定为本单位");
        if(role==null||!unitRole(orgId,role,false).isEnabled()) throw bad("ROLE_DISABLED","请选择本单位启用的角色");
    }
    private RolePlan validateRequest(String orgId,DelegationRequest request) {
        Delegation d=units.delegation(orgId);
        if((d==null?0:d.version())!=request.expectedVersion()) changed(0);
        if(d==null && request.administratorUserId()==null) throw bad("UNIT_ADMIN_REQUIRED","首次启用必须指定一名本单位管理员");
        validateGrants(request.permissions(),request.actions());
        managementPermissions(orgId,request.managementPermissions());
        RolePlan plan=rolePlan(orgId,request);
        if(request.administratorUserId()!=null) {
            UserAdminRow u=member(orgId,request.administratorUserId(),false);
            MemberRoleAssignment assignment=plan.assignments().get(u.getUserId());
            RoleRow r=assignment!=null&&assignment.newRoleKey()!=null?null:
                    identity.findRole(assignment==null?u.getRoleCode():assignment.roleCode());
            if(!"ACTIVE".equals(u.getStatus()) || (r!=null && !r.isEnabled()
                    && (d==null || !u.getUserId().equals(d.administratorUserId()))))
                throw bad("ADMINISTRATOR_INACTIVE","单位管理员必须是本单位启用的前台人员");
            if(r!=null && r.getOwnerOrgId()!=null && !orgId.equals(r.getOwnerOrgId()))
                throw bad("ROLE_ORGANIZATION_MISMATCH","不能任命绑定其他单位角色的人员");
        }
        return plan;
    }
    private record RolePlan(Map<String,MemberRoleAssignment> assignments,Map<String,RoleRow> retiring) { }
    private record EffectiveRole(Map<String,String> levels,Set<String> menus,Set<String> actions) { }

    /** Every changed membership is an explicit platform choice; appointment never manufactures a role. */
    private RolePlan rolePlan(String orgId,DelegationRequest request) {
        if(!request.newRoles().isEmpty()||!request.retireRoleCodes().isEmpty()) access.require("roles.auth");
        Map<String,DelegationNewRole> definitions=new LinkedHashMap<>();
        Set<String> names=units.roles(orgId).stream().map(RoleRow::getName).collect(Collectors.toSet());
        for(DelegationNewRole r:request.newRoles()) {
            if(r==null||clean(r.clientKey())==null||clean(r.name())==null||!r.clientKey().equals(r.clientKey().trim())
                    ||definitions.putIfAbsent(r.clientKey(),r)!=null)
                throw bad("INVALID_NEW_ROLE","新角色的标识或名称不能为空，标识不能重复");
            if(!names.add(r.name().trim())) throw error(HttpStatus.CONFLICT,"DUPLICATE_ROLE","本单位已存在同名角色");
            validateGrants(r.permissions(),r.actions());
            validateRoleCeiling(r.permissions(),r.actions(),request.permissions(),actionSet(request.actions()));
        }
        Map<String,UserAdminRow> members=members(orgId).stream().collect(Collectors.toMap(UserAdminRow::getUserId,u->u));
        Map<String,MemberRoleAssignment> assignments=new LinkedHashMap<>();
        for(MemberRoleAssignment a:request.memberAssignments()) {
            if(a==null||!members.containsKey(a.userId())||assignments.putIfAbsent(a.userId(),a)!=null
                    ||(a.roleCode()==null)==(a.newRoleKey()==null))
                throw bad("INVALID_ROLE_ASSIGNMENT","每名本单位前台人员只能明确选择一个现有角色或一个新角色");
            if(a.newRoleKey()!=null) {
                if(!definitions.containsKey(a.newRoleKey())) throw bad("INVALID_ROLE_ASSIGNMENT","人员所选的新角色不存在");
            } else {
                RoleRow target=unitRole(orgId,a.roleCode(),false);
                if(!target.isEnabled()) throw bad("ROLE_DISABLED","请选择本单位启用的角色");
            }
        }
        for(UserAdminRow u:members.values()) {
            if(!u.getUserId().equals(request.administratorUserId())
                    && !orgId.equals(units.roleOwner(u.getRoleCode()))&&!assignments.containsKey(u.getUserId()))
                throw bad("UNIT_ROLE_ASSIGNMENT_REQUIRED","请为“"+u.getName()+"（"+u.getAccount()+"）”明确选择本单位角色；系统不再自动复制角色");
        }
        Delegation departing=units.delegation(orgId);
        if(departing!=null && departing.administratorUserId()!=null
                && !Objects.equals(departing.administratorUserId(),request.administratorUserId())) {
            UserAdminRow oldAdministrator=members.get(departing.administratorUserId());
            if(oldAdministrator!=null && !assignments.containsKey(oldAdministrator.getUserId())
                    && !identity.findRole(oldAdministrator.getRoleCode()).isEnabled())
                throw bad("UNIT_ROLE_ASSIGNMENT_REQUIRED","请先为离任管理员选择启用的本单位人员角色");
        }
        Map<String,RoleRow> retiring=new LinkedHashMap<>();
        List<PermissionAssignment> currentCaps=units.permissions(orgId).stream()
                .map(p->new PermissionAssignment(p.getPermissionCode(),p.getPermissionLevel(),p.isMenuEnabled())).toList();
        Set<String> currentActions=new HashSet<>(units.actions(orgId));
        Delegation currentDelegation=units.delegation(orgId);
        if(!request.retireRoleCodes().isEmpty()&&(
                !Objects.equals(currentDelegation==null?null:currentDelegation.administratorUserId(),request.administratorUserId())
                ||!effectiveRole(currentCaps,currentActions,currentCaps,currentActions).equals(
                        effectiveRole(request.permissions(),actionSet(request.actions()),request.permissions(),actionSet(request.actions())))
                ||!managementPermissions(orgId,null).equals(managementPermissions(orgId,request.managementPermissions()))))
            throw bad("ROLE_RETIREMENT_AUTHORIZATION_CHANGE","清理旧副本时请保持单位管理员任命及前后台授权不变；管理员替换或权限调整请另行保存");
        for(String code:request.retireRoleCodes()) {
            RoleRow r=unitRole(orgId,code,false);
            if(r.isBuiltin()||r.getCopiedFromRoleCode()==null||r.getCopiedFromRoleName()==null||r.getCopiedAt()==null
                    ||retiring.putIfAbsent(code,r)!=null)
                throw bad("ROLE_COPY_RETIREMENT_INVALID","只能选择本单位有明确复制来源的旧副本，且不能重复选择");
            if(units.pendingRegistrationReferences(code)>0)
                throw error(HttpStatus.CONFLICT,"ROLE_IN_USE","副本仍被注册申请记录引用，保留角色及历史，不能删除");
            List<UserAdminRow> affected=members.values().stream().filter(u->code.equals(u.getRoleCode())).toList();
            if(r.getUserCount()!=affected.size()) throw error(HttpStatus.CONFLICT,"ROLE_IN_USE","副本仍有本次不能迁移的账号引用，不能删除");
            if(!affected.isEmpty()&&!r.isEnabled())
                throw bad("ROLE_REPLACEMENT_PERMISSION_MISMATCH","旧副本已停用，分配启用角色会改变人员授权状态，请先独立核对角色启停");
            EffectiveRole before=effectiveRole(r,currentCaps,currentActions);
            for(UserAdminRow u:affected) {
                MemberRoleAssignment a=assignments.get(u.getUserId());
                if(a==null||code.equals(a.roleCode())||(a.roleCode()!=null&&request.retireRoleCodes().contains(a.roleCode())))
                    throw bad("UNIT_ROLE_ASSIGNMENT_REQUIRED","请为旧副本的每名人员明确选择保留的替代角色");
                EffectiveRole after;
                if(a.newRoleKey()!=null) {
                    DelegationNewRole target=definitions.get(a.newRoleKey());
                    after=effectiveRole(target.permissions(),actionSet(target.actions()),request.permissions(),actionSet(request.actions()));
                } else {
                    RoleRow target=unitRole(orgId,a.roleCode(),false);
                    if(roleNeedsClipping(target.getRoleCode(),request))
                        throw bad("ROLE_REPLACEMENT_WOULD_CHANGE","替代角色会被本次单位授权变更裁剪，请先独立处理单位授权，再核对并清理副本");
                    after=effectiveRole(target,request.permissions(),actionSet(request.actions()));
                }
                if(!before.equals(after)) throw bad("ROLE_REPLACEMENT_PERMISSION_MISMATCH",
                        "“"+u.getName()+"”的替代角色与旧副本有效权限不一致（"+permissionDifference(before,after)+"），未迁移人员或删除角色");
            }
        }
        for(MemberRoleAssignment a:assignments.values())
            if(a.roleCode()!=null&&retiring.containsKey(a.roleCode())) throw bad("INVALID_ROLE_ASSIGNMENT","不能将人员分配到待删除的旧副本");
        return new RolePlan(assignments,retiring);
    }
    private EffectiveRole effectiveRole(RoleRow role,List<PermissionAssignment> caps,Set<String> actions) {
        if(!role.isEnabled()) return new EffectiveRole(Map.of(),Set.of(),Set.of());
        return effectiveRole(identity.listPermissionsForRole(role.getRoleCode()).stream()
                        .map(p->new PermissionAssignment(p.getPermissionCode(),p.getPermissionLevel(),p.isMenuEnabled())).toList(),
                identity.listActionsForRole(role.getRoleCode()).stream().filter(this::effectiveLegacyAction)
                        .map(ActionRow::getPermissionCode).collect(Collectors.toSet()),caps,actions);
    }
    private EffectiveRole effectiveRole(List<PermissionAssignment> grants,Set<String> grantedActions,
            List<PermissionAssignment> ceiling,Set<String> allowedActions) {
        Map<String,PermissionAssignment> caps=permissionMap(ceiling);
        Map<String,String> levels=new TreeMap<>();Set<String> menus=new TreeSet<>(),actions=new TreeSet<>();
        for(PermissionAssignment p:grants) {
            PermissionAssignment cap=caps.get(p.permissionCode());
            if(cap==null||!UnitDelegationPolicy.module(p.permissionCode())) continue;
            int level=Math.min(AccessService.level(p.level()),AccessService.level(cap.level()));
            if(level>0) {
                levels.put(p.permissionCode(),level==1?"READ":level==2?"OP":"AUTH");
                if(p.menuEnabled()&&cap.menuEnabled()) menus.add(p.permissionCode());
            }
        }
        grantedActions.stream().filter(allowedActions::contains).forEach(actions::add);
        return new EffectiveRole(levels,menus,actions);
    }
    private static String permissionDifference(EffectiveRole before,EffectiveRole after) {
        List<String> differences=new ArrayList<>();
        if(!before.levels().equals(after.levels())) differences.add("功能权限级别不同");
        if(!before.menus().equals(after.menus())) differences.add("可用菜单不同");
        if(!before.actions().equals(after.actions())) differences.add("业务动作不同");
        return String.join("、",differences);
    }
    private boolean roleNeedsClipping(String code,DelegationRequest request) {
        Map<String,PermissionAssignment> caps=permissionMap(request.permissions());
        for(PermissionRow p:identity.listPermissionsForRole(code)) {
            PermissionAssignment cap=caps.get(p.getPermissionCode());
            if(AccessService.level(p.getPermissionLevel())>AccessService.level(cap==null?"NONE":cap.level())
                    ||(p.isMenuEnabled()&&(cap==null||!cap.menuEnabled()||AccessService.level(cap.level())==0))) return true;
        }
        Set<String> allowed=actionSet(request.actions());
        return identity.listActionsForRole(code).stream().anyMatch(a->!effectiveLegacyAction(a)
                ||!allowed.contains(a.getPermissionCode())||!"OP".equals(a.getLevel()));
    }
    private void validateRoleCeiling(List<PermissionAssignment> permissions,List<ActionAssignment> actions,
            List<PermissionAssignment> ceiling,Set<String> allowedActions) {
        Map<String,PermissionAssignment> caps=permissionMap(ceiling);
        for(PermissionAssignment p:permissions) {
            PermissionAssignment cap=caps.get(p.permissionCode());
            if(AccessService.level(p.level())>AccessService.level(cap==null?"NONE":cap.level())
                    ||(p.menuEnabled()&&(cap==null||!cap.menuEnabled())))
                throw error(HttpStatus.FORBIDDEN,"UNIT_PERMISSION_CEILING","新角色权限不能超过本次单位授权上限");
        }
        if(!allowedActions.containsAll(actionSet(actions)))
            throw error(HttpStatus.FORBIDDEN,"UNIT_PERMISSION_CEILING","新角色动作不能超过本次单位授权上限");
    }
    private void validateGrants(List<PermissionAssignment> permissions,List<ActionAssignment> actions) {
        Map<String,PermissionRow> catalog=identity.listPermissionCatalog().stream().filter(p->UnitDelegationPolicy.module(p.getPermissionCode()))
                .collect(Collectors.toMap(PermissionRow::getPermissionCode,p->p));
        Set<String> seen=new HashSet<>();
        if(permissions==null) throw bad("INVALID_PERMISSION","请提交单位权限");
        for(PermissionAssignment p:permissions) {
            PermissionRow row=catalog.get(p.permissionCode());
            if(row==null||!seen.add(p.permissionCode())) throw bad("INVALID_PERMISSION","权限项不存在、重复或不属于前台");
            if(p.menuEnabled()&&(!UnitDelegationPolicy.menu(row.getRouteKey())||AccessService.level(p.level())<1))
                throw bad("MENU_REQUIRES_READ","只有可用前台菜单且至少查看权限才能开启菜单");
        }
        if(actions!=null) {
            Set<String> valid=identity.listActionCatalog().stream().map(ActionRow::getPermissionCode).collect(Collectors.toSet());
            seen.clear();
            for(ActionAssignment a:actions) {
                if(!valid.contains(a.permissionCode())||!FrontendActionPolicy.isAssignable(a.permissionCode())||!seen.add(a.permissionCode()))
                    throw bad("INVALID_PERMISSION","动作不存在、重复或不属于前台");
                if(!Set.of("NONE","OP").contains(a.level())) throw bad("INVALID_PERMISSION_LEVEL","动作权限只允许无或允许");
            }
        }
    }
    private void validateRoleGrants(String orgId,List<PermissionAssignment> permissions,List<ActionAssignment> actions) {
        validateGrants(permissions,actions);
        Map<String,PermissionRow> caps=units.permissions(orgId).stream().collect(Collectors.toMap(PermissionRow::getPermissionCode,p->p));
        for(PermissionAssignment p:permissions) {
            PermissionRow cap=caps.get(p.permissionCode());
            if(AccessService.level(p.level())>AccessService.level(cap==null?"NONE":cap.getPermissionLevel())
                    ||(p.menuEnabled()&&(cap==null||!cap.isMenuEnabled()))) throw error(HttpStatus.FORBIDDEN,"UNIT_PERMISSION_CEILING","角色权限不能超过单位授权上限");
        }
        Set<String> allowed=new HashSet<>(units.actions(orgId));
        if(actions!=null && !allowed.containsAll(actionSet(actions))) throw error(HttpStatus.FORBIDDEN,"UNIT_PERMISSION_CEILING","角色动作不能超过单位授权上限");
    }
    private void writeRole(String code,List<PermissionAssignment> permissions,List<ActionAssignment> actions) {
        Map<String,PermissionAssignment> assignments=permissionMap(permissions);
        for(PermissionRow p:identity.listPermissionCatalog()) {
            PermissionAssignment a=assignments.get(p.getPermissionCode());
            identity.updateRolePermission(code,p.getPermissionCode(),a==null?"NONE":a.level(),a!=null&&a.menuEnabled());
        }
        if(actions!=null) {
            identity.deleteRoleActions(code);
            actionSet(actions).forEach(a->identity.insertRoleAction(code,a,"OP"));
        }
    }
    private boolean clipRole(String code,DelegationRequest request) {
        boolean changed=false;
        Map<String,PermissionAssignment> caps=permissionMap(request.permissions());
        for(PermissionRow p:identity.listPermissionsForRole(code)) {
            PermissionAssignment cap=caps.get(p.getPermissionCode());
            String level=cap==null?"NONE":(AccessService.level(p.getPermissionLevel())<=AccessService.level(cap.level())?p.getPermissionLevel():cap.level());
            boolean menu=p.isMenuEnabled()&&cap!=null&&cap.menuEnabled()&&AccessService.level(level)>0;
            if(!level.equals(p.getPermissionLevel())||menu!=p.isMenuEnabled()) {
                identity.updateRolePermission(code,p.getPermissionCode(),level,menu); changed=true;
            }
        }
        Set<String> allowed=actionSet(request.actions());
        List<ActionRow> old=identity.listActionsForRole(code);
        List<ActionRow> kept=old.stream().filter(this::effectiveLegacyAction)
                .filter(a->allowed.contains(a.getPermissionCode())).toList();
        if(kept.size()!=old.size() || kept.stream().anyMatch(a->!"OP".equals(a.getLevel()))) {
            identity.deleteRoleActions(code);
            kept.forEach(a->identity.insertRoleAction(code,a.getPermissionCode(),"OP")); changed=true;
        }
        return changed;
    }
    /** Existing ordinary action rows use any non-NONE level; only DIRECT has always required explicit OP. */
    private boolean effectiveLegacyAction(ActionRow action) {
        return !"NONE".equals(action.getLevel())
                && (!"disposal:direct".equals(action.getPermissionCode()) || "OP".equals(action.getLevel()));
    }
    private List<CrossOrgImpact> crossImpacts(String orgId,List<UserAdminRow> members) {
        List<CrossOrgImpact> result=new ArrayList<>();
        var counts=units.crossOrgCounts(orgId);
        for(UserAdminRow u:members) {
            if(!Set.of("ALL","ASSIGNED").contains(u.getScopeMode())) continue;
            Map<String,Long> totals=new TreeMap<>();
            Map<String,String> names=new HashMap<>();
            Set<String> rolePermissions=new HashSet<>(access.permissionCodesForUser(u.getUserId()));
            for(var c:counts) {
                boolean readable=switch(c.resourceType()) {
                    case "FLIGHT_PLAN" -> rolePermissions.contains("flight:read")||rolePermissions.contains("flights.read");
                    case "ALARM" -> rolePermissions.contains("alarm:read")||rolePermissions.contains("alarms.read");
                    case "FLIGHT_RISK" -> rolePermissions.contains("risk:read")||rolePermissions.contains("risk.read");
                    case "DISPOSAL_AUTHORIZATION" -> rolePermissions.contains("disposal:read");
                    default -> false;
                };
                if(!readable) continue;
                if(!"ALL".equals(u.getScopeMode())&&identity.countUserScopeTuple(u.getUserId(),c.orgId(),c.districtId())==0) continue;
                String key=c.orgId()+"|"+c.resourceType();
                totals.merge(key,c.total(),Long::sum); names.put(c.orgId(),c.orgName());
            }
            totals.forEach((key,total)->{
                String[] parts=key.split("\\|");
                result.add(new CrossOrgImpact(u.getUserId(),u.getAccount(),parts[0],names.get(parts[0]),parts[1],total));
            });
        }
        return result;
    }
    private String snapshot(String orgId) {
        List<Object> data=new ArrayList<>();
        data.add(identity.findOrganization(orgId));
        data.add(units.delegation(orgId));
        data.add(units.permissions(orgId)); data.add(units.actions(orgId));
        data.add(units.managementPermissions(orgId));
        Set<String> roles=new TreeSet<>();
        for(UserAdminRow u:members(orgId)) {
            data.add(Arrays.asList(u.getUserId(),u.getAccount(),u.getOrgId(),u.getRoleCode(),u.getStatus(),
                    u.getScopeMode(),u.getScopeOrgRule(),u.getVersion()));
            data.add(identity.listUserScopes(u.getUserId()));
            roles.add(u.getRoleCode());
        }
        units.roles(orgId).forEach(r->roles.add(r.getRoleCode()));
        for(String role:roles) { data.add(identity.findRole(role)); data.add(identity.listPermissionsForRole(role)); data.add(identity.listActionsForRole(role)); }
        data.add(units.crossOrgPlanLinks(orgId));
        return hash(write(data));
    }
    private String requestHash(DelegationRequest r) {
        return hash(write(Arrays.asList(r.administratorUserId(),r.expectedVersion(),
                r.permissions().stream().sorted(Comparator.comparing(PermissionAssignment::permissionCode)).toList(),
                r.actions().stream().sorted(Comparator.comparing(ActionAssignment::permissionCode)).toList(),
                r.managementPermissions()==null?null:r.managementPermissions().stream()
                        .sorted(Comparator.comparing(ManagementPermission::moduleKey)).toList(),
                r.newRoles().stream().sorted(Comparator.comparing(DelegationNewRole::clientKey)).toList(),
                r.memberAssignments().stream().sorted(Comparator.comparing(MemberRoleAssignment::userId)).toList(),
                r.retireRoleCodes().stream().sorted().toList())));
    }
    private DelegationResponse detail(String orgId) {
        OrgRow o=identity.findOrganization(orgId);
        if(o==null) throw error(HttpStatus.NOT_FOUND,"ORG_NOT_FOUND","单位不存在");
        if(identity.countDeletedOrganization(orgId)>0)
            throw error(HttpStatus.CONFLICT,"ORG_DELETED","单位已删除，不能再配置授权");
        Delegation d=units.delegation(orgId);
        return new DelegationResponse(orgId,o.getName(),d!=null,d==null?0:d.version(),d==null?null:d.administratorUserId(),
                units.permissions(orgId).stream().map(this::permission).toList(),
                units.actions(orgId).stream().map(a->new ActionAssignment(a,"OP")).toList(),
                members(orgId).stream().filter(u->"ACTIVE".equals(u.getStatus())
                        && (identity.findRole(u.getRoleCode()).isEnabled() || (d!=null && u.getUserId().equals(d.administratorUserId())))).map(this::user).toList(),catalogEntries(),
                managementPermissions(orgId,null),members(orgId).stream().map(this::user).toList(),
                units.roles(orgId).stream().map(r->role(r,d)).toList());
    }
    /** Missing request field keeps existing grants; an explicit list replaces all five modules. */
    private List<ManagementPermission> managementPermissions(String orgId,List<ManagementPermission> submitted) {
        List<ManagementPermission> rows=submitted==null?units.managementPermissions(orgId):submitted;
        Map<String,String> levels=new HashMap<>();
        for(ManagementPermission grant:rows) {
            if(grant==null||grant.moduleKey()==null||grant.level()==null
                    ||!UnitManagementAccessService.MODULE_KEYS.contains(grant.moduleKey())
                    ||!Set.of("NONE","READ","OP").contains(grant.level())
                    ||("monitoring".equals(grant.moduleKey())&&"OP".equals(grant.level()))
                    ||levels.putIfAbsent(grant.moduleKey(),grant.level())!=null)
                throw bad("INVALID_MANAGEMENT_PERMISSION","后台授权模块或级别无效、重复；实时监测仅支持查看");
        }
        return UnitManagementAccessService.MODULE_KEYS.stream()
                .map(key->new ManagementPermission(key,levels.getOrDefault(key,"NONE"))).toList();
    }
    private Catalog catalogEntries() {
        List<PermissionResponse> permissions=identity.listPermissionCatalog().stream()
                .filter(p->UnitDelegationPolicy.module(p.getPermissionCode())).map(p->new PermissionResponse(p.getPermissionCode(),
                        p.getModuleName(),UnitDelegationPolicy.menu(p.getRouteKey())?p.getRouteKey():null,p.getSortOrder(),"NONE",false)).toList();
        Map<String,List<ActionRow>> groups=new LinkedHashMap<>();
        identity.listActionCatalog().stream().filter(a->FrontendActionPolicy.isAssignable(a.getPermissionCode()))
                .forEach(a->groups.computeIfAbsent(a.getModuleCode(),k->new ArrayList<>()).add(a));
        List<ActionModuleResponse> actions=groups.entrySet().stream().map(e->new ActionModuleResponse(e.getKey(),
                e.getValue().get(0).getModuleName(),e.getValue().stream().map(a->new ActionResponse(a.getPermissionCode(),a.getActionCode(),a.getName())).toList())).toList();
        return new Catalog(permissions,actions);
    }
    private UnitRole role(RoleRow r,Delegation d) {
        String adminRole=d==null||d.administratorUserId()==null?null:users.findById(d.administratorUserId()).getRoleCode();
        return new UnitRole(r.getRoleCode(),r.getName(),r.getDescription(),r.isBuiltin(),r.isEnabled(),r.getUserCount(),r.getVersion(),
                identity.listPermissionsForRole(r.getRoleCode()).stream().filter(p->UnitDelegationPolicy.module(p.getPermissionCode())).map(this::permission).toList(),
                identity.listActionsForRole(r.getRoleCode()).stream().filter(a->FrontendActionPolicy.isAssignable(a.getPermissionCode()))
                        .map(a->new RoleActionResponse(a.getPermissionCode(),a.getLevel())).toList(),r.getRoleCode().equals(adminRole),RoleCopyOrigins.from(r));
    }
    private UserResponse user(UserAdminRow u) {
        Delegation appointment=units.delegation(u.getOrgId());
        return new UserResponse(u.getUserId(),u.getAccount(),u.getName(),u.getPhone(),u.getOrgId(),u.getOrgName(),u.getRoleCode(),
                u.getRoleName(),u.getStatus(),DataScope.of(u.getScopeMode(),u.getScopeOrgRule()).name(),u.isMustChangePassword(),
                u.isOnline(),u.getLastLoginAt(),u.getLastLoginIp(),u.getCreatedAt(),u.getVersion(),"FRONTEND",
                appointment!=null && u.getUserId().equals(appointment.administratorUserId()));
    }
    private PermissionResponse permission(PermissionRow p) {
        return new PermissionResponse(p.getPermissionCode(),p.getModuleName(),UnitDelegationPolicy.menu(p.getRouteKey())?p.getRouteKey():null,
                p.getSortOrder(),p.getPermissionLevel(),p.isMenuEnabled());
    }
    /** Match the existing business permission labels by stable code; module_name is only a navigation group. */
    private static String permissionLabel(PermissionRow p) {
        return switch(p.getPermissionCode()) {
            case "dashboard" -> "数据大屏";
            case "sensing" -> "感知监测";
            case "flights" -> "飞行任务";
            case "legality" -> "合法性判定";
            case "alarms" -> "异常告警";
            case "punishment" -> "处置处罚";
            case "statistics" -> "统计分析";
            case "evidence" -> "证据管理";
            case "devices" -> "前台设备操作";
            case "airspace" -> "空域与航线";
            case "risk" -> "空间风险";
            default -> p.getModuleName()==null?p.getPermissionCode():p.getModuleName()+"（"+p.getPermissionCode()+"）";
        };
    }
    private static String permissionLevelLabel(String level) {
        return switch(level) {
            case "NONE" -> "无权限";
            case "READ" -> "只读";
            case "OP" -> "操作";
            case "AUTH" -> "授权";
            default -> level;
        };
    }
    private static Map<String,PermissionAssignment> permissionMap(List<PermissionAssignment> rows) {
        return rows.stream().collect(Collectors.toMap(PermissionAssignment::permissionCode,p->p));
    }
    private static Set<String> actionSet(List<ActionAssignment> rows) {
        return rows.stream().filter(a->"OP".equals(a.level())).map(ActionAssignment::permissionCode).collect(Collectors.toCollection(TreeSet::new));
    }
    private static String roleCode() { return "ROLE-UNIT-"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT); }
    private void record(String action,String type,String id,Object detail,SystemManagementService.RequestMeta meta) {
        var a=AuthContext.require();
        audit.record(a.userId(),a.account(),a.roleCode(),"users",action,type,id,write(detail),"SUCCESS",meta.ip(),meta.userAgent());
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch(JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static boolean contains(String value,String q) { return value!=null&&value.toLowerCase(Locale.ROOT).contains(q); }
    private static String clean(String value) { return value==null||value.isBlank()?null:value.trim(); }
    private static String empty(String value) { return value==null?"":value.trim(); }
    private static void changed(int count) { if(count!=1) throw error(HttpStatus.CONFLICT,"VERSION_CONFLICT","资料已变化，请刷新后重试"); }
    private static ApiException bad(String code,String message) { return error(HttpStatus.BAD_REQUEST,code,message); }
    private static ApiException error(HttpStatus status,String code,String message) { return new ApiException(status,code,message); }
}
