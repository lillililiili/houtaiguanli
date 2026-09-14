package com.uav.lowaltitude.modules.identity.domain;

public class AppSession {
    private String sessionId;
    private String userId;
    private long expireAt;
    private String ip;
    private String shiftNote;
    private int permissionVersion;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public long getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(long expireAt) {
        this.expireAt = expireAt;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getShiftNote() {
        return shiftNote;
    }

    public void setShiftNote(String shiftNote) {
        this.shiftNote = shiftNote;
    }

    public int getPermissionVersion() {
        return permissionVersion;
    }

    public void setPermissionVersion(int permissionVersion) {
        this.permissionVersion = permissionVersion;
    }
}
