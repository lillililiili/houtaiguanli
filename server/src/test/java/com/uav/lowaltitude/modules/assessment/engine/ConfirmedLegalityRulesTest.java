package com.uav.lowaltitude.modules.assessment.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.*;
import com.uav.lowaltitude.modules.assessment.engine.checks.*;

/** Confirmed chapter II semantics, with changed identities, route widths and boundary instants. */
class ConfirmedLegalityRulesTest {
    private final OffsetDateTime at = OffsetDateTime.parse("2026-10-08T13:00:00Z");
    private final String sn = "SN-" + UUID.randomUUID();

    static TestRuleParams confirmed() {
        return TestRuleParams.demoCatalog().withStatus("CONFIRMED").put("C01", "corridor_tolerance_m", "100")
                .put("C01", "distance_basis", "ROUTE_CENTERLINE").put("C01", "time_window_end_inclusive", "true")
                .put("C02-3", "distance_basis", "ROUTE_CENTERLINE").put("C02-4", "grace_end_inclusive", "true")
                .put("C03", "own_plan_time_mismatch_policy", "OVERRUN").put("C03", "quality_window_basis", "AS_OF");
    }

    @ParameterizedTest @CsvSource({"10,20,FULL,PASS", "20,100,FULL,PASS", "20.01,200,FULL,FAIL", "30,100,FULL,FAIL", "100,500,FULL,FAIL", "100.01,500,NONE,NOT_APPLICABLE", "150,1000,NONE,NOT_APPLICABLE"})
    void matchingAndDeviationUseCenterlineWithoutAddingWidth(String distance, int width, PlanMatchCode expected, ResultCode deviation) {
        var params = confirmed();
        var plan = plan(width, at.minusMinutes(20), at.plusMinutes(20));
        var port = port(plan, distance);
        var match = new PlanMatchCheck().match(state(), List.of(plan), id -> port.distanceToRoute(state(), id), at, params);
        assertThat(match.code()).isEqualTo(expected);
        assertThat(new RouteDeviationCheck(port).evaluate(context(match, at), params).resultCode()).isEqualTo(deviation);
        // Corridor-based versions still use the stored width only when the task is associated.
        assertThat(new RouteDeviationCheck(port).evaluate(context(match, at), TestRuleParams.demoCatalog()).resultCode())
                .isEqualTo(expected == PlanMatchCode.NONE ? ResultCode.NOT_APPLICABLE
                        : new BigDecimal(distance).subtract(BigDecimal.valueOf(width / 2.0)).compareTo(BigDecimal.valueOf(20)) > 0 ? ResultCode.FAIL : ResultCode.PASS);
    }

    @ParameterizedTest @CsvSource({"-1,FULL,PASS", "0,FULL,PASS", "1,NONE,FAIL"})
    void graceIncludesExactlyTenMinutesAndNightAddsAnOverrunReason(long millis, PlanMatchCode code, ResultCode time) {
        var params = confirmed();
        var plan = plan(100, at.minusHours(1), at.minusMinutes(10));
        var moment = at.plusNanos(millis * 1_000_000);
        var match = new PlanMatchCheck().match(state(), List.of(plan), id -> port(plan, "10").distanceToRoute(state(), id), moment, params);
        var context = context(match, moment);
        var hits = checks(context, params, port(plan, "10"));
        assertThat(match.code()).isEqualTo(code);
        assertThat(hits).anyMatch(hit -> "C02-4".equals(hit.ruleCode()) && hit.resultCode() == time);
        var decision = new C03Decision().decide(context, hits, params);
        if (millis <= 0) assertThat(decision.status()).isEqualTo(LegalStatus.LEGAL);
        else {
            assertThat(decision.status()).isEqualTo(LegalStatus.ILLEGAL);
            assertThat(decision.violationReasons()).containsExactlyInAnyOrder("TIME_WINDOW_OVERRUN", "NIGHT_FLIGHT");
            assertThat(decision.grade()).isEqualTo("LOW");
            assertThat(new DecisionAssuranceAlgorithm().assess(context, hits, decision, params).status()).isEqualTo("SUFFICIENT");
        }
    }

