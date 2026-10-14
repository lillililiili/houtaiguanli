package com.uav.lowaltitude.modules.device.application;

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
    private final EoDispatchService dispatch;
    public EoTrackingScheduler(EoTrackingRepository repository,EoEdgeRepository edges,EoTrackingPolicy policy,
            EoEdgeCommandService commands,AppClock clock,@Value("${app.eo-edge.auto-track-batch:20}") int batch,EoDispatchService dispatch) {
        this.repository=repository;this.edges=edges;this.policy=policy;this.commands=commands;this.clock=clock;this.batch=batch;
        this.dispatch=dispatch;
    }
    @Transactional
    public int poll() {
        if(edges.lockCursor()==null) return 0;
        int count=0;
        // Finishing an existing stop does not enable new automatic tracking, or undo a target pause.
        for (String task : repository.endingTasks()) {
            if (count >= batch) break;
            if (commands.retryStop(task)) count++;
        }
        for(var task:repository.openTasks()) {
            if(count>=batch) break;
            String target=text(task,"target_id");
            if(!repository.tryLock(target)) continue;
            var snapshot=repository.snapshot(target);
            boolean automatic="AUTO".equals(text(task,"origin"));
            if(automatic && !policy.enabledFor(snapshot)) continue;
            var device=edges.binding(text(task,"ops_device_id"),true);
            if(device==null) continue;
            // Manual requests need the same continuity maintenance even when automatic starts are disabled.
            // A cleared automatic demand (or auto pause) must not cancel a valid manual observation.
            String stopReason=automatic && repository.paused(target)?"OPERATOR_PAUSE_AUTO_TRACK":policy.continuationBlock(snapshot,task,device);
            if(stopReason==null && (!automatic || !policy.demand(target).isEmpty() || dispatch.protectedObservation(target))) continue;
            commands.enqueue(device,EoEdgeCommandService.END,EoEdgeCommandService.TOPIC_END,
                    stopReason==null?"AUTO_OBSERVATION_DEMAND_CLEARED":"AUTO_TRACK_STOP:"+stopReason);
            count++;
        }
        return count+dispatch.poll(Math.max(0,batch-count));
    }
}
