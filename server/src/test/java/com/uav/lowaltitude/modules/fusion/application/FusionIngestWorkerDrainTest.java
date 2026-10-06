package com.uav.lowaltitude.modules.fusion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.uav.lowaltitude.modules.fusion.application.FusionPipeline.FrameOutcome;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * ZT-06：有积压时一次调度连续领取多批，不再"处理一批就睡 poll-millis"；领空、批不满或预算用完就让出调度线程。
 * 预算为 0 保持旧行为（每次调度一批）。一帧失败只置这一帧 FAILED，不打断后面的帧。
 */
class FusionIngestWorkerDrainTest {
    private final FusionInboxRepository inbox = mock(FusionInboxRepository.class);
    private final FusionPipeline pipeline = mock(FusionPipeline.class);
    private final FusionProperties properties = new FusionProperties();
    private final AppClock clock = new AppClock(Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC));
    private final FusionIngestWorker worker = new FusionIngestWorker(inbox, pipeline, properties, clock, mock(PlatformTransactionManager.class));

    private static List<InboxRow> rows(String prefix, int count) {
        List<InboxRow> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(new InboxRow(prefix + i, "lingyun:radar:R", prefix + i, "source", 0, "{}"));
        return out;
    }

    @Test
    void backlogIsDrainedBatchAfterBatchWithinOnePoll() {
        properties.setBatchSize(2);
        when(inbox.claim(anyLong(), eq(2), anyLong(), anyInt())).thenReturn(rows("a", 2), rows("b", 2), rows("c", 1), rows("d", 2));
        when(pipeline.processFrame(org.mockito.ArgumentMatchers.any())).thenReturn(new FrameOutcome(1, 1, List.of("t")));
        worker.poll();
        // 两批满、第三批不满（已领空）就停：第四批留给下一次调度。
        verify(inbox, times(3)).claim(anyLong(), eq(2), anyLong(), anyInt());
        verify(inbox, times(5)).done(anyString(), anyLong());
    }

    @Test
    void zeroBudgetKeepsTheOldOneBatchPerPoll() {
        properties.setBatchSize(2);
        properties.setDrainBudgetMillis(0);
        when(inbox.claim(anyLong(), eq(2), anyLong(), anyInt())).thenReturn(rows("a", 2), rows("b", 2));
        when(pipeline.processFrame(org.mockito.ArgumentMatchers.any())).thenReturn(new FrameOutcome(1, 1, List.of("t")));
        worker.poll();
        verify(inbox, times(1)).claim(anyLong(), eq(2), anyLong(), anyInt());
    }

    @Test
    void aFailedFrameIsMarkedFailedAndTheBatchGoesOn() {
        properties.setBatchSize(2);
        when(inbox.claim(anyLong(), eq(2), anyLong(), anyInt())).thenReturn(rows("a", 2), List.of());
        InboxRow[] seen = new InboxRow[1];
        when(pipeline.processFrame(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            InboxRow row = call.getArgument(0);
            if (row.inboxId().equals("a0")) throw new IllegalStateException("bad frame");
            seen[0] = row;
            return new FrameOutcome(1, 1, List.of("t"));
        });
        worker.poll();
        verify(inbox).fail(eq("a0"), anyLong(), eq("bad frame"));
        verify(inbox, never()).done(eq("a0"), anyLong());
        verify(inbox).done(eq("a1"), anyLong());
        assertThat(seen[0].inboxId()).isEqualTo("a1");
        // 第一批是满的，所以接着领了一次，领空就停。
        verify(inbox, times(2)).claim(anyLong(), eq(2), anyLong(), anyInt());
    }
}
