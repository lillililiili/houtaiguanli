package com.uav.lowaltitude.modules.flightavoidance.api;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class FlightAvoidanceDtos {
    private FlightAvoidanceDtos() { }

    public record Policy(long version, BigDecimal forceLandBlockKm, BigDecimal forceLandWarnKm, int forceLandMaxAltitudeM,
            BigDecimal driveAwayWarnKm, int driveAwayMaxAltitudeM, long updatedAt, boolean canManage) { }

    public record PolicyInput(@Positive long expectedVersion,
            @NotNull @DecimalMin("0.5") @DecimalMax("350") BigDecimal forceLandBlockKm,
            @NotNull @DecimalMin("0.5") @DecimalMax("350") BigDecimal forceLandWarnKm,
            @Min(100) @Max(15000) int forceLandMaxAltitudeM,
            @NotNull @DecimalMin("0.5") @DecimalMax("350") BigDecimal driveAwayWarnKm,
            @Min(100) @Max(15000) int driveAwayMaxAltitudeM) { }

    public record Change(String changeId, long version, String actor, long createdAt, Policy before, Policy after) { }
    public record ChangePage(List<Change> items, int page, int size, long total) { }

    /** level：OFF 没接入航班接收机、不检查；CLEAR 附近没有；WARN 提醒范围有；NO_DATA 收不到航班信息；BLOCK 禁止反制范围有。 */
    public record Check(String level, String countermeasureMode, BigDecimal blockKm, BigDecimal warnKm, int maxAltitudeM,
            List<NearbyAircraft> aircraft, long policyVersion) { }

    public record NearbyAircraft(String icao24, String callsign, String airline, double distanceKm, Integer altitudeM,
            boolean approaching, String zone) { }

    public record Receiver(String deviceId, String deviceNo, String name, Double longitude, Double latitude,
            int receiveRangeKm, Long lastDataAt, boolean online, boolean simulated) { }

    public record Aircraft(String icao24, String callsign, String airline, double longitude, double latitude, Integer altitudeM,
            Double groundSpeedMps, Double trackDeg, long observedAt) { }

    /** 地图用：在用的接收机、最近收到的航班，以及画范围圈用的规则。 */
    public record Traffic(List<Receiver> receivers, List<Aircraft> aircraft, Policy policy, long servedAt) { }

    public record CheckRecord(String checkId, String eventId, String authorizationId, String deviceId, String deviceName,
            String countermeasureMode, String stage, String result, boolean acknowledged, String actorName,
            List<NearbyAircraft> aircraft, long checkedAt) { }

    /* ---- 航班接收机登记 ---- */

    public record ReceiverInput(@NotBlank @Size(max = 64) String deviceNo, @NotBlank @Size(max = 128) String name,
            @Size(max = 128) String vendor, @Size(max = 128) String model, @Size(max = 256) String address,
            @NotBlank @Size(max = 36) String ownerOrgId, @NotBlank @Size(max = 36) String districtId,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @Min(10) @Max(600) int receiveRangeKm, @Min(0) Long version) { }

    public record ReceiverProfile(String deviceId, String deviceNo, String name, String vendor, String model, String address,
            String ownerOrgId, String districtId, BigDecimal longitude, BigDecimal latitude, int receiveRangeKm,
            boolean enabled, boolean simulated, long version) { }

    /** 设备模拟器：登记一台模拟航班接收机（只在本地测试环境）。 */
    public record SimulatedReceiverInput(@NotBlank @Size(max = 64) String deviceNo, @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 36) String ownerOrgId, @NotBlank @Size(max = 36) String districtId,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @Min(10) @Max(600) int receiveRangeKm) { }

    /** 设备模拟器：一批航班位置。空列表表示接收机在线但附近没有航班。 */
    public record SimulatedReport(@NotNull @Size(max = 500) List<@Valid ReportedAircraft> aircraft) { }

    public record ReportedAircraft(@NotBlank @Pattern(regexp = "[0-9A-Fa-f]{6}") String icao24,
            @Size(max = 16) String callsign, @Size(max = 64) String airline,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @Min(-500) @Max(20000) Integer altitudeM, @DecimalMin("0") @DecimalMax("400") Double groundSpeedMps,
            @DecimalMin("0") @DecimalMax("360") Double trackDeg, @NotNull Long observedAt) { }
}
