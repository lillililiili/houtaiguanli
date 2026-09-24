package com.uav.lowaltitude.modules.disposal.application;

import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.automationrule.application.AutomationPrincipal;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository.AuthorizationRow;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.modules.identity.infrastructure.IdentityAdminMapper;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;

/** 无会话的直接反制续链及排队发送：每次按发起人当前权限和范围重新判断。 */
@Component
public class DirectDisposalAccess {
    private final IdentityAdminMapper identity;
    private final DisposalRepository authorizations;
    private final DeviceAccessPolicy devices;
    private final AppClock clock;

    public DirectDisposalAccess(IdentityAdminMapper identity, DisposalRepository authorizations,
                                DeviceAccessPolicy devices, AppClock clock) {
        this.identity = identity;
        this.authorizations = authorizations;
        this.devices = devices;
        this.clock = clock;
    }

    public AuthUser eligibleRequester(AuthorizationRow row, boolean needsDeviceControl) {
        if (row == null || !"DIRECT".equals(row.authorizationMode())) return null;
        var now = clock.now().atOffset(ZoneOffset.UTC);
        if (row.validFrom() == null || row.validUntil() == null
                || now.isBefore(row.validFrom()) || !now.isBefore(row.validUntil())) return null;
        // 规则发起人没有 disposal:direct，也不许登录。窗口内仍按系统依据和急停继续，不补审批人。
        if (AutomationPrincipal.USER_ID.equals(row.requestedBy())) return authorizations.actor(row.requestedBy());
        var user = identity.findAdminUser(row.requestedBy(), clock.nowMillis());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || user.isMustChangePassword()) return null;
        var role = identity.findRole(user.getRoleCode());
        if (role == null || !role.isEnabled()) return null;
        // 管理员也必须有显式 OP 授权；目录存在、READ/AUTH 或其他处置权限都不能替代。
        boolean direct = identity.listActionsForRole(user.getRoleCode()).stream().anyMatch(grant ->
                "disposal:direct".equals(grant.getPermissionCode()) && "OP".equals(grant.getLevel()));
        if (!direct) return null;
        ScopeMode scope = "ALL".equals(user.getScopeMode()) ? ScopeMode.ALL
                : "ASSIGNED".equals(user.getScopeMode()) ? ScopeMode.ASSIGNED : null;
        if (scope == null || authorizations.find(row.authorizationId(), new AccessDecision(user.getUserId(), scope)) == null)
            return null;
        AuthUser actor = authorizations.actor(user.getUserId());
        if (actor == null || actor.mustChangePassword() || !user.getRoleCode().equals(actor.roleCode())
                || !user.getScopeMode().equals(actor.scopeMode())) return null;
        if (needsDeviceControl && !devices.canOperateDevices(actor)) return null;
        return actor;
    }
}