    @Test void overrunPolicyDoesNotAuthorizeMissingIdentityOrAnOffRouteTarget() {
        var params = confirmed();
        var plan = plan(100, at.minusHours(1), at.minusMinutes(15));
        for (Map<String,String> dimensions : List.of(
                Map.of("identity", "MATCH", "corridor", "MISMATCH", "time_window", "MISMATCH"),
                Map.of("identity", "UNDETERMINED", "corridor", "MATCH", "time_window", "MISMATCH"))) {
            var match = new PlanMatch(PlanMatchCode.NONE, plan, dimensions, List.of("TIME_WINDOW_MISMATCH"));
            var context = context(match, at);
            var hits = checks(context, params, port(plan, "10"));
            var verdict = new C03Decision().decide(context, hits, params);
            assertThat(verdict.violationReasons()).contains("NO_AUTHORIZATION");
            assertThat(new DecisionAssuranceAlgorithm().assess(context, hits, verdict, params).status()).isEqualTo("INSUFFICIENT");
        }
    }

    @ParameterizedTest @ValueSource(strings={"live","mock","replay"})
    void confirmedRulesCarryEvidenceWithoutChangingTheInputSource(String sourceMode) {
        var params=confirmed();
        var plan=plan(100,at.minusHours(1),at.plusMinutes(10));
        var match=new PlanMatchCheck().match(state(),List.of(plan),id -> port(plan,"30").distanceToRoute(state(),id),at,params);
        var base=context(match,at);
        var context=new EvaluationContext(new Subject(SubjectKind.TARGET,state().targetId(),"test-org","test-district",sourceMode),
                state(),base.track(),match,List.of(),at,Freshness.FRESH,RunMode.ACTIVE,sourceMode);
        var hits=checks(context,params,port(plan,"30"));
        var verdict=new C03Decision().decide(context,hits,params);
        assertThat(verdict.violationReasons()).containsExactly("ROUTE_DEVIATION");
        assertThat(new DecisionAssuranceAlgorithm().assess(context,hits,verdict,params).status()).isEqualTo("SUFFICIENT");
        assertThat(context.sourceMode()).isEqualTo(sourceMode);
        assertThat(hits).allMatch(hit -> !hit.message().contains("DEMO"));
        if ("live".equals(sourceMode)) {
            var demo=params.withStatus("DEMO");
            assertThat(new DecisionAssuranceAlgorithm().assess(context,checks(context,demo,port(plan,"30")),verdict,demo).status()).isEqualTo("INSUFFICIENT");
        }
    }

    @ParameterizedTest @CsvSource({"-121,false", "-120,true", "0,true", "1,false"})
    void pilotPositionMustBelongToTheSameFreshDataWindow(long offsetSeconds, boolean usable) {
        var params=confirmed();
        var plan=plan(100,at.minusMinutes(10),at.plusMinutes(10));
        var match=new PlanMatch(PlanMatchCode.FULL,plan,Map.of(),List.of());
        var old=state();
        var pilot=new TargetState(old.targetId(),old.trackId(),sn,old.longitude(),old.latitude(),old.altitudeAmslM(),null,null,null,
                old.confidence(),at,at,old.longitude().add(new BigDecimal("0.02")),old.latitude(),at.plusSeconds(offsetSeconds));
        var base=context(match,at);
        var context=new EvaluationContext(base.subject(),pilot,base.track(),match,List.of(),at,Freshness.FRESH,RunMode.ACTIVE,"mock");
        var hits=checks(context,params,port(plan,"10"));
        var bvlos=hits.stream().filter(hit -> "C02-6".equals(hit.ruleCode())).findFirst().orElseThrow();
        assertThat(bvlos.resultCode()).isEqualTo(usable ? ResultCode.PASS : ResultCode.UNDETERMINED);
        var verdict=new C03Decision().decide(context,hits,params);
        assertThat(verdict.status()).isEqualTo(LegalStatus.LEGAL);
        assertThat(verdict.violationReasons()).doesNotContain("BVLOS_EXCEEDED");
        if (usable) assertThat(bvlos.facts()).containsEntry("beyond_vlos", true);
        // 新研判均采用距离提示；旧研判记录不回写。
        assertThat(new VisualLineOfSightCheck().evaluate(context,TestRuleParams.demoCatalog()).resultCode()).isEqualTo(ResultCode.PASS);
    }

