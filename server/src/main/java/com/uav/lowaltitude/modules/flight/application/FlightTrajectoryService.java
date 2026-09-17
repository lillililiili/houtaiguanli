package com.uav.lowaltitude.modules.flight.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.SpatialFactPort;
import com.uav.lowaltitude.modules.assessment.application.LegalityEvaluationReadService;
import com.uav.lowaltitude.modules.assessment.engine.RuleContracts.TargetState;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightActualsRepository;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightReadRepository;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TrackPointDto;
import com.uav.lowaltitude.modules.target.api.TargetDtos.TrackSummaryDto;
import com.uav.lowaltitude.platform.api.ApiException;

/** 只读轨迹对照。使用计划钉住的航线和已有 PostGIS 米制距离，不产生合法性结论。 */
@Service
public class FlightTrajectoryService {
    private final FlightReadRepository plans;
    private final FlightActualsRepository evaluations;
    private final AccessControlService access;
    private final TargetReadService targets;
    private final SpatialFactPort spatial;
    private final LegalityEvaluationReadService evaluationReads;
    public record Point(String pointId,String trackId,long pointSeq,long observedAt,BigDecimal longitude,
            BigDecimal latitude,String corridorRelation,boolean breakBefore) { }
    public record Trajectory(String availability,String targetId,Long gapMillis,String paramStatus,List<Point> points,String note) { }
    public FlightTrajectoryService(FlightReadRepository plans,FlightActualsRepository evaluations,AccessControlService access,
            TargetReadService targets,SpatialFactPort spatial,LegalityEvaluationReadService evaluationReads){this.plans=plans;this.evaluations=evaluations;this.access=access;this.targets=targets;this.spatial=spatial;this.evaluationReads=evaluationReads;}

    @Transactional(readOnly=true)
    public Trajectory read(String planId) {
        var plan=plans.findPlan(FlightActualsService.identifier(planId),access.require(PermissionCode.FLIGHT_READ));
        if(plan==null)throw new ApiException(HttpStatus.NOT_FOUND,"FLIGHT_PLAN_NOT_FOUND","飞行计划不存在或不可见");
        if(!Set.of("EXECUTING","COMPLETED").contains(plan.statusCode()))return empty("NOT_APPLICABLE","未执行或已取消计划不绘制实际对照");
        var decision=access.require(PermissionCode.ASSESSMENT_READ);
        access.require(PermissionCode.TARGET_READ);
        var routeAccess=access.require(PermissionCode.ROUTE_READ);
        if(plan.routeVersionId()==null || plans.findRouteVersion(plan.routeVersionId(),routeAccess)==null)
            return empty("UNAVAILABLE","缺少可见的计划航线版本");
        if(plan.startAt()==null || plan.endAt()==null)return empty("UNAVAILABLE","计划时段不完整，不能截取实际轨迹");
        var evaluation=evaluations.findLatestActiveEvaluation(plan.planId(),decision);
        if(evaluation==null || evaluation.targetId()==null)return empty("NO_EVALUATION","尚未关联感知目标；没有轨迹不代表未起飞");
        Long gap=evaluations.trackGapMillis(evaluation.evaluationId());
        List<TrackSummaryDto> tracks=new ArrayList<>();
        for(int page=1;;page++){
            var data=targets.tracks(evaluation.targetId(),params(page));tracks.addAll(data.items());
            if(tracks.size()>=data.total())break;
            if(data.items().isEmpty())throw incomplete();
        }
        long from=plan.startAt().toInstant().toEpochMilli(),to=plan.endAt().toInstant().toEpochMilli();
        tracks.removeIf(t->(t.startedAt()!=null && t.startedAt()>to) || (t.endedAt()!=null && t.endedAt()<from));
        boolean fused=tracks.stream().anyMatch(t->"FUSED".equals(t.layer()));
        return compare(evaluation.targetId(),plan.routeVersionId(),gap,evaluation.paramStatus(),
                tracks.stream().filter(t->!fused || "FUSED".equals(t.layer())).map(TrackSummaryDto::trackId).toList(),from,to);
    }

    /** 研判地图必须钉住本次研判，不能借计划的最新研判切换到另一架飞机。 */
    @Transactional(readOnly=true)
    public Trajectory readEvaluation(String evaluationId) {
        var evaluation=evaluationReads.detail(evaluationId);
        access.require(PermissionCode.TARGET_READ);
        if(evaluation.targetId()==null)return empty("UNAVAILABLE","本次研判没有可查看的目标轨迹");
        String routeId=evaluation.routeVersionId();
        if(routeId!=null){
            var routeAccess=access.require(PermissionCode.ROUTE_READ);
            if(plans.findRouteVersion(routeId,routeAccess)==null)
                throw new ApiException(HttpStatus.NOT_FOUND,"ROUTE_VERSION_NOT_FOUND","研判引用的航线不存在或不可见");
        }
        long to=Math.min(evaluation.asOf(),evaluation.evaluatedAt());
        var query=params(1);query.add("layer","FUSED");
        query.add("started_from","0");query.add("started_to",String.valueOf(to));
        var fused=targets.tracks(evaluation.targetId(),query).items();
        List<String> ids;
        if(!fused.isEmpty())ids=List.of(fused.get(0).trackId());
        else if(evaluation.trackId()!=null){
            boolean found=false;
            for(int page=1;;page++){
                var batch=targets.tracks(evaluation.targetId(),params(page));
                if(batch.items().stream().anyMatch(t->evaluation.trackId().equals(t.trackId()))){found=true;break;}
                if((long)page*100>=batch.total())break;
                if(batch.items().isEmpty())throw incomplete();
            }
            ids=found?List.of(evaluation.trackId()):List.of();
        }else ids=List.of();
        return compare(evaluation.targetId(),routeId,evaluations.trackGapMillis(evaluation.evaluationId()),
                evaluation.paramStatus(),ids,0,to);
    }

