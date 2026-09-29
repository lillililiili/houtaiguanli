package com.uav.lowaltitude.modules.device.application;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.device.infrastructure.EoTrackingRepository;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.platform.time.AppClock;
import static com.uav.lowaltitude.modules.device.application.EoTrackingPolicy.text;

@Service
public class EoTrackingScheduler {
    private final EoTrackingRepository repository;
    private final EoEdgeRepository edges;
    private final EoTrackingPolicy policy;
    private final EoEdgeCommandService commands;
    private final AppClock clock;
    private final int batch;
    public EoTrackingScheduler(EoTrackingRepository repository,EoEdgeRepository edges,EoTrackingPolicy policy,
            EoEdgeCommandService commands,AppClock clock,@Value("${app.eo-edge.auto-track-batch:20}") int batch) {
        this.repository=repository;this.edges=edges;this.policy=policy;this.commands=commands;this.clock=clock;this.batch=batch;
    }
    @Transactional
    public int poll() {
        if(!policy.enabled()) return 0;
        if(edges.lockCursor()==null) return 0;
        int count=0;
        for(var task:repository.openAutomaticTasks(batch)) {
            String target=text(task,"target_id");repository.lock(target);
            if(!repository.paused(target) && policy.block(repository.snapshot(target))==null && !policy.demand(target).isEmpty()) continue;
            var device=edges.binding(text(task,"ops_device_id"),true);
            if(device==null) continue;
            commands.enqueue(device,EoEdgeCommandService.END,EoEdgeCommandService.TOPIC_END,"AUTO_OBSERVATION_CLEARED");
            count++;
        }
        for(String target:repository.candidates(policy.cutoff(),clock.nowMillis(),Math.max(batch*10,batch))) {
            if(count>=batch) break;
            repository.lock(target);
            var snapshot=repository.snapshot(target);
            if(repository.paused(target)||edges.targetHasOpenTask(target)||policy.block(snapshot)!=null||policy.demand(target).isEmpty()) continue;
            var device=edges.idleDeviceForMode(text(snapshot,"owner_org_id"),text(snapshot,"district_id"),null,EoTrackingPolicy.mode(snapshot),policy.heartbeatCutoff(),policy.now());
            if(device==null) continue;
            device=edges.binding(device.opsDeviceId(),true);
            if(!policy.deviceReady(device)||edges.openTask(device.opsDeviceId())!=null) continue;
            var latest=edges.latestTaskByTarget(target);
            if(latest!=null && "FAILED".equals(text(latest,"status"))) continue;
            String task=UUID.randomUUID().toString();
            commands.enqueueBegin(device,task,target,null,"UNREPORTED_KINEMATICS_AND_SIZE_USE_PROTOCOL_DEFAULTS",policy.bootstrap(snapshot),null,"BUSINESS_OBSERVATION_AUTO_TRACK");
            repository.automatic(task);count++;
        }
        return count;
    }
}