    @Test void pilotPositionWithoutObservationTimeIsNotCurrentEvidence() {
        var old=state();
        var pilot=new TargetState(old.targetId(),old.trackId(),sn,old.longitude(),old.latitude(),old.altitudeAmslM(),null,null,null,
                old.confidence(),at,at,old.longitude().add(new BigDecimal("0.02")),old.latitude(),null);
        var base=context(null,at);
        var context=new EvaluationContext(base.subject(),pilot,base.track(),null,List.of(),at,Freshness.FRESH,RunMode.ACTIVE,"mock");
        assertThat(new VisualLineOfSightCheck().evaluate(context,confirmed()).reasonCode()).isEqualTo("PILOT_POSITION_UNAVAILABLE");
    }

    @ParameterizedTest @CsvSource({"live,false,LEGAL", "mock,false,LEGAL", "replay,false,LEGAL", "live,true,ILLEGAL", "mock,true,ILLEGAL", "replay,true,ILLEGAL"})
    void permittedUnregisteredFlightPreservesTheMatchingFactAndOtherIndependentRules(String source,boolean night,LegalStatus expected) {
        var params=confirmed().put("C03","no_plan_status","LEGAL");
        var context=unregistered(source,night?at:at.minusHours(12),"0.9",new TrackQuality(5,5L,false));
        var hits=checks(context,params,port(plan(100,at.minusHours(1),at.plusHours(1)),"10"));
        var verdict=new C03Decision().decide(context,hits,params);
        assertThat(verdict.status()).isEqualTo(expected);
        assertThat(verdict.violationReasons()).doesNotContain("NO_AUTHORIZATION");
        assertThat(context.planMatch().code()).isEqualTo(PlanMatchCode.NONE);
        assertThat(hits).anyMatch(hit -> "C01".equals(hit.ruleCode())&&hit.resultCode()==ResultCode.FAIL);
        for(String rule:List.of("C02-3","C02-4","C02-7")) assertThat(hits).anyMatch(hit -> rule.equals(hit.ruleCode())&&hit.resultCode()==ResultCode.NOT_APPLICABLE);
        assertThat(new DecisionAssuranceAlgorithm().assess(context,hits,verdict,params).status()).isEqualTo("SUFFICIENT");
        if(night) assertThat(verdict.violationReasons()).containsExactly("NIGHT_FLIGHT");
        else assertThat(verdict.violationReasons()).isEmpty();
    }

    @Test void permittedUnregisteredFlightStillRequiresQualityAndCompleteIndependentEvidence() {
        var params=confirmed().put("C03","no_plan_status","LEGAL");
        var context=unregistered("live",at.minusHours(12),"0.74",new TrackQuality(2,31L,false));
        var hits=checks(context,params,port(plan(100,at.minusHours(1),at.plusHours(1)),"10"));
        var verdict=new C03Decision().decide(context,hits,params);
        assertThat(verdict.status()).isEqualTo(LegalStatus.UNDETERMINED);
        assertThat(new DecisionAssuranceAlgorithm().assess(context,hits,verdict,params).status()).isEqualTo("INSUFFICIENT");
        var good=unregistered("live",at.minusHours(12),"0.9",new TrackQuality(5,5L,false));
        var unknown=new HitDetail("C02-2",null,ResultCode.UNDETERMINED,"ALTITUDE_DATUM_OR_RANGE_UNKNOWN",null,Map.of(),List.of(),List.of(),"");
        var missing=hits.stream().map(hit -> "C02-2".equals(hit.ruleCode())?unknown:hit).toList();
        assertThat(new C03Decision().decide(good,missing,params).status()).isEqualTo(LegalStatus.UNDETERMINED);
        var demo=params.withStatus("DEMO");
        assertThat(new DecisionAssuranceAlgorithm().assess(good,checks(good,demo,port(plan(100,at.minusHours(1),at.plusHours(1)),"10")),
                new C03Decision().decide(good,hits,params),demo).status()).isEqualTo("INSUFFICIENT");
    }

