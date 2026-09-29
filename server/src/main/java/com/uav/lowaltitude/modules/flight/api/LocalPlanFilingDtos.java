package com.uav.lowaltitude.modules.flight.api;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.uav.lowaltitude.modules.directory.api.DirectoryDtos.*;

public final class LocalPlanFilingDtos {
 public static final String SIMULATOR_SOURCE_ID="local-flight-plan-simulator";
 public static final String SIMULATOR_SOURCE_NAME="数据模拟器（飞行计划）";
 private LocalPlanFilingDtos() { }
 public record Filing(@Size(max=36) String sourceId,
  @Size(max=128) String operatorName,@Size(max=128) String pilotName,
  @Size(max=128) String takeoffSiteName,@Size(max=128) String landingSiteName,
  @DecimalMin("-180") @DecimalMax("180") BigDecimal takeoffLongitude,
  @DecimalMin("-90") @DecimalMax("90") BigDecimal takeoffLatitude,
  @DecimalMin("-180") @DecimalMax("180") BigDecimal landingLongitude,
  @DecimalMin("-90") @DecimalMax("90") BigDecimal landingLatitude,
  @Size(max=36) String sourceBindingId,@Size(max=36) String operatorOrgId,@Size(max=36) String pilotContactId) { }
 public record Update(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String messageId,
  @NotNull @Min(0) Long expectedVersion,@NotNull @Valid Filing filing) { }
 public record Source(String sourceId,String sourceName,String sourceMode) { }
 public record Pilot(String contactId,String orgId,String name) { }
 public record Options(List<Source> planSources,List<Option> organizations,List<Pilot> pilots,
  List<SourceBinding> sourceBindings,List<String> unavailableSections) { }
 public record Detail(FlightDtos.FlightPlanDto plan,Subjects subjects) { }
}
