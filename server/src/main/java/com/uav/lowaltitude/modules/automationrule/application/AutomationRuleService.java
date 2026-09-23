package com.uav.lowaltitude.modules.automationrule.application;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.automationrule.api.AutomationRuleDtos.*;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuleRepository;
import com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuleRepository.Head;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/** Persistent configuration. Deliberately has no dependency on device or notification dispatch. */
@Service
public class AutomationRuleService {
    private final AutomationRuleRepository repo;
    private final AccessService access;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final ObjectMapper json;
    private final AutomationRuntimePolicy runtime;
    private final com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository runs;

    public AutomationRuleService(AutomationRuleRepository repo,
            AccessService access, IdempotencyGuard idempotency, AuditService audit, AppClock clock, ObjectMapper json,
            AutomationRuntimePolicy runtime,com.uav.lowaltitude.modules.automationrule.infrastructure.AutomationRuntimeRepository runs) {
        this.repo=repo; this.access=access; this.idempotency=idempotency;
        this.audit=audit; this.clock=clock; this.json=json;
        this.runtime=runtime;this.runs=runs;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Group get(String category) { read(); return view(requireHead(category,false)); }

    @Transactional(readOnly=true)
    public Page<Change> history(String category, int page, int size) {
        read(); requireHead(category,false);
        if(page<1 || size<1 || size>100 || (long)(page-1)*size>Integer.MAX_VALUE) throw bad("分页参数无效");
        var items=repo.history(category,page,size).stream().map(r ->
                new Change(r.id(),r.version(),r.action(),r.actor(),r.at(),strings(r.details()))).toList();
        return new Page<>(items,page,size,repo.historyCount(category));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page<AutomationRuntimeModel.Run> runs(String category,int page,int size){
        read();requireHead(category,false);
        if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE)throw bad("分页参数无效");
        var items=runs.runs(category,page,size).stream().map(r->new AutomationRuntimeModel.Run(r.id(),r.eventId(),r.targetId(),r.version(),
            r.status(),r.reason(),r.at(),r.observed(),r.mode(),conditions(r.conditions()),runs.actionsForRun(r.id()).stream()
            .map(a->new AutomationRuntimeModel.Action(a.code(),a.status(),a.reason(),a.reference())).toList())).toList();
        return new Page<>(items,page,size,runs.runCount(category));
    }
    private List<AutomationRuntimeModel.Condition> conditions(String raw){
        try{return Arrays.asList(json.readValue(raw,AutomationRuntimeModel.Condition[].class));}
        catch(JsonProcessingException invalid){throw new IllegalStateException("运行记录格式无效",invalid);}
    }

    @Transactional
    public Group saveRule(String category, String id, RuleInput input, String key) {
        write(); Head head=requireHead(category,true);
        claim(key,"rule:"+category+":"+id,input); requireVersion(head,input.expectedVersion());
        Snapshot before=snapshot(head);
        Rule old=id==null ? null : requireRule(before.rules(),id);
        CatalogItem item=item(category,input.itemCode());
        String name=input.name().trim(), value=validateValue(item,input.value().trim());
        if(name.isEmpty()) throw bad("规则名称不能为空");
        if(input.holdSeconds()>0 && !item.supportsHold()) throw bad("此判定项不支持持续满足时间");
        if(before.rules().stream().anyMatch(r -> !Objects.equals(r.ruleId(),id) && r.itemCode().equals(item.code())))
            throw conflict("RULE_ITEM_EXISTS","此判定项已配置，请编辑已有规则");
        if(before.rules().stream().anyMatch(r -> !Objects.equals(r.ruleId(),id) && r.name().equals(name)))
            throw conflict("RULE_NAME_EXISTS","规则名称已存在");
        Rule saved=new Rule(id==null ? UUID.randomUUID().toString() : id,name,item.code(),value,
                input.holdSeconds(),input.enabled(),clock.nowMillis(),AuthContext.require().account());
        List<String> details=ruleDiff(old,saved,item);
        if(details.isEmpty()) return view(head);
        if(old==null) repo.insert(category,saved,saved.updatedAt());
        else if(repo.update(category,saved)!=1) throw missing();
        return commit(head,before,(old==null?"新建":"修改")+"「"+name+"」",details);
    }

    @Transactional
    public Group enabled(String category, String id, EnabledInput input, String key) {
        write(); Head head=requireHead(category,true);
        claim(key,"enabled:"+category+":"+id,input); requireVersion(head,input.expectedVersion());
        Snapshot before=snapshot(head); Rule old=requireRule(before.rules(),id);
        if(old.enabled()==input.enabled()) return view(head);
        Rule updated=new Rule(old.ruleId(),old.name(),old.itemCode(),old.value(),old.holdSeconds(),
                input.enabled(),clock.nowMillis(),AuthContext.require().account());
        if(repo.update(category,updated)!=1) throw missing();
        return commit(head,before,(input.enabled()?"启用":"停用")+"「"+old.name()+"」",
                List.of("配置状态："+state(old.enabled())+" → "+state(input.enabled())));
    }

    @Transactional
    public Group settings(String category, SettingsInput input, String key) {
        write(); Head head=requireHead(category,true);
        claim(key,"settings:"+category,input); requireVersion(head,input.expectedVersion());
        validateSettings(category,input);
        Snapshot before=snapshot(head);
        repo.settings(category,input,encode(input.actions()));
        Settings after=settings(requireHead(category,false));
        List<String> details=settingsDiff(before.settings(),after);
        if(details.isEmpty()) return view(head);
        return commit(head,before,"调整本类规则生效设置",details);
    }

    private Group commit(Head head, Snapshot before, String action, List<String> details) {
        long now=clock.nowMillis();
        if(repo.advance(head.category(),head.version(),now)!=1) throw conflict("VERSION_CONFLICT","规则配置已被其他人更新，请刷新后操作");
        Head after=requireHead(head.category(),false); var actor=AuthContext.require();
        repo.history(UUID.randomUUID().toString(),head.category(),after.version(),action,actor.userId(),actor.account(),
                now,encode(details),encode(before),encode(snapshot(after)));
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"responsePlans","automation_rule_changed",
                "automation_rule_group",head.category(),action+"; version="+after.version(),"SUCCESS","","");
        return view(after);
    }
    private Group view(Head head) {
        var health=runtime.health();
        return new Group(head.category(),head.version(),settings(head),repo.rules(head.category()),
            AutomationRuleCatalog.GROUPS.get(head.category()),health.status(),health.message(),
            access.permissionCodes(AuthContext.require().roleCode()).contains("responsePlans.auth"));
    }
    private Settings settings(Head head) {
        var scopes=repo.scopes(head.category());
        return new Settings(head.scope(),scopes.stream().map(s->s.id()).toList(),scopes.stream().map(s->s.name()).toList(),
                head.schedule(),head.start(),head.end(),"Asia/Shanghai",head.waitSeconds(),strings(head.actions()));
    }
    private Snapshot snapshot(Head head) { return new Snapshot(settings(head),repo.rules(head.category())); }
    private Head requireHead(String category, boolean lock) {
        if(!AutomationRuleCatalog.GROUPS.containsKey(category)) throw missing();
        Head head=repo.head(category,lock); if(head==null) throw missing(); return head;
    }
    private CatalogItem item(String category,String code) {
        return AutomationRuleCatalog.GROUPS.get(category).stream().filter(i->i.code().equals(code)).findFirst()
                .orElseThrow(()->bad("判定项不属于当前规则类型"));
    }
    private Rule requireRule(List<Rule> rules,String id) {
        return rules.stream().filter(r->r.ruleId().equals(id)).findFirst().orElseThrow(AutomationRuleService::missing);
    }
    private String validateValue(CatalogItem item,String value) {
        if("NUMBER".equals(item.kind())) {
            try {
                if(!value.matches("[0-9]{1,6}")) throw new NumberFormatException();
                int number=Integer.parseInt(value);
                if(number<item.minValue() || number>item.maxValue()) throw new NumberFormatException();
                return Integer.toString(number);
            } catch(NumberFormatException e) { throw bad(item.label()+"须为 "+item.minValue()+"～"+item.maxValue()+" 之间的整数（"+item.unit()+"）"); }
        }
        if("FIXED".equals(item.kind()) && !Objects.equals(item.fixedValue(),value)) throw bad("固定条件不可修改");
        if("SELECT".equals(item.kind()) && !item.options().contains(value)) throw bad("条件值不在可选范围内");
        return value;
    }
    private void validateSettings(String category,SettingsInput b) {
        if(!Set.of("ALL","AIRSPACES").contains(b.scopeMode()) || !Set.of("ALL_DAY","DAILY").contains(b.scheduleMode())) throw bad("范围或时间模式无效");
        if(!"Asia/Shanghai".equals(b.timezone())) throw bad("规则生效时间使用北京时间");
        try {
            if(!b.startTime().matches("\\d{2}:\\d{2}") || !b.endTime().matches("\\d{2}:\\d{2}")) throw new DateTimeParseException("format","",0);
            LocalTime.parse(b.startTime()); LocalTime.parse(b.endTime());
        } catch(DateTimeParseException ex) { throw bad("请填写有效的开始和结束时间"); }
        if("DAILY".equals(b.scheduleMode()) && b.startTime().equals(b.endTime())) throw bad("开始和结束时间不能相同，全天生效请选择全天");
        if(!Set.of(0,5,15,30).contains(b.insufficientWaitSeconds())) throw bad("补充数据等待时间须为0、5、15或30秒");
        if(new HashSet<>(b.airspaceIds()).size()!=b.airspaceIds().size()) throw bad("适用空域不能重复");
        if("ALL".equals(b.scopeMode()) && !b.airspaceIds().isEmpty()) throw bad("全部范围不能同时指定空域");
        if("AIRSPACES".equals(b.scopeMode()) && b.airspaceIds().isEmpty()) throw bad("请至少选择一个适用空域");
        for(String id:b.airspaceIds()) if(!repo.airspaceExists(id)) throw bad("适用空域不存在，请重新选择");
        if(new HashSet<>(b.actions()).size()!=b.actions().size()) throw bad("执行动作不能重复");
        if(!b.actions().isEmpty()) throw bad("核实、反制和通知处罚的执行动作由系统固定，不能另行选择");
    }
    private List<String> ruleDiff(Rule old,Rule saved,CatalogItem item) {
        var details=new ArrayList<String>();
        if(old==null) return List.of("判定项："+item.label(),"条件："+saved.value()+item.unit(),"持续满足："+hold(saved.holdSeconds()),"状态："+state(saved.enabled()));
        if(!old.name().equals(saved.name())) details.add("名称："+old.name()+" → "+saved.name());
        if(!old.itemCode().equals(saved.itemCode())) details.add("判定项："+old.itemCode()+" → "+item.label());
        if(!old.value().equals(saved.value())) details.add("条件："+old.value()+" → "+saved.value()+item.unit());
        if(old.holdSeconds()!=saved.holdSeconds()) details.add("持续满足："+hold(old.holdSeconds())+" → "+hold(saved.holdSeconds()));
        if(old.enabled()!=saved.enabled()) details.add("状态："+state(old.enabled())+" → "+state(saved.enabled()));
        return details;
    }
    private List<String> settingsDiff(Settings a,Settings b) {
        var details=new ArrayList<String>();
        if(!a.scopeMode().equals(b.scopeMode()) || !a.airspaceIds().equals(b.airspaceIds())) details.add("适用范围："+scopeText(a)+" → "+scopeText(b));
        if(!a.scheduleMode().equals(b.scheduleMode()) || !a.startTime().equals(b.startTime()) || !a.endTime().equals(b.endTime())) details.add("生效时间："+timeText(a)+" → "+timeText(b));
        if(a.insufficientWaitSeconds()!=b.insufficientWaitSeconds()) details.add("补充数据等待："+a.insufficientWaitSeconds()+"秒 → "+b.insufficientWaitSeconds()+"秒");
        if(!a.actions().equals(b.actions())) details.add("执行动作："+actionText(a.actions())+" → "+actionText(b.actions()));
        return details;
    }
    private String scopeText(Settings s) { return "ALL".equals(s.scopeMode())?"全部监测区域":String.join("、",s.airspaceNames()); }
    private String timeText(Settings s) { return "ALL_DAY".equals(s.scheduleMode())?"全天":s.startTime()+"—"+s.endTime()+(s.endTime().compareTo(s.startTime())<0?"（次日）":"")+" 北京时间"; }
    private String actionText(List<String> values) {
        var labels=Map.of("notify","通知上级","evidence","汇集证据","track","持续跟踪","pilot","飞手提醒（短信及电话录音）");
        return values.isEmpty()?"未配置":String.join("、",values.stream().map(v->labels.getOrDefault(v,v)).toList());
    }
    private static String hold(int seconds) { return seconds==0?"即时判断":"连续"+seconds+"秒"; }
    private static String state(boolean enabled) { return enabled?"启用":"停用"; }
    private void read() {
        access.requireBusinessData("responsePlans.read");
        if(!"ALL".equals(AuthContext.require().scopeMode())) throw new ApiException(HttpStatus.FORBIDDEN,"GLOBAL_RULE_SCOPE_REQUIRED","此规则为全局配置，需要全部数据范围；原处置预案仍按既有范围访问");
    }
    private void write() { read(); access.requireBusinessData("responsePlans.auth"); }
    private void requireVersion(Head head,long expected) { if(head.version()!=expected) throw conflict("VERSION_CONFLICT","规则配置已更新，请刷新后操作"); }
    private void claim(String key,String operation,Object body) { idempotency.claim(key,"automation-rule:"+operation+":"+encode(body)); }
    private String encode(Object value) { try {return json.writeValueAsString(value);} catch(JsonProcessingException e) {throw new IllegalStateException(e);} }
    private List<String> strings(String value) { try {return List.of(json.readValue(value,String[].class));} catch(JsonProcessingException e) {throw new IllegalStateException("规则配置数据格式无效",e);} }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"AUTOMATION_RULE_NOT_FOUND","规则类型或规则不存在"); }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message); }
    private static ApiException conflict(String code,String message) { return new ApiException(HttpStatus.CONFLICT,code,message); }
}
