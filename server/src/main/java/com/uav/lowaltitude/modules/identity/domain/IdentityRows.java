package com.uav.lowaltitude.modules.identity.domain;

public final class IdentityRows {

    private IdentityRows() {
    }

    public static class RoleRow {
        private String roleCode;
        private String name;
        private String description;
        private boolean builtin;
        private boolean enabled;
        private int userCount;
        private int version;

        public String getRoleCode() { return roleCode; }
        public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public boolean isBuiltin() { return builtin; }
        public void setBuiltin(boolean builtin) { this.builtin = builtin; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getUserCount() { return userCount; }
        public void setUserCount(int userCount) { this.userCount = userCount; }
        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }
    }

    /** 动作权限目录行（决策 15-1）。与 MODULE 矩阵行分开：矩阵整组提交，动作逐项授予。 */
    public static class ActionRow {
        private String permissionCode;
        private String moduleCode;
        private String moduleName;
        private String actionCode;
        private String name;
        private int sortOrder;
        private String level;

        public String getPermissionCode() { return permissionCode; }
        public void setPermissionCode(String permissionCode) { this.permissionCode = permissionCode; }
        public String getModuleCode() { return moduleCode; }
        public void setModuleCode(String moduleCode) { this.moduleCode = moduleCode; }
        public String getModuleName() { return moduleName; }
        public void setModuleName(String moduleName) { this.moduleName = moduleName; }
        public String getActionCode() { return actionCode; }
        public void setActionCode(String actionCode) { this.actionCode = actionCode; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
    }

    public static class PermissionRow {
        private String permissionCode;
        private String moduleName;
        private String routeKey;
        private int sortOrder;
        private String permissionLevel;
        private boolean menuEnabled;

        public String getPermissionCode() { return permissionCode; }
        public void setPermissionCode(String permissionCode) { this.permissionCode = permissionCode; }
        public String getModuleName() { return moduleName; }
        public void setModuleName(String moduleName) { this.moduleName = moduleName; }
        public String getRouteKey() { return routeKey; }
        public void setRouteKey(String routeKey) { this.routeKey = routeKey; }
        public int getSortOrder() { return sortOrder; }
        public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
        public String getPermissionLevel() { return permissionLevel; }
        public void setPermissionLevel(String permissionLevel) { this.permissionLevel = permissionLevel; }
        public boolean isMenuEnabled() { return menuEnabled; }
        public void setMenuEnabled(boolean menuEnabled) { this.menuEnabled = menuEnabled; }
    }

