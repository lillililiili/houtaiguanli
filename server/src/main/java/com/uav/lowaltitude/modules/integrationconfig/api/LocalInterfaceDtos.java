package com.uav.lowaltitude.modules.integrationconfig.api;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.databind.JsonNode;

public final class LocalInterfaceDtos {
 private LocalInterfaceDtos(){}
 public record PlanInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String messageId,
  @NotBlank @Size(max=36) String routeVersionId,@NotBlank @Size(max=128) String uavSn,
  @NotNull @Positive Long startAt,@NotNull @Positive Long endAt){}
 public record WeatherInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String messageId,
  @NotBlank @Size(max=36) String planId,@NotBlank @Size(max=128) String areaName,
  @NotNull @Positive Long publishedAt,@NotEmpty @Size(max=48) List<@Valid Period> periods){}
 public record Period(@NotNull @Positive Long from,@NotNull @Positive Long to,@NotBlank @Size(max=128) String summary,
  @NotNull @DecimalMin("-90") @DecimalMax("60") Double temperatureC,
  @NotNull @DecimalMin("0") @DecimalMax("150") Double windSpeedMs,
  @NotNull @DecimalMin("0") @DecimalMax("150") Double gustMs,
  @NotNull @Min(0) @Max(360) Integer windDirectionDeg,
  @NotNull @Min(0) @Max(100) Integer precipitationProbabilityPct,
  @NotNull @Min(0) @Max(100) Integer humidityPct){}
 public record BindingInput(@Pattern(regexp="RISK|UAV_EVENT") @NotNull String sourceKind,
  @NotBlank @Size(max=36) String sourceId,@NotNull Boolean enabled){}
 public record Binding(String sourceKind,String sourceId,boolean enabled,long expiresAt){}
 public record ReceiptInput(@NotNull @Min(0) Long expectedVersion,
  @Pattern(regexp="DELIVERED|ACKNOWLEDGED|FAILED|TIMEOUT") @NotNull String outcome){}
 public record Message(String messageId,String kind,String direction,String subjectId,String state,long version,
  long createdAt,JsonNode payload,JsonNode result){}
 public record RouteOption(String routeVersionId,String routeId,String name,String routeNo,long validFrom,Long validTo){}
 public record PlanOption(String planId,String planNo,Long startAt,Long endAt){}
 public record SourceOption(String sourceKind,String sourceId,String label){}
 public record Context(List<RouteOption> routes,List<PlanOption> plans,List<Binding> bindings,List<Message> messages,List<SourceOption> sources,List<String> unavailableSections){}
}
