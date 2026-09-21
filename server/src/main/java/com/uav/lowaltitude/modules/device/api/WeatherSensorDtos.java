package com.uav.lowaltitude.modules.device.api;
import jakarta.validation.constraints.*;
public final class WeatherSensorDtos {
    private WeatherSensorDtos() { }
    public record Input(@NotBlank @Size(max=64) String deviceNo,@NotBlank @Size(max=128) String name,
        @Size(max=128) String vendor,@Size(max=128) String model,@Size(max=256) String address,
        @NotBlank @Size(max=36) String ownerOrgId,@NotBlank @Size(max=36) String districtId,@Min(0) Long version) { }
    public record Sensor(String deviceId,String deviceNo,String name,String vendor,String model,String address,
        String ownerOrgId,String districtId,long version) { }
}
