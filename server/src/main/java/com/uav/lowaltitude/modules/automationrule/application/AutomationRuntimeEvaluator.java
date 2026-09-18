package com.uav.lowaltitude.modules.automationrule.application;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.uav.lowaltitude.modules.automationrule.api.AutomationRuleDtos.Rule;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuleRepository.Head;
import com.uav.lowaltitude.modules.automationrule.application.AutomationRuntimeModel.*;

/** Pure condition evaluation: observations, not scheduler ticks, advance sustained conditions. */
public final class AutomationRuntimeEvaluator {
    public Decision evaluate(Head group,List<Rule> all,Set<String> scope,AutomationRuntimeFacts facts,long now,
            long maxAge,Map<String,Hold> previous,Long unknownSince) {
        var enabled=all.stream().filter(Rule::enabled).toList();
        if(enabled.isEmpty())return result("PAUSED","未启用规则，此类自动流程暂停");
        LocalTime time=Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalTime();
        if("DAILY".equals(group.schedule())) {
            LocalTime start=LocalTime.parse(group.start()),end=LocalTime.parse(group.end());
            boolean inside=start.isBefore(end)?!time.isBefore(start)&&time.isBefore(end):!time.isBefore(start)||time.isBefore(end);
            if(start.equals(end)||!inside)return result("OUT_OF_SCHEDULE","当前不在本类规则生效时段内");
        }
        if(facts==null)return result("REVIEW","事件或同范围目标事实不可用，需处理异常");
        if(!"UAV".equals(facts.objectType()))return result("REVIEW","目标未明确识别为无人机");
        if("FALSE_POSITIVE".equals(facts.eventState()))return result("NOT_MATCHED","事件已排除");
        if(!fresh(facts.observedAt(),now,maxAge))return result("REVIEW","当前观测缺失、过期或时间异常，不能自动通过");
        if("AIRSPACES".equals(group.scope())) {
            if(!facts.airspaceKnown())return unknown(group,now,unknownSince,"无法确认当前位置与指定空域的关系");
            if(Collections.disjoint(scope,facts.airspaceIds()))return result("OUT_OF_SCOPE","目标不在指定的有效空域内");
        }
        var checks=new ArrayList<Condition>();var holds=new LinkedHashMap<String,Hold>();
        boolean failed=false,unknown=false,holding=false;
        for(Rule rule:enabled) {
            var fact=facts.facts().get(rule.itemCode());
            String actual=fact==null?null:fact.value();
            String unavailable=fact==null?"缺少当前判定依据":fact.unavailableReason();
            if(actual==null||!fresh(fact.observedAt(),now,maxAge)) {
                unknown=true;checks.add(new Condition(rule.name(),"UNKNOWN",display(actual),expected(rule),unavailable(unavailable)));continue;
            }
            boolean pass=matches(group.category(),rule,actual);
            String state=pass?"PASS":"FAIL",reason=pass?"条件满足":"未达到配置要求";
            if(!pass)failed=true;
            if(pass&&rule.holdSeconds()>0) {
                Hold old=previous.get(rule.ruleId());long observed=fact.observedAt();
                long since=old!=null&&observed>=old.observedAt()&&observed-old.observedAt()<=maxAge?old.since():observed;
                holds.put(rule.ruleId(),new Hold(since,observed));
                if(observed-since<rule.holdSeconds()*1000L) {holding=true;state="WAITING";reason="等待连续有效观测满足 "+rule.holdSeconds()+" 秒";}
            }
            checks.add(new Condition(rule.name(),state,display(actual),expected(rule),reason));
        }
        if(failed)return new Decision("NOT_MATCHED","至少一条已启用规则未满足",checks,holds,null);
        if(unknown) {
            long since=unknownSince==null?now:unknownSince;
            return new Decision(now-since>=group.waitSeconds()*1000L?"REVIEW":"WAITING",
                now-since>=group.waitSeconds()*1000L?"补充数据等待已结束，仍缺少判定依据，请处理异常":"等待补充缺少的判定依据",checks,holds,since);
        }
        if(holding)return new Decision("WAITING","条件已满足，等待持续观测时长达到要求",checks,holds,null);
        return new Decision("PASS","全部已启用规则满足",checks,holds,null);
    }
    private static String expected(Rule r) {
        var item=AutomationRuleCatalog.GROUPS.values().stream().flatMap(List::stream).filter(c->c.code().equals(r.itemCode())).findFirst().orElse(null);
        return (item!=null&&"NUMBER".equals(item.kind())?item.operator()+" "+r.value()+" "+item.unit():r.value())+(r.holdSeconds()>0?"；连续 "+r.holdSeconds()+" 秒":"");
    }
    private static String display(String value){return value==null?null:switch(value){case "true"->"是";case "false"->"否";case "HIGH"->"高风险";case "MEDIUM"->"中风险";case "LOW"->"低风险";default->value;};}
    private static String unavailable(String code){
        if(code==null)return "依据缺失或已过期";
        return switch(code){
            case "AUTHORIZED_DEVICE_CURRENT_HEALTH_UNKNOWN"->"执行设备当前健康状态未知";
            case "CURRENT_AIRSPACE_RELATION_UNKNOWN"->"当前位置与空域的关系未知";
            case "LATEST_OBSERVATION_MISSING_STALE_OR_FUTURE"->"最新观测缺失、过期或时间异常";
            case "NO_CURRENT_CONTIGUOUS_RAW_OBSERVATIONS_WITH_TRACK_CONFIG"->"缺少连续有效的原始轨迹观测";
            case "NO_CURRENT_TRACEABLE_SOURCE_OBSERVATION"->"缺少当前可追溯的原始观测";
            case "NO_PERSISTED_CURRENT_RECEIPT_CHANNEL_READINESS_EVIDENCE"->"缺少回执通道当前可用的依据";
            case "REQUIRES_AUTHORIZED_EXECUTION_DEVICE_AND_CURRENT_DISPATCH_CHECK","UNIQUE_CURRENT_COUNTER_AUTHORIZATION_REQUIRED"->"缺少唯一、有效的反制授权及对应执行设备";
            case "TWO_INDEPENDENT_IDENTITY_SOURCES_REQUIRED"->"需至少两个独立来源的有效身份信息";
            case "TARGET_NOT_EXPLICIT_UAV"->"目标尚未明确识别为无人机";
            default->"当前条件缺少可用判定依据";
        };
    }
    private static boolean matches(String category,Rule rule,String actual) {
        var item=AutomationRuleCatalog.GROUPS.get(category).stream().filter(c->c.code().equals(rule.itemCode())).findFirst().orElse(null);
        if(item==null)return false;
        if("FIXED".equals(item.kind()))return "true".equals(actual);
        if("SELECT".equals(item.kind()))return "HIGH".equals(actual)||("中风险或高风险".equals(rule.value())&&"MEDIUM".equals(actual));
        try {int comparison=new BigDecimal(actual).compareTo(new BigDecimal(rule.value()));return "不超过".equals(item.operator())?comparison<=0:comparison>=0;}
        catch(NumberFormatException invalid){return false;}
    }
    public static boolean fresh(Long at,long now,long window) {return at!=null&&at<=now&&now-at<=window;}
    private static Decision result(String state,String reason){return new Decision(state,reason,List.of(),Map.of(),null);}
    private static Decision unknown(Head group,long now,Long previous,String reason){long since=previous==null?now:previous;return new Decision(now-since>=group.waitSeconds()*1000L?"REVIEW":"WAITING",reason,List.of(),Map.of(),since);}
}
