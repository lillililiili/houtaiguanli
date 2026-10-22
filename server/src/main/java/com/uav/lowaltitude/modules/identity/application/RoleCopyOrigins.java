package com.uav.lowaltitude.modules.identity.application;

import com.uav.lowaltitude.modules.identity.api.SystemDtos.RoleCopyOrigin;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.RoleRow;

/** Shared read mapping; provenance never participates in authorization or user assignment. */
final class RoleCopyOrigins {
    private RoleCopyOrigins() { }

    static RoleCopyOrigin from(RoleRow row) {
        return row.getCopiedFromRoleCode() == null ? null : new RoleCopyOrigin(
                row.getCopiedFromRoleCode(), row.getCopiedFromRoleName(), row.getCopiedAt());
    }
}
