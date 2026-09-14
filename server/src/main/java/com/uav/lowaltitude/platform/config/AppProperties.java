package com.uav.lowaltitude.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String sourceMode = "mock";
    private String evidenceDir = "./data/evidence";
    private final Login login = new Login();
    private final Session session = new Session();
    private final DevSeed devSeed = new DevSeed();
    private final BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();
    private final SuperAdmin superAdmin = new SuperAdmin();
    private final SuperAdminRecovery superAdminRecovery = new SuperAdminRecovery();
    private final LiveDevice liveDevice = new LiveDevice();

    public String getSourceMode() {
        return sourceMode;
    }

    public void setSourceMode(String sourceMode) {
        this.sourceMode = sourceMode;
    }

    public String getEvidenceDir() {
        return evidenceDir;
    }

    public void setEvidenceDir(String evidenceDir) {
        this.evidenceDir = evidenceDir;
    }

    public Login getLogin() {
        return login;
    }

    public Session getSession() {
        return session;
    }

    public DevSeed getDevSeed() {
        return devSeed;
    }

    public BootstrapAdmin getBootstrapAdmin() {
        return bootstrapAdmin;
    }

    public SuperAdmin getSuperAdmin() {
        return superAdmin;
    }

    public SuperAdminRecovery getSuperAdminRecovery() {
        return superAdminRecovery;
    }

    public LiveDevice getLiveDevice() { return liveDevice; }

    public static class Login {
        private int failLimit = 5;
        private int lockMinutes = 30;

        public int getFailLimit() {
            return failLimit;
        }

        public void setFailLimit(int failLimit) {
            this.failLimit = failLimit;
        }

        public int getLockMinutes() {
            return lockMinutes;
        }

        public void setLockMinutes(int lockMinutes) {
            this.lockMinutes = lockMinutes;
        }
    }

    public static class Session {
        private int ttlHours = 8;

        public int getTtlHours() {
            return ttlHours;
        }

        public void setTtlHours(int ttlHours) {
            this.ttlHours = ttlHours;
        }
    }

    public static class DevSeed {
        private boolean enabled;
        private String password = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class BootstrapAdmin {
        private boolean enabled;
        private String account = "";
        private String name = "超级管理员";
        private String password = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAccount() { return account; }
        public void setAccount(String value) { account = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public String getPassword() { return password; }
        public void setPassword(String value) { password = value; }
    }

    public static class SuperAdmin {
        private String account = "admin1";

        public String getAccount() { return account; }
        public void setAccount(String value) { account = value; }
    }

    public static class SuperAdminRecovery {
        private boolean enabled;
        private String password = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPassword() { return password; }
        public void setPassword(String value) { password = value; }
    }

    public static class LiveDevice {
        private boolean enabled;
        private String instanceId = "";
        private int leaseSeconds = 30;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String value) { instanceId = value; }
        public int getLeaseSeconds() { return leaseSeconds; }
        public void setLeaseSeconds(int value) { leaseSeconds = value; }
    }
}