    private Trajectory compare(String targetId,String routeVersionId,Long gap,String paramStatus,
            List<String> trackIds,long from,long to) {
        List<Point> result=new ArrayList<>();boolean spatialAvailable=true;
        for(var trackId:trackIds){
            List<TrackPointDto> raw=new ArrayList<>();
            for(int page=1;;page++){
                var query=params(page);query.add("time_from",String.valueOf(from));
                query.add("time_to",String.valueOf(to));
                var data=targets.points(trackId,query);raw.addAll(data.items());
                if(raw.size()>=data.total())break;
                if(data.items().isEmpty())throw incomplete();
            }
            TrackPointDto previous=null;
            for(var point:raw){
                if(!measured(point)){previous=null;continue;}
                var location=point.location();String relation="UNKNOWN";
                if(spatialAvailable && routeVersionId!=null)try {
                    var distance=spatial.distanceToRoute(new TargetState(targetId,trackId,null,
                        location.longitude(),location.latitude(),point.altitudeAmslM(),point.heightAglM(),null,null,null,null,null),routeVersionId);
                    if(distance.unknownReason()==null && distance.distanceM()!=null && distance.halfWidthM()!=null){
                        int comparison=distance.distanceM().compareTo(distance.halfWidthM());
                        relation=comparison<0?"WITHIN":comparison>0?"OUTSIDE":"BOUNDARY";
                    }
                }catch(ApiException ex){if(!"SPATIAL_BACKEND_UNAVAILABLE".equals(ex.getCode()))throw ex;spatialAvailable=false;}
                boolean disconnect=!continuous(previous,point,gap);
                result.add(new Point(point.pointId(),point.trackId(),point.pointSeq(),point.observedAt(),location.longitude(),location.latitude(),relation,disconnect));
                previous=point;
            }
        }
        String note=routeVersionId==null?"未关联可比对的计划航线，轨迹关系未知；缺失轨迹断开，不补点。":"颜色只表示实测位置与计划走廊的横向关系，不代表合法性；缺失轨迹断开，不补点。";
        if(result.isEmpty())note=routeVersionId==null?"本次研判暂无有效实测轨迹。":"已有计划匹配记录，但查询时段内没有可用实测点，计划线保留灰色虚线。";
        else if(result.stream().map(p->List.of(p.longitude().stripTrailingZeros(),p.latitude().stripTrailingZeros())).distinct().limit(2).count()==1)
            note="实测点都在同一位置，只显示位置点；完全匹配不代表已有整条飞行轨迹，计划线仍为灰色虚线。";
        if(gap==null)note+="缺少轨迹间隔参数，仅显示实测点。";
        if(!spatialAvailable)note+="空间计算不可用，范围关系未知。";
        return new Trajectory("AVAILABLE",targetId,gap,paramStatus,List.copyOf(result),note);
    }
    static boolean measured(TrackPointDto point){
        var p=point.location();
        return point.observedAt()!=null && (point.pointKind()==null || "MEAS".equals(point.pointKind())) && p!=null
            && "WGS84".equals(p.coordinateSystem()) && p.longitude()!=null && p.latitude()!=null
            && p.longitude().abs().compareTo(BigDecimal.valueOf(180))<=0 && p.latitude().abs().compareTo(BigDecimal.valueOf(90))<=0;
    }
    static boolean continuous(TrackPointDto previous,TrackPointDto point,Long gap){
        return previous!=null && gap!=null && measured(previous) && measured(point)
            && previous.trackId().equals(point.trackId()) && point.pointSeq()==previous.pointSeq()+1
            && point.observedAt()>previous.observedAt() && point.observedAt()-previous.observedAt()<=gap;
    }
    private static LinkedMultiValueMap<String,String> params(int page){var params=new LinkedMultiValueMap<String,String>();params.add("page",String.valueOf(page));params.add("size","100");return params;}
    private static Trajectory empty(String availability,String note){return new Trajectory(availability,null,null,null,List.of(),note);}
    private static ApiException incomplete(){return new ApiException(HttpStatus.CONFLICT,"TRAJECTORY_CHANGED","轨迹分页发生变化，请重新读取");}
}
