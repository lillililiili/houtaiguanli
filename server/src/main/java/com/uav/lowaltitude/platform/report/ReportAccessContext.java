package com.uav.lowaltitude.platform.report;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.application.UnitManagementAccessService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.identity.domain.ScopeMode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.security.AuthContext;

/** A server-created, read-only report scope. Never used by business action authorization. */
@Component
public class ReportAccessContext {
    private record Grant(String actor, String org, Set<String> modules, Set<String> management) {}
    private static final ThreadLocal<Grant> CURRENT = new ThreadLocal<>();
    private final UnitManagementAccessService management;
    private final NamedParameterJdbcTemplate jdbc;
    public ReportAccessContext(UnitManagementAccessService management, NamedParameterJdbcTemplate jdbc) {
        this.management = management; this.jdbc = jdbc;
    }

    public <T> T call(boolean export, Supplier<T> work) {
        var actor = AuthContext.require();
        if (actor.mustChangePassword()) throw new ApiException(HttpStatus.FORBIDDEN,"PASSWORD_CHANGE_REQUIRED","请先修改临时密码");
        String org = management.require(export ? "statistics.op" : "statistics.read");
        Set<String> modules = Set.copyOf(jdbc.query("SELECT permission_code FROM app_org_permission WHERE org_id=:org AND permission_level IN ('READ','OP','AUTH')",
                Map.of("org",org),(rs,n)->rs.getString(1)));
        Grant previous = CURRENT.get();
        CURRENT.set(new Grant(actor.userId(),org,modules,Set.copyOf(management.permissionCodes(actor.userId()))));
        try { return work.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    private static Grant current() {
        Grant value = CURRENT.get();
        if (value != null && !value.actor().equals(AuthContext.require().userId())) throw denied();
        return value;
    }
    public static String orgId() { Grant value=current(); return value==null?null:value.org(); }
    public static boolean active() { return current()!=null; }
    public static void requireReport(AccessService fallback) {
        if (!active()) fallback.requireBusinessData("statistics.read");
    }
    public static AccessDecision require(AccessControlService fallback, PermissionCode permission) {
        Grant grant = current();
        if (grant == null) return fallback.require(permission);
        String module = switch(permission) {
            case TARGET_READ -> "sensing";
            case ALARM_READ -> "alarms";
            case FLIGHT_READ -> "flights";
            case RISK_READ -> "risk";
            case DISPOSAL_READ, HANDOFF_READ -> "punishment";
            default -> throw denied();
        };
        if (!grant.modules().contains(module)) throw denied();
        return new AccessDecision(grant.actor(),ScopeMode.ASSIGNED);
    }
    /** Device report data follows opened backend device/maintenance modules, not personal front-office grants. */
    public static void requireDeviceModule(String module) {
        Grant grant=current();
        if (grant==null || !Set.of("devices","maintenance").contains(module)
                || !grant.management().contains(module+".read")) throw denied();
    }
    /** Trusted column names are supplied only by report repositories. */
    public static BusinessReportSource.Dataset scope(BusinessReportSource.Dataset dataset, String ownerColumn) {
        String org=orgId();
        if (org==null) return dataset;
        if (!Set.of("t.owner_org_id","a.owner_org_id","e.owner_org_id","r.owner_org_id","p.owner_org_id","h.owner_org_id").contains(ownerColumn))
            throw new IllegalArgumentException("Unsupported report owner column");
        var parameters=new HashMap<>(dataset.parameters()); parameters.put("unit_report_org",org);
        return new BusinessReportSource.Dataset(dataset.sql()+" AND "+ownerColumn+"=:unit_report_org",parameters);
    }
    private static ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN,"REPORT_MODULE_UNAVAILABLE","单位未开通此报表数据模块");
    }
}
