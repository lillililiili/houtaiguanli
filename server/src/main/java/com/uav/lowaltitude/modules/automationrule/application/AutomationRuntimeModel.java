package com.uav.lowaltitude.modules.automationrule.application;

import java.util.List;
import java.util.Map;

public final class AutomationRuntimeModel {
    private AutomationRuntimeModel() { }
    public record Condition(String name,String result,String actual,String expected,String reason) { }
    public record Hold(long since,long observedAt) { }
    public record Decision(String status,String reason,List<Condition> conditions,Map<String,Hold> holds,Long unknownSince) { }
    public record Action(String code,String status,String reason,String reference) { }
    public record Run(String runId,String eventId,String targetId,long groupVersion,String status,String reason,
            long evaluatedAt,Long observedAt,String sourceMode,List<Condition> conditions,List<Action> actions) { }
    public record Health(String status,String message) { }
    public record ActionResult(String status,String reason,String reference,String receipt) { }
}