    public static class UserAdminRow {
        private String userId;
        private String account;
        private String name;
        private String phone;
        private String orgId;
        private String orgName;
        private String roleCode;
        private String roleName;
        private String status;
        private String scopeMode;
        private boolean mustChangePassword;
        private boolean online;
        private Long lastLoginAt;
        private String lastLoginIp;
        private long createdAt;
        private int version;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getAccount() { return account; }
        public void setAccount(String account) { this.account = account; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getOrgId() { return orgId; }
        public void setOrgId(String orgId) { this.orgId = orgId; }
        public String getOrgName() { return orgName; }
        public void setOrgName(String orgName) { this.orgName = orgName; }
        public String getRoleCode() { return roleCode; }
        public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
        public String getRoleName() { return roleName; }
        public void setRoleName(String roleName) { this.roleName = roleName; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getScopeMode() { return scopeMode; }
        public void setScopeMode(String scopeMode) { this.scopeMode = scopeMode; }
        public boolean isMustChangePassword() { return mustChangePassword; }
        public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
        public boolean isOnline() { return online; }
        public void setOnline(boolean online) { this.online = online; }
        public Long getLastLoginAt() { return lastLoginAt; }
        public void setLastLoginAt(Long lastLoginAt) { this.lastLoginAt = lastLoginAt; }
        public String getLastLoginIp() { return lastLoginIp; }
        public void setLastLoginIp(String lastLoginIp) { this.lastLoginIp = lastLoginIp; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }
    }

    public static class ScopeGrantRow {
        private String orgId;
        private String orgName;
        private String districtId;
        private String districtName;

        public String getOrgId() { return orgId; }
        public void setOrgId(String orgId) { this.orgId = orgId; }
        public String getOrgName() { return orgName; }
        public void setOrgName(String orgName) { this.orgName = orgName; }
        public String getDistrictId() { return districtId; }
        public void setDistrictId(String districtId) { this.districtId = districtId; }
        public String getDistrictName() { return districtName; }
        public void setDistrictName(String districtName) { this.districtName = districtName; }
    }

    public static class OrgRow {
        private String orgId;
        private String parentId;
        private String orgCode;
        private String name;
        private boolean enabled;
        private int version;

        public String getOrgId() { return orgId; }
        public void setOrgId(String orgId) { this.orgId = orgId; }
        public String getParentId() { return parentId; }
        public void setParentId(String parentId) { this.parentId = parentId; }
        public String getOrgCode() { return orgCode; }
        public void setOrgCode(String orgCode) { this.orgCode = orgCode; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }
    }

    public static class DistrictRow {
        private String districtId;
        private String districtCode;
        private String name;
        private boolean enabled;
        private int version;

        public String getDistrictId() { return districtId; }
        public void setDistrictId(String districtId) { this.districtId = districtId; }
        public String getDistrictCode() { return districtCode; }
        public void setDistrictCode(String districtCode) { this.districtCode = districtCode; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }
    }

    public static class AccessChangeRow {
        private String changeId;
        private String changeType;
        private String subjectType;
        private String subjectId;
        private String requesterId;
        private String requesterName;
        private String beforeSnapshot;
        private String afterSnapshot;
        private String reason;
        private int subjectVersion;
        private String status;
        private String reviewerId;
        private String reviewerName;
        private String reviewComment;
        private long requestedAt;
        private Long reviewedAt;
        private int version;

        public String getChangeId() { return changeId; }
        public void setChangeId(String changeId) { this.changeId = changeId; }
        public String getChangeType() { return changeType; }
        public void setChangeType(String changeType) { this.changeType = changeType; }
        public String getSubjectType() { return subjectType; }
        public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
        public String getSubjectId() { return subjectId; }
        public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
        public String getRequesterId() { return requesterId; }
        public void setRequesterId(String requesterId) { this.requesterId = requesterId; }
        public String getRequesterName() { return requesterName; }
        public void setRequesterName(String requesterName) { this.requesterName = requesterName; }
        public String getBeforeSnapshot() { return beforeSnapshot; }
        public void setBeforeSnapshot(String beforeSnapshot) { this.beforeSnapshot = beforeSnapshot; }
        public String getAfterSnapshot() { return afterSnapshot; }
        public void setAfterSnapshot(String afterSnapshot) { this.afterSnapshot = afterSnapshot; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public int getSubjectVersion() { return subjectVersion; }
        public void setSubjectVersion(int subjectVersion) { this.subjectVersion = subjectVersion; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getReviewerId() { return reviewerId; }
        public void setReviewerId(String reviewerId) { this.reviewerId = reviewerId; }
        public String getReviewerName() { return reviewerName; }
        public void setReviewerName(String reviewerName) { this.reviewerName = reviewerName; }
        public String getReviewComment() { return reviewComment; }
        public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
        public long getRequestedAt() { return requestedAt; }
        public void setRequestedAt(long requestedAt) { this.requestedAt = requestedAt; }
        public Long getReviewedAt() { return reviewedAt; }
        public void setReviewedAt(Long reviewedAt) { this.reviewedAt = reviewedAt; }
        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }
    }

    public static class PendingUserRow {
        private String changeId;
        private String account;
        private String name;
        private String phone;
        private String orgId;
        private String roleCode;
        private String scopeMode;
        private String scopeGrants;
        private String passwordHash;

        public String getChangeId() { return changeId; }
        public void setChangeId(String changeId) { this.changeId = changeId; }
        public String getAccount() { return account; }
        public void setAccount(String account) { this.account = account; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getOrgId() { return orgId; }
        public void setOrgId(String orgId) { this.orgId = orgId; }
        public String getRoleCode() { return roleCode; }
        public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
        public String getScopeMode() { return scopeMode; }
        public void setScopeMode(String scopeMode) { this.scopeMode = scopeMode; }
        public String getScopeGrants() { return scopeGrants; }
        public void setScopeGrants(String scopeGrants) { this.scopeGrants = scopeGrants; }
        public String getPasswordHash() { return passwordHash; }
        public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    }
}
