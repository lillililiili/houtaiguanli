package com.uav.lowaltitude.modules.alarm.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import com.uav.lowaltitude.modules.alarm.api.AdvisoryVoiceRecordingDtos.CatalogDto;
import com.uav.lowaltitude.modules.alarm.api.AdvisoryVoiceRecordingDtos.CurrentDto;
import com.uav.lowaltitude.modules.alarm.api.AdvisoryVoiceRecordingDtos.RecordingDto;
import com.uav.lowaltitude.modules.alarm.application.AdvisoryVoiceRecording.Resolution;
import com.uav.lowaltitude.modules.alarm.infrastructure.AdvisoryVoiceRecordingRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AdvisoryVoiceRecordingRepository.Row;
import com.uav.lowaltitude.modules.alarm.infrastructure.AdvisoryVoiceRecordingRepository.Setting;
import com.uav.lowaltitude.modules.identity.application.AccessService;
import com.uav.lowaltitude.modules.identity.application.IdempotencyGuard;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.storage.ObjectStoragePort;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 后台“接口配置 → 电话通知录音”：上传、试听、选用、停止使用和删除未用过的录音。
 * 查看沿用接口配置读权限；管理与切换通知通道相同，要求接口配置操作、通知配置授权和全部数据范围（录音对全部告警生效）。
 * 只决定播放哪段录音，不触发、不重拨电话；拨打仍由自动电话任务按原有条件执行。
 */
