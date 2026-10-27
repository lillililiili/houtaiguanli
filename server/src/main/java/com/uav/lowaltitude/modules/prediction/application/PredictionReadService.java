package com.uav.lowaltitude.modules.prediction.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.AlarmDto;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.PageDto;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.*;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

@Service
public class PredictionReadService {
    private static final String BASIS_COLUMNS=",a.detail AS frozen_detail,basis.prediction_id AS last_evidence_prediction_id"
            +",basis.generated_at AS last_evidence_generated_at,basis.based_on_observed_at AS last_evidence_observed_at";
    private static final String BASIS_JOINS=" LEFT JOIN alarm a ON a.alarm_id=e.alarm_id LEFT JOIN prediction_snapshot basis ON basis.prediction_id=("
            +"SELECT b.prediction_id FROM prediction_episode_snapshot bs JOIN prediction_snapshot b ON b.prediction_id=bs.prediction_id"
            +" WHERE bs.episode_id=p.episode_id AND b.status='READY' AND b.target_id=p.target_id"
            +" AND b.owner_org_id=p.owner_org_id AND b.district_id=p.district_id AND b.source_mode=p.source_mode"
            +" ORDER BY b.generated_at DESC,b.prediction_id DESC FETCH FIRST 1 ROW ONLY)";
    private final NamedParameterJdbcTemplate jdbc;
    private final AccessControlService access;
    private final ObjectMapper json;
    private final AppClock clock;
    private com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity riskCases;
    @org.springframework.beans.factory.annotation.Autowired
    public void riskCases(com.uav.lowaltitude.modules.alarm.application.UavRiskCaseIdentity value){riskCases=value;}
    public PredictionReadService(NamedParameterJdbcTemplate jdbc,AccessControlService access,ObjectMapper json,AppClock clock) {
        this.jdbc=jdbc; this.access=access; this.json=json; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> target(String id) {
        var decision=access.require(PermissionCode.TARGET_READ);
        Map<String,Object> params=new HashMap<>(); params.put("id",id); params.put("user",decision.userId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM target t WHERE t.target_id=:id"+scope(decision),params,Long.class)!=1)
            throw new ApiException(HttpStatus.NOT_FOUND,"TARGET_NOT_FOUND","目标不存在");
        var found=jdbc.query("SELECT s.result_json FROM prediction_current c JOIN prediction_snapshot s ON s.prediction_id=c.prediction_id"
                +" JOIN target t ON t.target_id=c.target_id WHERE c.target_id=:id AND s.owner_org_id=t.owner_org_id AND s.district_id=t.district_id AND s.source_mode=t.source_mode",
                params,(r,n)->object(r.getString(1)));
        if(found.isEmpty()) return new LinkedHashMap<>(Map.of("target_id",id,"status","UNKNOWN","reason_codes",List.of("INSUFFICIENT_POINTS"),
                "points",List.of(),"warnings",List.of(),"generated_at",clock.nowMillis(),"valid_until",clock.nowMillis()));
        return decorate(found.get(0),warnings(id));
    }
    @Transactional(readOnly=true)
    public PageDto<Map<String,Object>> current(MultiValueMap<String,String> values) {
        var decision=access.require(PermissionCode.TARGET_READ);
        if(values.keySet().stream().anyMatch(k->!Set.of("page","size").contains(k))) throw invalid();
        int page=integer(values,"page",1),size=integer(values,"size",100);
        if(page<1 || size<1 || size>100 || page>Integer.MAX_VALUE/size) throw invalid();
        Map<String,Object> p=new HashMap<>(); p.put("user",decision.userId());p.put("offset",(page-1)*size);p.put("size",size);
        String from=" FROM prediction_current c JOIN prediction_snapshot s ON s.prediction_id=c.prediction_id JOIN target t ON t.target_id=c.target_id"
                +" WHERE t.owner_org_id=s.owner_org_id AND t.district_id=s.district_id AND t.source_mode=s.source_mode"+scope(decision);
        long count=jdbc.queryForObject("SELECT COUNT(*)"+from,p,Long.class);
        List<Map<String,Object>> items=jdbc.query("SELECT s.result_json"+from+" ORDER BY s.target_id OFFSET :offset ROWS FETCH NEXT :size ROWS ONLY",p,(r,n)->object(r.getString(1)));
        // One batch for lifecycle summaries, including UNKNOWN episodes when a target has disappeared from map push.
        Map<String,List<Map<String,Object>>> grouped=new HashMap<>();
        if(!items.isEmpty()) {
            var references=alarmReferences();
            p.put("ids",items.stream().map(i->i.get("target_id")).toList());
            for(var row:jdbc.queryForList("SELECT p.*,l.event_id,e.alarm_id"+BASIS_COLUMNS+" FROM prediction_episode p LEFT JOIN prediction_event_link l"
                    +" ON l.episode_id=p.episode_id AND l.role='PREDICTED' LEFT JOIN uav_event e ON e.event_id=l.event_id"
                    +BASIS_JOINS
                    +" JOIN target pt ON pt.target_id=p.target_id AND pt.owner_org_id=p.owner_org_id AND pt.district_id=p.district_id AND pt.source_mode=p.source_mode"
                    +" WHERE p.target_id IN (:ids) AND p.first_prediction_id IS NOT NULL AND p.active_key IS NOT NULL ORDER BY p.started_at DESC",p))
                grouped.computeIfAbsent((String)row.get("target_id"),k->new ArrayList<>()).add(warning(row,references.test(row)));
        }
        return new PageDto<>(items.stream().map(item->decorate(item,grouped.getOrDefault(item.get("target_id"),List.of()))).toList(),page,size,count);
    }
    /** Called only after AlarmReadService has authorized this exact alarm. */
    public com.uav.lowaltitude.modules.alarm.api.AlarmDtos.VerificationBasisDto verificationEvidence(AlarmDto dto) {
        String selected=selectedCase(dto),event=dto.getEventId().value();
        Long version=dto.getRiskCase()==null?null:number(dto.getRiskCase().get("version"));
        List<Map<String,Object>> rows=selected==null||event==null||version==null?List.of():jdbc.queryForList(
                "SELECT p.*,l.role,l.event_id,e.alarm_id,s.result_json,s.status AS snapshot_status"
                +",s.generated_at AS snapshot_generated_at,s.valid_until AS snapshot_valid_until"
                +" FROM uav_risk_case c JOIN uav_risk_case_member m ON m.risk_case_id=c.risk_case_id AND m.event_id=:event"
                +" JOIN uav_event e ON e.event_id=m.event_id AND e.owner_org_id=c.owner_org_id AND e.district_id=c.district_id"
                +" JOIN alarm a ON a.alarm_id=e.alarm_id AND a.target_id=c.target_id AND a.owner_org_id=c.owner_org_id"
                +" AND a.district_id=c.district_id AND a.source_mode=c.source_mode"
                +" JOIN prediction_episode p ON p.episode_id=c.episode_id AND p.target_id=c.target_id"
                +" AND p.owner_org_id=c.owner_org_id AND p.district_id=c.district_id AND p.source_mode=c.source_mode"
                +" JOIN target t ON t.target_id=c.target_id AND t.owner_org_id=c.owner_org_id"
                +" AND t.district_id=c.district_id AND t.source_mode=c.source_mode"
                +" JOIN prediction_event_link l ON l.episode_id=p.episode_id AND l.event_id=e.event_id AND l.role='PREDICTED'"
                +" JOIN prediction_snapshot s ON s.prediction_id=p.latest_prediction_id AND s.target_id=p.target_id"
                +" AND s.owner_org_id=p.owner_org_id AND s.district_id=p.district_id AND s.source_mode=p.source_mode"
                +" WHERE c.risk_case_id=:case_id AND c.version=:version AND c.superseded_by IS NULL AND e.alarm_id=:alarm",
                Map.of("case_id",selected,"version",version,"event",event,"alarm",dto.getAlarmId()));
        long now=clock.nowMillis();
        String block="缺少当前可靠实测和有效预测，暂不能核实预测风险";
        if(rows.size()==1){
            var row=rows.get(0);
            var facts=new PredictionEventGate.Facts((String)row.get("episode_id"),event,(String)row.get("role"),
                    (String)row.get("status"),((Number)row.get("version")).longValue(),(String)row.get("latest_prediction_id"),
                    number(row.get("last_observed_at")),number(row.get("valid_until")),Boolean.TRUE.equals(row.get("current_evidence_valid")),
                    Boolean.TRUE.equals(row.get("entered_evidence_valid")),Boolean.TRUE.equals(row.get("manual_counter_only")),false,null);
            block=PredictionEventGate.confirmationBlock(facts,now);
            if(!"READY".equals(row.get("snapshot_status"))||number(row.get("snapshot_generated_at"))==null
                    ||number(row.get("snapshot_generated_at"))>now||number(row.get("snapshot_valid_until"))==null
                    ||number(row.get("snapshot_valid_until"))<=now)block="缺少当前可靠实测和有效预测，暂不能核实预测风险";
            // No last-valid fallback or history scan: the dialog and its basis describe this same row.
            var source=object(row.get("result_json"));
            source.put("prediction_id",row.get("latest_prediction_id"));
            source.put("valid_until",row.get("snapshot_valid_until"));
            source.put("status",block==null||block.isEmpty()?row.get("snapshot_status"):"UNKNOWN");
            var latest=decorate(source,List.of(warning(row,true,now)),now);
            if(dto.getTargetId()==null)latest=redact(latest);
            dto.withPrediction(PredictionWorker.ORIGIN,latest,Map.of(),List.of());
        }
        boolean valid=block==null||block.isEmpty();
        return new com.uav.lowaltitude.modules.alarm.api.AlarmDtos.VerificationBasisDto(valid,
                valid?List.of():List.of("RISK_EVIDENCE_UNAVAILABLE"),valid?null:block,now);
    }
    private static Long number(Object value){return value instanceof Number n?n.longValue():null;}

    /** Called only after AlarmReadService has authorized this exact alarm. */
    public List<AlarmDto> enrichList(List<AlarmDto> values) {
        var ids=values.stream().map(v->v.getEventId().value()).filter(Objects::nonNull).distinct().toList();
        if(ids.isEmpty()) return values;
        var grouped=new HashMap<String,List<Map<String,Object>>>();
        for(var row:jdbc.queryForList("SELECT p.*,l.role,l.event_id,e.alarm_id,s.result_json"+BASIS_COLUMNS+" FROM prediction_event_link l"
                +" JOIN prediction_episode p ON p.episode_id=l.episode_id JOIN uav_event e ON e.event_id=l.event_id"
                +BASIS_JOINS
                +" JOIN prediction_snapshot s ON s.prediction_id=p.latest_prediction_id WHERE l.event_id IN (:ids)"
                +" ORDER BY CASE WHEN p.active_key IS NULL THEN 1 ELSE 0 END,p.started_at DESC,p.episode_id",Map.of("ids",ids)))
            grouped.computeIfAbsent((String)row.get("event_id"),k->new ArrayList<>()).add(row);
        for(var dto:values) {
            var rows=caseRows(dto,dto.getEventId().value(),grouped.get(dto.getEventId().value()));
            if(rows==null || rows.isEmpty()) continue;
            var row=rows.get(0);
            var latest=decorate(object(row.get("result_json")),
                    rows.stream().map(r->warning(r,true)).toList());
            if(dto.getTargetId()==null) latest=redact(latest);
            dto.withPrediction("PREDICTED".equals(row.get("role"))?PredictionWorker.ORIGIN:"OBSERVED_AIRSPACE_ENTRY",
                    latest,Map.of(),List.of());
        }
        return values;
    }
    /** Detail retains the frozen first evidence and complete lifecycle history. */
    public AlarmDto enrich(AlarmDto dto,String eventId,boolean targetVisible) {
        if(eventId==null) return dto;
        var rows=jdbc.queryForList("SELECT p.*,l.role,l.event_id,e.alarm_id"+BASIS_COLUMNS+" FROM prediction_event_link l JOIN prediction_episode p ON p.episode_id=l.episode_id"
                +" JOIN uav_event e ON e.event_id=l.event_id"+BASIS_JOINS+" WHERE l.event_id=:id"
                +" ORDER BY CASE WHEN p.active_key IS NULL THEN 1 ELSE 0 END,p.started_at DESC,p.episode_id",Map.of("id",eventId));
        rows=caseRows(dto,eventId,rows);
        if(rows==null || rows.isEmpty()) return dto;
        var row=rows.get(0);
        Map<String,Object> latest=snapshot((String)row.get("latest_prediction_id"));
        latest=decorate(latest,rows.stream().map(r->warning(r,true)).toList());
        Map<String,Object> first=snapshot((String)row.get("first_prediction_id"));
        var detail=object(row.get("frozen_detail"));
        if(detail.get("prediction") instanceof Map<?,?> frozen) {
            first=new LinkedHashMap<>(first); first.put("warning",frozen); first.put("warnings",List.of(frozen));
        }
        Map<String,Object> historyParameters=new HashMap<>();historyParameters.put("id",eventId);
        String historyScope="";
        if(selectedCase(dto)!=null){historyScope=" AND h.episode_id=:episode";historyParameters.put("episode",row.get("episode_id"));}
        var history=jdbc.queryForList("SELECT h.history_id,h.episode_id,h.prediction_id,h.version,h.state,h.reason,h.created_at"
                +" FROM prediction_episode_history h JOIN prediction_event_link l ON l.episode_id=h.episode_id WHERE l.event_id=:id"
                +historyScope+" ORDER BY h.created_at,h.episode_id,h.version",
                historyParameters);
        history.forEach(h->h.put("state",PredictionWorker.publicStatus((String)h.get("state"))));
        if(!targetVisible) { latest=redact(latest);first=redact(first); }
        return dto.withPrediction("PREDICTED".equals(row.get("role"))?PredictionWorker.ORIGIN:"OBSERVED_AIRSPACE_ENTRY",latest,first,history);
    }
    private String selectedCase(AlarmDto dto){
        String selected=riskCases==null?null:riskCases.selectedCase();
        if(selected!=null)return selected;
        return dto.getRiskCase()==null?null:Objects.toString(dto.getRiskCase().get("risk_case_id"),null);
    }
    /** A shared measured event cannot choose another area's first snapshot or lifecycle. */
    private List<Map<String,Object>> caseRows(AlarmDto dto,String eventId,List<Map<String,Object>> rows){
        String selected=selectedCase(dto);
        if(selected==null || rows==null || rows.isEmpty())return rows;
        var cases=jdbc.queryForList("SELECT c.episode_id,c.target_id,c.owner_org_id,c.district_id,c.source_mode,pa.detail AS frozen_detail"
                +" FROM uav_risk_case c JOIN uav_risk_case_member m ON m.risk_case_id=c.risk_case_id AND m.event_id=:event"
                +" LEFT JOIN prediction_event_link pl ON pl.episode_id=c.episode_id AND pl.role='PREDICTED'"
                +" LEFT JOIN uav_event pe ON pe.event_id=pl.event_id LEFT JOIN alarm pa ON pa.alarm_id=pe.alarm_id"
                +" AND pa.target_id=c.target_id AND pa.owner_org_id=c.owner_org_id AND pa.district_id=c.district_id AND pa.source_mode=c.source_mode"
                +" WHERE c.risk_case_id=:case_id AND c.superseded_by IS NULL ORDER BY pl.created_at,pl.event_id",
                Map.of("case_id",selected,"event",eventId));
        if(cases.isEmpty() || cases.get(0).get("episode_id")==null)return List.of();
        var risk=cases.get(0);
        return rows.stream().filter(row->List.of("episode_id","target_id","owner_org_id","district_id","source_mode")
                .stream().allMatch(key->Objects.equals(row.get(key),risk.get(key))))
                .map(row->{var copy=new LinkedHashMap<>(row);copy.put("frozen_detail",risk.get("frozen_detail"));return (Map<String,Object>)copy;}).toList();
    }
    public List<String> exportSummary(String eventId) {
        if(eventId==null) return Arrays.asList(null,null,null);
        return exportSummaries(List.of(eventId)).getOrDefault(eventId,Arrays.asList(null,null,null));
    }
    public Map<String,List<String>> exportSummaries(List<String> eventIds) {
        var ids=eventIds.stream().filter(Objects::nonNull).distinct().toList();
        if(ids.isEmpty()) return Map.of();
        var result=new HashMap<String,List<String>>();
        for(var row:jdbc.queryForList("SELECT l.event_id,p.status,p.valid_until,p.reason,l.role FROM prediction_event_link l"
                +" JOIN prediction_episode p ON p.episode_id=l.episode_id WHERE l.event_id IN (:ids)"
                +" ORDER BY CASE WHEN p.active_key IS NULL THEN 1 ELSE 0 END,p.started_at DESC,p.episode_id",Map.of("ids",ids)))
            result.putIfAbsent((String)row.get("event_id"),exportSummary(row));
        return result;
    }
    private List<String> exportSummary(Map<String,Object> row) {
        String state=(String)row.get("status");
        if("ACTIVE".equals(state) && ((Number)row.get("valid_until")).longValue()<=clock.nowMillis()) state="UNKNOWN";
        String status=switch(state) {case "ACTIVE"->"预测风险持续";case "UNKNOWN"->"预测依据不足";case "CLEARED"->"预测预警已撤销";case "ENTERED"->"已实际进入";default->"稳定性确认中";};
        return List.of("PREDICTED".equals(row.get("role"))?"预测进入禁飞区":"关联实测入区",status,reasonLabel((String)row.get("reason")));
    }
    private static String reasonLabel(String reason) {
        if(reason==null || reason.isBlank()) return "预测依据已更新";
        return Arrays.stream(reason.split(",")).map(String::trim).distinct().map(code->switch(code) {
            case "STABLE_ENTRY_PREDICTION" -> "连续实测支持稳定的入区预测";
            case "NEW_MEASUREMENTS_CONFIRM_AVOIDANCE" -> "新实测确认未来30秒不再入区";
            case "AVOIDANCE_AWAITING_CONFIRMATION" -> "预测航向已避开，等待连续新实测确认";
            case "ACTUAL_ENTRY_OBSERVED" -> "实测已确认进入空域";
            case "ACTUAL_ENTRY_BEFORE_WARNING" -> "预警形成前已实测入区";
            case "TARGET_DOMAIN_CHANGED" -> "目标归属或来源范围已变化";
            case "STALE_MEASUREMENT" -> "最新实测位置已过期";
            case "STALE_OR_UNTRUSTED_MEASUREMENT" -> "实测已过期或时间不可信";
            case "TARGET_IDENTITY_NOT_CURRENT" -> "目标身份已不属于当前有效轨迹";
            case "TURNING" -> "正在转向，预测待稳定";
            case "SPEED_UNSTABLE" -> "速度变化较大，预测待稳定";
            case "FIT_UNRELIABLE" -> "轨迹趋势不够稳定";
            case "DIRECTION_UNRESOLVED" -> "暂不能确定飞行方向";
            case "INSUFFICIENT_POINTS","NO_MEASUREMENTS" -> "有效实测点不足";
            case "INSUFFICIENT_TIME_SPAN" -> "连续观测时间不足";
            case "POSITION_ACCURACY_UNSUITABLE" -> "位置误差不满足预测要求";
            case "DISCONTINUOUS_MEASUREMENTS","POSITION_DISCONTINUITY" -> "实测轨迹不连续";
            case "ALTITUDE_RELATION_UNKNOWN" -> "高度与空域的关系暂不明确";
            case "UNCERTAINTY_OR_BOUNDARY_CONTACT" -> "误差范围跨界或仅接触边界";
            case "AIRSPACE_VERSION_AMBIGUOUS","AIRSPACE_VERSION_CONFLICT" -> "预测时段的空域版本不明确";
            case "PREDICTION_PROCESSING_FAILED" -> "预测处理失败，等待重新计算";
            case "TARGET_OR_SCOPE_UNAVAILABLE" -> "目标或数据范围暂不可用";
            case "STABILITY_PENDING" -> "连续入区预测尚待稳定确认";
            case "ENTRY_AWAITING_CONFIRMATION" -> "预测将进入禁飞区，等待连续新实测确认";
            default -> "预测依据待确认（"+code+"）";
        }).collect(java.util.stream.Collectors.joining("；"));
    }
    private List<Map<String,Object>> warnings(String target) {
        var references=alarmReferences();
        return jdbc.queryForList("SELECT p.*,l.event_id,e.alarm_id"+BASIS_COLUMNS+" FROM prediction_episode p LEFT JOIN prediction_event_link l"
                +" ON l.episode_id=p.episode_id AND l.role='PREDICTED' LEFT JOIN uav_event e ON e.event_id=l.event_id"
                +BASIS_JOINS
                +" JOIN target pt ON pt.target_id=p.target_id AND pt.owner_org_id=p.owner_org_id AND pt.district_id=p.district_id AND pt.source_mode=p.source_mode"
                +" WHERE p.target_id=:id AND p.first_prediction_id IS NOT NULL AND p.active_key IS NOT NULL ORDER BY p.started_at DESC",Map.of("id",target))
                .stream().map(row->warning(row,references.test(row))).toList();
    }
    private java.util.function.Predicate<Map<String,Object>> alarmReferences() {
        try {
            var decision=access.require(PermissionCode.ALARM_READ);
            if(decision.scopeMode()==ScopeMode.ALL) return row->true;
            if(decision.scopeMode()==ScopeMode.NONE) return row->false;
            var grants=new HashSet<List<Object>>();
            for(var scope:jdbc.queryForList("SELECT org_id,district_id FROM app_user_data_scope WHERE user_id=:id",Map.of("id",decision.userId())))
                grants.add(Arrays.asList(scope.get("org_id"),scope.get("district_id")));
            return row->grants.contains(Arrays.asList(row.get("owner_org_id"),row.get("district_id")));
        } catch(ApiException forbidden) { return row->false; }
    }
    private Map<String,Object> warning(Map<String,Object> row,boolean alarmAuthorized) {
        return warning(row,alarmAuthorized,clock.nowMillis());
    }
    private Map<String,Object> warning(Map<String,Object> row,boolean alarmAuthorized,long now) {
        Map<String,Object> w=object(row.get("warning_json"));
        String state=PredictionWorker.publicStatus((String)row.get("status"));
        boolean expired="ACTIVE".equals(state) && ((Number)row.get("valid_until")).longValue()<=now;
        if(expired) state="UNKNOWN";
        w.put("state",state); w.put("reason",expired?"STALE_MEASUREMENT":row.get("reason")); w.put("version",row.get("version"));
        w.put("episode_id",row.get("episode_id"));
        var frozen=object(row.get("frozen_detail"));
        if(frozen.get("prediction") instanceof Map<?,?> first) {
            boolean recovered=false;
            if(w.get("airspace_name")==null || Objects.equals(w.get("airspace_name"),w.get("airspace_id"))) {
                if(first.get("airspace_name")!=null) {w.put("airspace_name",first.get("airspace_name"));recovered=true;}
            }
            if(!(w.get("airspace_version_ids") instanceof List<?> versions) || versions.isEmpty()) {
                if(first.get("airspace_version_ids") instanceof List<?> frozenVersions && !frozenVersions.isEmpty()) {
                    w.put("airspace_version_ids",frozenVersions);w.put("airspace_version_id",frozenVersions.get(0));recovered=true;
                }
            }
            if(recovered) {w.put("airspace_basis","FIRST_FROZEN");w.put("airspace_basis_prediction_id",frozen.get("prediction_id"));}
        }
        for(String key:List.of("last_evidence_prediction_id","last_evidence_generated_at","last_evidence_observed_at"))
            if(row.get(key)!=null) w.put(key,row.get(key));
        if("UNKNOWN".equals(state)) {
            w.put("evidence_status","LAST_KNOWN");w.remove("entry_at");w.remove("entry_location");
            if(!"FIRST_FROZEN".equals(w.get("airspace_basis"))) w.put("airspace_basis","LAST_KNOWN");
        }
        if(alarmAuthorized) {
            w.put("alarm_id",row.get("alarm_id")); w.put("event_id",row.get("event_id"));
        } else { w.remove("alarm_id");w.remove("event_id"); }
        return w;
    }
    private Map<String,Object> snapshot(String id) {
        if(id==null) return Map.of();
        var rows=jdbc.query("SELECT result_json FROM prediction_snapshot WHERE prediction_id=:id",Map.of("id",id),(r,n)->object(r.getString(1)));
        return rows.isEmpty()?Map.of():rows.get(0);
    }
    private Map<String,Object> decorate(Map<String,Object> source,List<Map<String,Object>> warnings) {
        return decorate(source,warnings,clock.nowMillis());
    }
    private Map<String,Object> decorate(Map<String,Object> source,List<Map<String,Object>> warnings,long now) {
        var value=new LinkedHashMap<>(source);
        value.put("warnings",warnings); value.put("warning",warnings.isEmpty()?null:warnings.get(0));
        String invalidation=warnings.stream().map(w->Objects.toString(w.get("reason"),""))
                .filter(r->Set.of("PREDICTION_PROCESSING_FAILED","TARGET_OR_SCOPE_UNAVAILABLE","TARGET_DOMAIN_CHANGED").contains(r)).findFirst().orElse(null);
        boolean expired="READY".equals(value.get("status")) && value.get("valid_until") instanceof Number expiry && expiry.longValue()<=now;
        if(expired || invalidation!=null) {
            value.put("status","UNKNOWN");
            value.put("reason_codes",List.of(invalidation==null?"STALE_MEASUREMENT":invalidation));
        }
        if("UNKNOWN".equals(value.get("status"))) {
            value.put("evidence_status","LAST_KNOWN");value.put("points",List.of());value.remove("trajectory");
            var latest=warnings.stream().filter(w->w.get("last_evidence_observed_at") instanceof Number)
                    .max(Comparator.comparingLong(w->((Number)w.get("last_evidence_observed_at")).longValue())).orElse(null);
            if(latest!=null) for(String key:List.of("last_evidence_prediction_id","last_evidence_generated_at","last_evidence_observed_at"))
                if(latest.get(key)!=null) value.put(key,latest.get(key));
            if(value.get("based_on_observed_at")==null && value.get("last_evidence_observed_at")!=null)
                value.put("based_on_observed_at",value.get("last_evidence_observed_at"));
        }
        return value;
    }
    private Map<String,Object> redact(Map<String,Object> raw) {
        var result=new LinkedHashMap<>(raw);
        for(String key:List.of("target_id","target_no","anchor","points","trajectory","heading_deg","speed_mps")) result.remove(key);
        if(result.get("warnings") instanceof List<?> ws) {
            var safe=new ArrayList<Map<String,Object>>();
            for(Object w:ws) if(w instanceof Map<?,?> map) {
                var copy=new LinkedHashMap<String,Object>();map.forEach((k,v)->copy.put(k.toString(),v));copy.remove("entry_location");safe.add(copy);
            }
            result.put("warnings",safe); result.put("warning",safe.isEmpty()?null:safe.get(0));
        }
        return result;
    }
    private Map<String,Object> object(Object value) {
        // H2 returns JSON columns as UTF-8 bytes; PostgreSQL returns a JSON wrapper whose
        // string value is the document. Decode before parsing, never stringify byte[].
        String raw=value instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):Objects.toString(value,"{}");
        try {
            var node=json.readTree(raw);
            if(node.isTextual()) node=json.readTree(node.asText());
            if(!node.isObject()) throw new IllegalArgumentException("Prediction evidence must be a JSON object");
            return json.convertValue(node,new TypeReference<LinkedHashMap<String,Object>>(){});
        }
        catch(Exception e) {throw new IllegalStateException("Invalid prediction evidence JSON",e);}
    }
    private static String scope(AccessDecision d) {
        String sql=" AND EXISTS(SELECT 1 FROM app_org o WHERE o.org_id=t.owner_org_id AND o.enabled=TRUE)"
                +" AND EXISTS(SELECT 1 FROM app_district d WHERE d.district_id=t.district_id AND d.enabled=TRUE)";
        if(d.scopeMode()==ScopeMode.NONE) return sql+" AND 1=0";
        if(d.scopeMode()==ScopeMode.ASSIGNED) sql+=" AND EXISTS(SELECT 1 FROM app_user_data_scope ds WHERE ds.user_id=:user AND ds.org_id=t.owner_org_id AND ds.district_id=t.district_id)";
        return sql;
    }
    private static int integer(MultiValueMap<String,String> q,String key,int fallback) {
        if(!q.containsKey(key)) return fallback;
        try {if(q.get(key).size()!=1) throw invalid();return Integer.parseInt(q.getFirst(key));}catch(RuntimeException e){throw invalid();}
    }
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","预测查询参数无效");}
}
