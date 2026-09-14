package com.uav.lowaltitude.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.map-package")
public class MapPackageProperties {

    private String dataDir = "./data/map-data";
    private long maxUploadBytes = 512L * 1024 * 1024;
    private long maxExpandedBytes = 2L * 1024 * 1024 * 1024;
    private int maxFiles = 10_000;
    private String businessCityCode = "370500";

    public String getDataDir() { return dataDir; }
    public void setDataDir(String value) { dataDir = value; }
    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long value) { maxUploadBytes = value; }
    public long getMaxExpandedBytes() { return maxExpandedBytes; }
    public void setMaxExpandedBytes(long value) { maxExpandedBytes = value; }
    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int value) { maxFiles = value; }
    public String getBusinessCityCode() { return businessCityCode; }
    public void setBusinessCityCode(String value) { businessCityCode = value; }
}
