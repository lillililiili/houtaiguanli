package com.uav.lowaltitude.modules.device.api;

import jakarta.validation.constraints.*;

/** Explicit simulation facts, not additional fields in a vendor protocol. */
public final class LocalQaDeviceStatusDtos {
    private LocalQaDeviceStatusDtos() { }
    public record Input(
            @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String messageId,
            @NotBlank @Size(max=36) String deviceId,
            @NotBlank @Size(max=36) String sourceId,
            @NotNull @Positive Long observedAt,
            @NotNull @Pattern(regexp="ONLINE|OFFLINE|ABNORMAL|UNKNOWN") String connectivity,
            @NotNull @Pattern(regexp="GOOD|BAD|DEGRADED|UNKNOWN") String healthCode,
            @NotNull Boolean hasAlarm) { }
    public record Receipt(String messageId,String deviceId,long observedAt,long receivedAt,
            String sourceType,boolean simulated) { }
    /** 模拟器正常停止时声明设备计划下线：之后到恢复上报前的心跳超时不算故障，不生成异常处置单。 */
    public record PlannedOfflineInput(
            @NotBlank @Size(max=36) String deviceId,
            @NotBlank @Size(max=36) String sourceId) { }
    public record PlannedOffline(String deviceId,long declaredAt,boolean simulated) { }
}