@Service
public class AdvisoryVoiceRecordingService {
    private static final Logger log=LoggerFactory.getLogger(AdvisoryVoiceRecordingService.class);
    private static final String STORAGE_PREFIX="advisory-voice-recordings/";
    private static final List<String> FORMATS=List.of("WAV");
    private final AdvisoryVoiceRecordingRepository repository;
    private final AdvisoryVoiceRecording recordings;
    private final AutoVoicePolicy policy;
    private final ObjectStoragePort storage;
    private final AccessService access;
    private final IdempotencyGuard idempotency;
    private final AuditService audit;
    private final AppClock clock;
    private final TransactionTemplate tx;
    public AdvisoryVoiceRecordingService(AdvisoryVoiceRecordingRepository repository,AdvisoryVoiceRecording recordings,AutoVoicePolicy policy,
            ObjectStoragePort storage,AccessService access,IdempotencyGuard idempotency,AuditService audit,AppClock clock,PlatformTransactionManager manager) {
        this.repository=repository;this.recordings=recordings;this.policy=policy;this.storage=storage;this.access=access;
        this.idempotency=idempotency;this.audit=audit;this.clock=clock;this.tx=new TransactionTemplate(manager);
    }
    public CatalogDto catalog() {requireRead();return catalogInternal();}
    /** 只收完整可读的 WAV：电话通道（模拟外呼与本机模拟）都按 WAV 校验，其他格式会让电话这一步无法执行。 */
    public RecordingDto upload(MultipartFile file,String name,String transcript,String key,String ip,String userAgent) {
        AuthUser actor=requireManage();
        String title=text(name,"录音名称",120),words=text(transcript,"录音里说的话",1000);
        if(file==null||file.isEmpty())throw invalid("请选择要上传的录音文件");
        if(file.getSize()>AdvisoryVoiceRecording.MAX_BYTES)throw tooLarge();
        byte[] data;
        try(InputStream in=file.getInputStream()) {data=in.readNBytes((int)AdvisoryVoiceRecording.MAX_BYTES+1);}
        catch(IOException unreadable) {throw invalid("录音文件读取失败，请重新选择后上传");}
        if(data.length>AdvisoryVoiceRecording.MAX_BYTES)throw tooLarge();
        var wave=AdvisoryVoiceRecording.wave(data);
        if(wave==null)throw new ApiException(HttpStatus.BAD_REQUEST,"VOICE_RECORDING_INVALID","只能上传完整的 WAV 录音（电话通道目前只能播放 WAV），这个文件不是完整可播放的 WAV 音频");
        String sha=AdvisoryVoiceRecording.sha256(data),original=originalName(file.getOriginalFilename()),id=UUID.randomUUID().toString();
        long now=clock.nowMillis();
        String objectKey=STORAGE_PREFIX+Instant.ofEpochMilli(now).toString().substring(0,10)+"/"+id+"/recording.wav";
        AtomicBoolean stored=new AtomicBoolean();
        try {
            return tx.execute(status->{
                idempotency.claim(key,"advisory-voice-recording-upload:"+sha+":"+title+":"+words);
                String existing=repository.findNameBySha256(sha);
                if(existing!=null)throw duplicate(existing);
                var object=storage.putNew(objectKey,new ByteArrayInputStream(data));stored.set(true);
                if(object.sizeBytes()!=data.length||!sha.equals(object.sha256()))throw new IllegalStateException("Stored recording differs from upload");
                Row row=new Row(id,title,words,original,"audio/wav",objectKey,data.length,sha,wave.durationMillis(),wave.sampleRate(),wave.channels(),actor.userId(),actor.name(),now,false);
                try {repository.insert(row);}
                catch(DuplicateKeyException concurrent) {throw duplicate(title);}
                audit(actor,"advisory_voice_recording_uploaded",id,"name="+title+"; sha256="+sha+"; size_bytes="+data.length+"; duration_ms="+wave.durationMillis(),ip,userAgent);
                return dto(row,null);
            });
        } catch(RuntimeException failed) {
            if(stored.get())safeDelete(objectKey);
            throw failed;
        }
    }
    /** 选用或停止使用；按全局版本做条件更新，防止两人同时切换时互相覆盖。 */
    public CatalogDto setActive(String id,boolean active,int expectedVersion,String key,String ip,String userAgent) {
        AuthUser actor=requireManage();String recordingId=id(id);
        tx.executeWithoutResult(status->{
            idempotency.claim(key,"advisory-voice-recording-active:"+recordingId+":"+active+":"+expectedVersion);
            Setting setting=repository.setting(true);
            if(setting.version()!=expectedVersion)throw versionConflict();
            Row row=find(recordingId);
            if(active) {
                if(recordingId.equals(setting.activeRecordingId()))throw conflict("VOICE_RECORDING_ALREADY_ACTIVE","这段录音已经在使用中");
                if(recordings.verified(row)==null)throw conflict("VOICE_RECORDING_FILE_UNAVAILABLE","这段录音的文件缺失或已损坏，不能选用；请重新上传");
            } else if(!recordingId.equals(setting.activeRecordingId()))throw conflict("VOICE_RECORDING_NOT_ACTIVE","这段录音当前没有在使用");
            if(repository.updateActive(active?recordingId:null,actor.userId(),clock.nowMillis(),expectedVersion)!=1)throw versionConflict();
            String previous=active&&setting.activeRecordingId()!=null?"; previous_recording_id="+setting.activeRecordingId():"";
            audit(actor,active?"advisory_voice_recording_activated":"advisory_voice_recording_deactivated",recordingId,"name="+row.name()+"; sha256="+row.sha256()+previous,ip,userAgent);
        });
        return catalogInternal();
    }
    /** 只删没在使用、也没用于过电话通知的录音；用过的留着备查。 */
    public CatalogDto delete(String id,String key,String ip,String userAgent) {
        AuthUser actor=requireManage();String recordingId=id(id);
        String objectKey=tx.execute(status->{
            idempotency.claim(key,"advisory-voice-recording-delete:"+recordingId);
            Setting setting=repository.setting(true);
            Row row=find(recordingId);
            if(recordingId.equals(setting.activeRecordingId()))throw conflict("VOICE_RECORDING_IN_USE","正在使用的录音不能删除；请先改选其他录音或停止使用");
            if(row.used())throw conflict("VOICE_RECORDING_USED","这段录音已经用于电话通知，要留着备查，不能删除");
            if(repository.delete(recordingId)!=1)throw notFound();
            audit(actor,"advisory_voice_recording_deleted",recordingId,"name="+row.name()+"; sha256="+row.sha256(),ip,userAgent);
            return row.objectKey();
        });
        safeDelete(objectKey);
        return catalogInternal();
    }
    /** 试听用原始字节；与入库哈希不一致时不返回内容。 */
    public Content content(String id) {
        requireRead();Row row=find(id(id));
        byte[] data=recordings.content(row);
        if(data==null)throw conflict("VOICE_RECORDING_FILE_UNAVAILABLE","录音文件缺失或已损坏，无法播放");
        return new Content(row.originalName(),row.contentType(),data);
    }
    private CatalogDto catalogInternal() {
        Setting setting=repository.setting(false);
        List<RecordingDto> items=repository.list().stream().map(row->dto(row,setting.activeRecordingId())).toList();
        var startup=recordings.startup();
        return new CatalogDto(items,setting.activeRecordingId(),setting.version(),current(recordings.resolve()),startup==null?null:startup.name(),
                policy.enabled(),canManage(),AdvisoryVoiceRecording.MAX_BYTES,FORMATS);
    }
    private static CurrentDto current(Resolution resolution) {
        return switch(resolution.source()) {
            case UPLOADED -> resolution.recording()!=null
                    ? new CurrentDto("UPLOADED",true,resolution.uploadedId(),resolution.uploadedName(),"电话通知播放后台选用的录音“"+resolution.uploadedName()+"”。")
                    : new CurrentDto("UPLOADED",false,resolution.uploadedId(),resolution.uploadedName(),
                            "已选用的录音“"+resolution.uploadedName()+"”文件缺失或已损坏，电话通知暂时不能拨打，也不会改播其他录音。请重新上传或改选录音。");
            case STARTUP_CONFIG -> new CurrentDto("STARTUP_CONFIG",true,resolution.recording().id(),resolution.recording().name(),
                    "电话通知播放服务器启动参数配置的录音“"+resolution.recording().name()+"”。在下面选用上传的录音后，改为播放选用的录音。");
            case NONE -> new CurrentDto("NONE",false,null,null,"没有可播放的录音：告警核实属实、短信送达后，系统不会拨打飞手电话，电话这一步直接跳过。上传并选用录音后才会拨打。");
        };
    }
    private static RecordingDto dto(Row row,String activeId) {
        boolean active=row.recordingId().equals(activeId);
        return new RecordingDto(row.recordingId(),row.name(),row.transcript(),row.originalName(),row.contentType(),row.sizeBytes(),row.sha256(),
                row.durationMillis(),row.sampleRate(),row.channels(),row.uploadedByName(),row.uploadedAt(),active,row.used(),!active&&!row.used());
    }
    private AuthUser requireRead() {access.requireBusinessData("interfaces.read");return AuthContext.require();}
    private AuthUser requireManage() {
        access.requireBusinessData("interfaces.op");access.require("notificationSettings.auth");
        AuthUser actor=AuthContext.require();
        if(!"ALL".equals(actor.scopeMode()))throw new ApiException(HttpStatus.FORBIDDEN,"VOICE_RECORDING_SCOPE_REQUIRED","电话通知录音对全部告警生效，需要全部数据范围才能管理");
        return actor;
    }
    private boolean canManage() {
        try {requireManage();return true;}
        catch(ApiException denied) {if(denied.getStatus()==HttpStatus.FORBIDDEN)return false;throw denied;}
    }
    private Row find(String id) {Row row=repository.find(id);if(row==null)throw notFound();return row;}
    private void audit(AuthUser actor,String action,String id,String detail,String ip,String userAgent) {
        String agent=userAgent==null?"":userAgent.length()>512?userAgent.substring(0,512):userAgent;
        audit.record(actor.userId(),actor.account(),actor.roleCode(),"interfaces",action,"advisory_voice_recording",id,detail,"SUCCESS",ip==null?"":ip,agent);
    }
    private void safeDelete(String objectKey) {
        try {storage.deleteIfPresent(objectKey);}
        catch(RuntimeException ex) {log.warn("advisory voice recording file could not be removed: {}",objectKey,ex);}
    }
    private static String originalName(String value) {
        String name=value==null?"":value.replaceAll(".*[/\\\\]","").trim();
        if(name.isEmpty())return "recording.wav";
        return name.length()>256?name.substring(name.length()-256):name;
    }
    private static String text(String value,String label,int max) {
        String text=value==null?"":value.trim();
        if(text.isEmpty()||text.length()>max)throw invalid(label+"不能为空，最多 "+max+" 个字");
        return text;
    }
    private static String id(String value) {
        String id=value==null?"":value.trim();
        if(id.isEmpty()||id.length()>36)throw notFound();
        return id;
    }
    private static ApiException invalid(String message) {return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);}
    private static ApiException tooLarge() {return new ApiException(HttpStatus.BAD_REQUEST,"FILE_TOO_LARGE","录音文件不能超过 10 MiB");}
    private static ApiException duplicate(String name) {return new ApiException(HttpStatus.CONFLICT,"VOICE_RECORDING_DUPLICATE","这段录音已经上传过（名称："+name+"），不用重复上传");}
    private static ApiException notFound() {return new ApiException(HttpStatus.NOT_FOUND,"VOICE_RECORDING_NOT_FOUND","录音不存在或已删除");}
    private static ApiException conflict(String code,String message) {return new ApiException(HttpStatus.CONFLICT,code,message);}
    private static ApiException versionConflict() {return conflict("VERSION_CONFLICT","录音设置已被其他人修改，请刷新后再操作");}
    public record Content(String filename,String contentType,byte[] bytes) { }
}
