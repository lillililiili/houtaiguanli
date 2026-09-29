package com.uav.lowaltitude.modules.device.application;

import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceRepository;
import com.uav.lowaltitude.modules.target.application.TargetReadService;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.security.AuthContext;

@Service
public class QaVideoStreamService {
    private final VideoStreamRegistry streams;
    private final DeviceAccessPolicy access;
    private final EoEdgeRepository edges;
    private final DeviceRepository devices;
    private final TargetReadService targets;
    private final AuditService audit;
    public QaVideoStreamService(VideoStreamRegistry streams, DeviceAccessPolicy access, EoEdgeRepository edges,
            DeviceRepository devices, TargetReadService targets, AuditService audit) {
        this.streams=streams; this.access=access; this.edges=edges; this.devices=devices; this.targets=targets; this.audit=audit;
    }
    public synchronized VideoStreamRegistry.Stream register(String taskId, String deviceId, String requestedTarget) {
        streams.requireEnabled();
        Map<String,Object> task = authorize(taskId);
        String targetId = text(task,"target_id");
        var current = edges.latestTaskByTarget(targetId);
        var command = devices.findCommand(text(task,"begin_command_id"));
        if (!"OPEN".equals(text(task,"status")) || !Objects.equals(text(task,"ops_device_id"),deviceId)
                || (requestedTarget != null && !Objects.equals(targetId, requestedTarget))
                || current == null || !Objects.equals(taskId,text(current,"task_id"))
                || command == null || !Objects.equals(deviceId,text(command,"device_id"))
                || !"EO_BEGIN_TRACK".equals(text(command,"command_type"))
                || !java.util.Set.of("QUEUED","SENT","ACCEPTED","SUCCEEDED").contains(text(command,"status")))
            throw conflict();
        var binding=edges.binding(deviceId,false);
        String targetMode=targets.target(targetId).sourceMode();
        if (binding==null || !binding.enabled() || !("mock".equals(targetMode)||"replay".equals(targetMode))
                || !TargetVideoService.sameSourceDomain(targetMode,binding.sourceMode())
                || !Objects.equals(binding.sourceMode(),text(command,"source_mode"))) throw conflict();
        String mode=binding.sourceMode();
        return streams.register(taskId,targetId,deviceId,mode, () -> {
            var user=AuthContext.require();
            audit.recordStandalone(user.userId(),user.account(),"qa_video_register","eo_tracking_task",taskId,
                    "登记独立模拟器测试视频，来源="+mode,null);
        });
    }
    public synchronized void remove(String taskId) {
        streams.requireEnabled(); authorize(taskId);
        if(streams.find(taskId)!=null) {
            var user=AuthContext.require();
            audit.recordStandalone(user.userId(),user.account(),"qa_video_unregister","eo_tracking_task",taskId,
                    "注销独立模拟器测试视频",null);
            streams.remove(taskId);
        }
    }
    private Map<String,Object> authorize(String taskId) {
        var user=access.requireDevicesOperate();
        var task=edges.task(taskId);
        if(task==null)throw new ApiException(HttpStatus.NOT_FOUND,"EO_TASK_NOT_FOUND","跟踪任务不存在");
        targets.target(text(task,"target_id"));
        if(!devices.canDeleteInScope(text(task,"ops_device_id"),user.userId(),user.scopeMode()))
            throw new ApiException(HttpStatus.FORBIDDEN,"DEVICE_SCOPE_FORBIDDEN","当前账号无权访问关联光电设备");
        return task;
    }
    private static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"VIDEO_TASK_MISMATCH","仅允许为当前模拟设备的有效跟踪任务登记视频");}
    private static String text(Map<String,Object> row,String key){Object value=row.get(key);return value==null?null:value.toString();}
}
