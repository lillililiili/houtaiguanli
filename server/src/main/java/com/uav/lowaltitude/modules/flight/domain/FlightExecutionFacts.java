package com.uav.lowaltitude.modules.flight.domain;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** A source assertion about one execution, independent of a candidate flight plan. */
public final class FlightExecutionFacts {
    private FlightExecutionFacts() { }
    public record PositionEvent(@NotNull @Positive Long occurredAt,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @DecimalMin("0") BigDecimal accuracyM) { }
    public record Input(@NotBlank @Size(max=36) String sourceId,
        @NotNull @Pattern(regexp="live|mock|replay") String sourceMode,
        @NotBlank @Size(max=128) String messageId,@NotNull @Positive Long version,
        @NotBlank @Size(max=36) String targetId,@NotBlank @Size(max=36) String trackId,
        @NotBlank @Size(max=128) String executionId,@NotNull @Positive Long validFrom,@NotNull @Positive Long validTo,
        @NotNull @Pattern(regexp="AIRBORNE|LANDED|UNKNOWN") String phase,
        @Valid PositionEvent takeoff,@Valid PositionEvent landing,
        @Size(max=36) String pilotContactId,@Size(max=36) String reportingBindingId,
        @NotEmpty @Size(max=20) List<@NotBlank @Size(max=256) String> evidenceRefs) { }
    public record Accepted(String factId,String sourceMode,boolean duplicate) { }
    public record Filing(String planId,long planVersion,BigDecimal takeoffLongitude,BigDecimal takeoffLatitude,
        BigDecimal landingLongitude,BigDecimal landingLatitude,String pilotId,String reportingOrgId,
        boolean pilotVerified,boolean reportingVerified) { }
    public record Actual(String factId,String sourceMode,Input input,String reportingOrgId,boolean pilotVerified,
        boolean reportingVerified,long receivedAt) { }
    public record Comparison(Filing filing,Actual actual,String unknownReason,long revision) {
        public Comparison(Filing filing,Actual actual,String reason){this(filing,actual,reason,0);}
        public static Comparison unknown(String reason){return new Comparison(null,null,reason,0);}
    }
}
