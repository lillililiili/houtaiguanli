package com.uav.lowaltitude.modules.identity.domain;

public class AppUser {
    private String userId;
    private String account;
    private String name;
    private String roleCode;
    private String status;
    private String passwordHash;
    private String phone;
    private String orgId;
    private String scopeMode;
    private boolean mustChangePassword;
    private int permissionVersion;
    private Long lastLoginAt;
    private String lastLoginIp;
    private long createdAt;
    private long updatedAt;
    private int version;
    private int failCount;
    private Long lockedUntil;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getScopeMode() {
        return scopeMode;
    }

    public void setScopeMode(String scopeMode) {
        this.scopeMode = scopeMode;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public int getPermissionVersion() {
        return permissionVersion;
    }

    public void setPermissionVersion(int permissionVersion) {
        this.permissionVersion = permissionVersion;
    }

    public Long getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Long lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getLastLoginIp() {
        return lastLoginIp;
    }

    public void setLastLoginIp(String lastLoginIp) {
        this.lastLoginIp = lastLoginIp;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getFailCount() {
        return failCount;
    }

    public void setFailCount(int failCount) {
        this.failCount = failCount;
    }

    public Long getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Long lockedUntil) {
        this.lockedUntil = lockedUntil;
    }
}
