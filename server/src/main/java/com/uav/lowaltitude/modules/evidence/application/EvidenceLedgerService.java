package com.uav.lowaltitude.modules.evidence.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.*;
import com.uav.lowaltitude.modules.evidence.api.EvidenceLedgerDtos.*;
import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.*;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceLedgerRepository;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceLedgerRepository.*;
import com.uav.lowaltitude.modules.evidence.infrastructure.EvidenceRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.device.application.DeviceAccessPolicy;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.export.CsvExport;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.time.AppClock;

/** Unified material index. Actual commands and measured tracks remain distinct from uploaded files. */
@Service
public class EvidenceLedgerService {
    private static final List<String> CATEGORIES=List.of("VIDEO","TRACK","IMAGE","COMMAND");
    private static final Set<String> FILTERS=Set.of("category","status","custody","subject_kind","subject_id","q");
    private final EvidenceLedgerRepository ledger;
    private final EvidenceRepository subjects;
    private final EvidenceAssociationService files;
    private final AccessControlService access;
    private final DeviceRepository devices;
    private final DeviceAccessPolicy deviceAccess;
    private final AppClock clock;
    private final AuditService audit;
    private final ObjectMapper json;
    public EvidenceLedgerService(EvidenceLedgerRepository ledger,EvidenceRepository subjects,EvidenceAssociationService files,
            AccessControlService access,DeviceRepository devices,DeviceAccessPolicy deviceAccess,AppClock clock,AuditService audit,ObjectMapper json) {
        this.ledger=ledger;this.subjects=subjects;this.files=files;this.access=access;this.devices=devices;this.deviceAccess=deviceAccess;
        this.clock=clock;this.audit=audit;this.json=json;
    }
    @Transactional(readOnly=true)
    public PageDto<Entry> list(MultiValueMap<String,String> params) {
        AccessDecision decision=access.require(PermissionCode.EVIDENCE_READ);
        Query query=query(params,true,false);int page=positive(params,"page",1,1000000),size=positive(params,"size",20,100);
        if(!visible(query,decision))return new PageDto<>(List.of(),page,size,0);
        Relation relation=relation(query,decision);
        return new PageDto<>(entries(relation,(page-1)*size,size),page,size,ledger.count(relation));
    }
    @Transactional(readOnly=true)
    public Stats stats(MultiValueMap<String,String> params) {
        AccessDecision decision=access.require(PermissionCode.EVIDENCE_READ);
        Query query=query(params,false,false);
        if(!visible(query,decision))return new Stats(0,counts(Map.of()),List.of(),List.of());
        Relation relation=relation(query,decision);
        return new Stats(ledger.count(relation),counts(ledger.counts(relation,"category")),
                entries(ledger.counts(relation,"status")),entries(ledger.counts(relation,"custody")));
    }
    @Transactional
    public Detail detail(String kind,String id,MultiValueMap<String,String> params) {
        AccessDecision decision=access.require(PermissionCode.EVIDENCE_READ);
        if(!Set.of("FILE","TRACK","COMMAND").contains(kind))throw invalid();
        identifier(id);Query context=query(params,false,true);
        if(!visible(context,decision))throw missing();
        Query query=new Query(null,null,null,context.subjectKind(),context.subjectId(),null,kind,id);
        List<Entry> found=entries(relation(query,decision),0,1);
        if(found.isEmpty())throw missing();
        Entry entry=found.get(0);List<LinkDto> links=new ArrayList<>();
        Command command=null;List<Entry> attachments=List.of();
        if(kind.equals("FILE"))links=files.get(id).links();
        if(kind.equals("TRACK")) {
            for(Map<String,Object> row:ledger.trackLinks(id)) {
                String subjectKind=string(row,"subject_kind"),subjectId=string(row,"subject_id");
                if(canSeeLink(subjectKind)&&subjects.subjectVisible(subjectKind,subjectId,decision))
                    links.add(new LinkDto(null,subjectKind,subjectId,subjects.findSubject(subjectKind,subjectId).no()));
            }
        }
        if(kind.equals("COMMAND")) {
            Map<String,Object> row=devices.findCommand(id);if(row==null)throw missing();
            for(SubjectLink link:ledger.commandLinks(id)) {
                if(!canSeeLink(link.kind()))continue;
                if(!subjects.subjectVisible(link.kind(),link.id(),decision))continue;
                var subject=subjects.findSubject(link.kind(),link.id());
                links.add(new LinkDto(null,link.kind(),link.id(),subject.no()));
            }
            List<Receipt> receipts=new ArrayList<>();
            for(Map<String,Object> receipt:devices.commandReceipts(id)) {
                Object payload=receipt.get("payload");
                com.fasterxml.jackson.databind.JsonNode parsed=null;
                try{parsed=payload==null?null:json.readTree(payload instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):payload.toString());}
                catch(java.io.IOException e){throw new ApiException(HttpStatus.CONFLICT,"INVALID_RECEIPT_PAYLOAD","历史回执内容无法解析");}
                receipts.add(new Receipt(string(receipt,"receipt_id"),string(receipt,"receipt_kind"),string(receipt,"device_result_code"),number(receipt,"occurred_at"),number(receipt,"received_at"),parsed));
            }
            command=new Command(id,string(row,"command_no"),string(row,"device_name"),string(row,"device_no"),string(row,"command_type"),string(row,"reason"),string(row,"status"),number(row,"created_at"),number(row,"issued_at"),number(row,"completed_at"),string(row,"result_detail"),receipts);
            Relation attached=relation(new Query(null,null,null,"COMMAND",id,null,"FILE",null),decision);
            if(ledger.count(attached)>1000)throw new ApiException(HttpStatus.CONFLICT,"TOO_MANY_ATTACHMENTS","附件过多，请从台账分页查看");
            attachments=entries(attached,0,1000);
        }
        return new Detail(entry,links,command,attachments);
    }
    @Transactional(readOnly=true)
    public ChainDto materials(String kind,String id,MultiValueMap<String,String> params) {
        AccessDecision decision=access.require(PermissionCode.EVIDENCE_READ);
        if(!params.isEmpty() || !EvidenceAssociationService.SUBJECTS.contains(kind))throw invalid();
        identifier(id);Query query=new Query(null,null,null,kind,id,null,null,null);
        if(!visible(query,decision))throw missing();
        Relation relation=relation(query,decision);List<Entry> items=new ArrayList<>();
        for(String category:CATEGORIES) items.addAll(entries(relation(new Query(category,null,null,kind,id,null,null,null),decision),0,100));
        items.sort(java.util.Comparator.comparing(Entry::occurredAt,java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())).thenComparing(Entry::sourceKind).thenComparing(Entry::sourceId));
        Map<String,Long> totals=ledger.counts(relation,"category");Map<String,CoverageDto> coverage=new LinkedHashMap<>();
        for(String category:CATEGORIES) {
            long count=totals.getOrDefault(category,0L);
            long returned=items.stream().filter(e->e.category().equals(category)).count();
            int broken=(int)items.stream().filter(e->e.category().equals(category)&&unavailable(e)).count();
            coverage.put(category,new CoverageDto(count>0?"PRESENT":(category.equals("TRACK")&&!probe(PermissionCode.TARGET_READ)||category.equals("COMMAND")&&!canReadCommands())?"FORBIDDEN":"ABSENT",
                    (int)Math.min(Integer.MAX_VALUE,count),broken,count>returned));
        }
        // Membership metadata is not a cryptographic verification of file bytes; no invented integrity verdict.
        List<Entry> chronological=new ArrayList<>(items);java.util.Collections.reverse(chronological);
        List<RecordDto> records=chronological.stream().map(e->new RecordDto(e.category(),e.sourceId(),e.capturedAt()==null?e.storedAt():e.capturedAt(),null,unavailable(e)?"UNAVAILABLE":"AVAILABLE",e)).toList();
        var subject=subjects.findSubject(kind,id);
        return new ChainDto(kind,id,subject.no(),kind.equals("TARGET")&&probe(PermissionCode.TARGET_READ)?id:null,null,coverage,null,records,null);
    }
    @Transactional
    public ResponseEntity<byte[]> export(MultiValueMap<String,String> params,String ip,String agent) {
        AccessDecision decision=access.require(PermissionCode.EVIDENCE_READ);Query query=query(params,true,false);
        positive(params,"page",1,1000000);positive(params,"size",20,100);
        List<Entry> items=List.of();
        if(visible(query,decision)) {
            Relation relation=relation(query,decision);
            if(ledger.count(relation)>CsvExport.MAX_ROWS)throw new ApiException(HttpStatus.BAD_REQUEST,"EXPORT_TOO_LARGE","导出超过5000条，请缩小筛选范围");
            items=entries(relation,0,CsvExport.MAX_ROWS);
        }
        var actor=AuthContext.require();
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"evidence","evidence_exported","evidence_ledger",null,"导出材料台账 "+items.size()+" 条","SUCCESS",ip,agent);
        List<List<String>> rows=items.stream().map(EvidenceLedgerLabels::row).toList();
        return CsvExport.response(CsvExport.fileName("evidence-ledger",clock.now()),EvidenceLedgerLabels.HEADERS,rows);
    }
    private static boolean unavailable(Entry e){return Set.of("PENDING","MISSING","CORRUPT","DESTROYED","NO_POINTS").contains(e.status());}
    private static String string(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:value.toString();}
    private static Long number(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:((Number)value).longValue();}
    private List<Entry> entries(Relation relation,int offset,int size){return ledger.list(relation,offset,size).stream().map(r->new Entry(r.sourceKind(),r.sourceId(),r.category(),r.evidenceNo(),r.originalName(),r.kindCode(),r.status(),r.capturedAt(),r.storedAt(),r.sourceMode(),r.layer(),r.startedAt(),r.endedAt(),r.sizeBytes(),r.held(),r.custody(),r.linkCount(),r.retainUntil(),r.pointCount())).toList();}
    private Relation relation(Query query,AccessDecision decision){return ledger.relation(query,decision,probe(PermissionCode.EVIDENCE_INGEST),probe(PermissionCode.TARGET_READ),canReadCommands(),clock.now().toEpochMilli());}
    private boolean visible(Query q,AccessDecision decision) {
        if(q.subjectKind()==null)return true;
        if(Set.of("DEVICE","COMMAND","COMMISSION").contains(q.subjectKind())&&!canReadCommands())return false;
        if(q.subjectKind().equals("CASE")&&!probe(PermissionCode.PUNISHMENT_READ))return false;
        if(q.subjectKind().equals("AUTHORIZATION")&&!probe(PermissionCode.DISPOSAL_READ))return false;
        return q.subjectId()==null||subjects.subjectVisible(q.subjectKind(),q.subjectId(),decision);
    }
    private boolean canSeeLink(String kind) {
        return switch(kind) {
            case "TARGET" -> probe(PermissionCode.TARGET_READ);
            case "EVENT" -> probe(PermissionCode.ALARM_READ);
            case "PLAN" -> probe(PermissionCode.FLIGHT_READ);
            case "CASE" -> probe(PermissionCode.PUNISHMENT_READ);
            case "AUTHORIZATION" -> probe(PermissionCode.DISPOSAL_READ);
            case "DEVICE", "COMMAND", "COMMISSION" -> canReadCommands();
            default -> false;
        };
    }
    private boolean canReadCommands() {
        try { deviceAccess.requireMonitoringRead(); return true; }
        catch(ApiException e) { if(e.getStatus()==HttpStatus.FORBIDDEN)return false;throw e; }
    }
    private boolean probe(PermissionCode permission) {
        try{access.require(permission);return true;}catch(ApiException e){if(e.getStatus()==HttpStatus.FORBIDDEN)return false;throw e;}
    }
    private static Query query(MultiValueMap<String,String> p,boolean paged,boolean context) {
        for(String key:p.keySet())if(p.get(key).size()!=1||!(context?Set.of("subject_kind","subject_id").contains(key):FILTERS.contains(key)||paged&&Set.of("page","size").contains(key)))throw invalid();
        String category=choice(p,"category",Set.copyOf(CATEGORIES));
        String status=text(p,"status");if(status!=null&&!Set.of("PENDING","AVAILABLE","MISSING","CORRUPT","DESTROYED","OBSERVED","NO_POINTS","QUEUED","SENT","ACCEPTED","SUCCEEDED","FAILED","TIMED_OUT","CANCELLED").contains(status))throw invalid();
        String custody=choice(p,"custody",Set.of("KEPT","NEARING","DUE","HELD"));
        String kind=choice(p,"subject_kind",EvidenceAssociationService.SUBJECTS),id=text(p,"subject_id");
        if(id!=null&&kind==null||context&&((kind==null)!=(id==null)))throw invalid();
        if(id!=null)identifier(id);String q=text(p,"q");if(q!=null&&q.length()>200)throw invalid();
        return new Query(category,status,custody,kind,id,q,null,null);
    }
    private static String text(MultiValueMap<String,String> p,String key){String value=p.getFirst(key);return value==null||value.isBlank()?null:value.trim();}
    private static String choice(MultiValueMap<String,String> p,String key,Set<String> allowed){String value=text(p,key);if(value!=null&&!allowed.contains(value))throw invalid();return value;}
    private static int positive(MultiValueMap<String,String> p,String key,int fallback,int max){try{String s=text(p,key);int n=s==null?fallback:Integer.parseInt(s);if(n<1||n>max)throw invalid();return n;}catch(NumberFormatException e){throw invalid();}}
    private static void identifier(String id){if(id==null||id.isBlank()||id.length()>128)throw invalid();}
    private static List<CountDto> counts(Map<String,Long> values){return CATEGORIES.stream().map(k->new CountDto(k,values.getOrDefault(k,0L))).toList();}
    private static List<CountDto> entries(Map<String,Long> values){return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->new CountDto(e.getKey(),e.getValue())).toList();}
    private static ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","筛选参数无效");}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","材料不存在或不可见");}
}