    @Test void unregisteredPolicyDoesNotSuppressIndependentAirspaceViolationOrRewriteTheOldPolicy() {
        var params=confirmed().put("C03","no_plan_status","LEGAL");
        var context=unregistered("live",at.minusHours(12),"0.9",new TrackQuality(5,5L,false));
        var ordinary=checks(context,params,port(plan(100,at.minusHours(1),at.plusHours(1)),"10"));
        var restricted=new HitDetail("C02-1",null,ResultCode.FAIL,"INSIDE_RESTRICTED_AIRSPACE",null,Map.of(),List.of(),List.of(),"");
        var hits=ordinary.stream().map(hit -> "C02-1".equals(hit.ruleCode())?restricted:hit).toList();
        var verdict=new C03Decision().decide(context,hits,params);
        assertThat(verdict.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(verdict.violationReasons()).containsExactly("INSIDE_RESTRICTED_AIRSPACE");
        assertThat(new DecisionAssuranceAlgorithm().assess(context,hits,verdict,params).status()).isEqualTo("SUFFICIENT");
        var forgedLegal=new C03Decision.Decision(LegalStatus.LEGAL,null,List.of(),List.of(),null,null);
        assertThat(new DecisionAssuranceAlgorithm().assess(context,hits,forgedLegal,params).status()).isEqualTo("INSUFFICIENT");
        var legacy=confirmed();
        var old=new C03Decision().decide(context,ordinary,legacy);
        assertThat(old.status()).isEqualTo(LegalStatus.ILLEGAL);
        assertThat(old.violationReasons()).containsExactly("NO_AUTHORIZATION");
        assertThat(new DecisionAssuranceAlgorithm().assess(context,ordinary,old,legacy).reasons()).contains("PLAN_AUTHORIZATION_UNVERIFIED");
    }

    @Test void permittedUnregisteredFlightKeepsPilotDistanceAsAdvisory() {
        var params=confirmed().put("C03","no_plan_status","LEGAL");
        var base=unregistered("live",at.minusHours(12),"0.9",new TrackQuality(5,5L,false));
        var old=base.state();
        var state=new TargetState(old.targetId(),old.trackId(),sn,old.longitude(),old.latitude(),old.altitudeAmslM(),null,null,null,
                old.confidence(),base.asOf(),base.asOf(),old.longitude().add(new BigDecimal("0.02")),old.latitude(),base.asOf());
        var context=new EvaluationContext(base.subject(),state,base.track(),base.planMatch(),List.of(),base.asOf(),Freshness.FRESH,RunMode.ACTIVE,"live");
        var hits=checks(context,params,port(plan(100,at.minusHours(1),at.plusHours(1)),"10"));
        var verdict=new C03Decision().decide(context,hits,params);
        assertThat(verdict.status()).isEqualTo(LegalStatus.LEGAL);
        assertThat(verdict.violationReasons()).isEmpty();
        assertThat(verdict.grade()).isNull();
        assertThat(hits.stream().filter(hit -> "C02-6".equals(hit.ruleCode())).findFirst().orElseThrow().facts())
                .containsEntry("beyond_vlos", true);
        assertThat(new DecisionAssuranceAlgorithm().assess(context,hits,verdict,params).status()).isEqualTo("SUFFICIENT");
    }

    private EvaluationContext unregistered(String source,OffsetDateTime moment,String confidence,TrackQuality quality) {
        var old=state();
        var state=new TargetState(old.targetId(),old.trackId(),sn,old.longitude(),old.latitude(),old.altitudeAmslM(),null,null,null,new BigDecimal(confidence),moment,moment);
        return new EvaluationContext(new Subject(SubjectKind.TARGET,state.targetId(),"test-org","test-district",source),state,quality,
                new PlanMatch(PlanMatchCode.NONE,null,Map.of(),List.of("NO_PLAN_CANDIDATE")),List.of(),moment,Freshness.FRESH,RunMode.ACTIVE,source);
    }

    @ParameterizedTest @CsvSource({"0.749,3,30,UNDETERMINED", "0.75,3,30,ILLEGAL", "0.9,2,30,UNDETERMINED", "0.9,3,31,UNDETERMINED"})
    void qualityBoundariesStillGateIndependentAirspaceViolation(String confidence, int points, long gap, LegalStatus expected) {
        var base = context(null, at);
        var state = state();
        var input = new TargetState(state.targetId(), state.trackId(), sn, state.longitude(), state.latitude(), state.altitudeAmslM(), state.heightAglM(),
                null, null, new BigDecimal(confidence), at, at);
        var ctx = new EvaluationContext(base.subject(), input, new TrackQuality(points, gap, false), null, List.of(), at, Freshness.FRESH, RunMode.ACTIVE, "mock");
        var hit = new HitDetail("C02-1", null, ResultCode.FAIL, "INSIDE_RESTRICTED_AIRSPACE", null, Map.of(), List.of(), List.of(), "");
        assertThat(new C03Decision().decide(ctx, List.of(hit), confirmed()).status()).isEqualTo(expected);
    }

    static List<HitDetail> checks(EvaluationContext context, RuleParams params, SpatialFactPort port) {
        return List.<RuleCheck>of(new PlanMatchCheck(), new RestrictedAirspaceCheck(), new AirspaceAltitudeCheck(),
                new RouteDeviationCheck(port), new TimeWindowCheck(), new NightFlightCheck(), new VisualLineOfSightCheck(),
                new PlanAltitudeCheck(), new TemporaryRestrictionCheck()).stream().map(check -> check.evaluate(context, params)).toList();
    }
    private PlanFact plan(int width, OffsetDateTime start, OffsetDateTime end) {
        return new PlanFact(UUID.randomUUID().toString(), UUID.randomUUID().toString(), sn, start, end, BigDecimal.valueOf(width),
                BigDecimal.ZERO, BigDecimal.valueOf(120), "AMSL", "test-org", "test-district");
    }
    private TargetState state() {
        return new TargetState("target-" + sn, "track-" + sn, sn, new BigDecimal("116.2"), new BigDecimal("35.5"),
                BigDecimal.valueOf(80), null, null, null, new BigDecimal("0.9"), at, at);
    }
    private EvaluationContext context(PlanMatch match, OffsetDateTime moment) {
        return new EvaluationContext(new Subject(SubjectKind.TARGET, state().targetId(), "test-org", "test-district", "mock"),
                state(), new TrackQuality(5, 5L, false), match, List.of(), moment, Freshness.FRESH, RunMode.ACTIVE, "mock");
    }
    private SpatialFactPort port(PlanFact plan, String distance) {
        return new SpatialFactPort() {
            public List<AirspaceHit> airspaceHits(TargetState state, OffsetDateTime time) { return List.of(); }
            public RouteDistance distanceToRoute(TargetState state, String id) { return new RouteDistance(id, new BigDecimal(distance), plan.corridorWidthM().divide(BigDecimal.valueOf(2)), null); }
            public boolean ambiguousEffectiveAirspaceVersion(OffsetDateTime time) { return false; }
        };
    }
}
