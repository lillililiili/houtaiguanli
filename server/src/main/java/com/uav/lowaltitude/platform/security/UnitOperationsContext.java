package com.uav.lowaltitude.platform.security;

import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.uav.lowaltitude.modules.identity.application.UnitManagementAccessService;
import com.uav.lowaltitude.platform.api.ApiException;

/** A server-created capability scoped to one restricted management invocation, never a business grant. */
@Component
public class UnitOperationsContext {
    public enum Family { DEVICES, MONITORING, MAINTENANCE, COMMISSIONING }
    private record State(String userId, String orgId, Family family, java.util.Set<String> newSources) { }
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private final UnitManagementAccessService access;
    public UnitOperationsContext(UnitManagementAccessService access) { this.access = access; }

    public <T> T call(Family family, String permission, Supplier<T> work) {
        if(AuthContext.require().mustChangePassword())
            throw new ApiException(HttpStatus.FORBIDDEN,"PASSWORD_CHANGE_REQUIRED","请先修改临时密码");
        String org = access.require(permission);
        State previous = CURRENT.get();
        CURRENT.set(new State(AuthContext.require().userId(), org, family, new java.util.HashSet<>()));
        try { return work.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    /** Shared device selectors are also needed by independently authorized monitoring and commissioning pages. */
    public <T> T deviceRead(Supplier<T> work) {
        String user = AuthContext.require().userId();
        if (access.allows(user,"devices.read")) return call(Family.DEVICES,"devices.read",work);
        if (access.allows(user,"monitoring.read")) return call(Family.MONITORING,"monitoring.read",work);
        if (access.allows(user,"commissioning.read")) return call(Family.COMMISSIONING,"commissioning.read",work);
        return call(Family.MAINTENANCE,"maintenance.read",work);
    }

    public static String orgId() { State s = CURRENT.get(); return s == null ? null : s.orgId(); }
    public static String userId() { State s = CURRENT.get(); return s == null ? null : s.userId(); }
    public static boolean active() { return CURRENT.get() != null; }
    public static void registerNewSource(String id) { if(active()) CURRENT.get().newSources().add(id); }
    public static boolean ownsNewSource(String id) { return active() && CURRENT.get().newSources().contains(id); }
    public static String managementPermission(String requested) {
        State s = CURRENT.get();
        if (s == null) return null;
        if (requested.startsWith("commissioning.")) return requested;
        return switch (s.family()) {
            case DEVICES -> requested.startsWith("devices.") ? requested : null;
            case MONITORING -> requested.equals("devices.read") || requested.equals("monitoring.read") ? "monitoring.read" : null;
            case MAINTENANCE -> requested.equals("devices.read") || requested.equals("monitoring.read") ? "maintenance.read"
                    : requested.equals("monitoring.op") ? "maintenance.op" : null;
            case COMMISSIONING -> requested.equals("devices.read") || requested.equals("monitoring.read") ? "commissioning.read" : null;
        };
    }

    public AuthUser require(String requested) {
        String permission = managementPermission(requested);
        if (permission == null || !AuthContext.require().userId().equals(userId())
                || !orgId().equals(access.require(permission))) throw forbidden();
        return AuthContext.require();
    }

    public boolean allows(AuthUser user, String requested) {
        String permission = managementPermission(requested);
        return permission != null && user != null && user.userId().equals(userId())
                && orgId().equals(access.orgId(user.userId())) && access.allows(user.userId(), permission);
    }

    public static void requireOwner(String requestedOrg) {
        if (active() && requestedOrg != null && !orgId().equals(requestedOrg)) throw forbidden();
    }
    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "UNIT_OPERATION_FORBIDDEN", "仅可在本单位授权的管理范围内操作");
    }
}
