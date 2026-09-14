package com.uav.lowaltitude.modules.disposal.application;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.disposal.infrastructure.DisposalRepository;
import com.uav.lowaltitude.modules.handoff.domain.DisposalCompletionPort;

/** 处置域对交接域暴露的唯一事实：某主体有没有已完成的授权（决策 13-6）。只读，不带范围裁剪—— */
/* 前提是不是成立与"谁在看"无关：换个人来看不该让同一个事件忽然变得可以处罚。 */
@Component
public class DisposalCompletionAdapter implements DisposalCompletionPort {
    private final DisposalRepository repository;

    public DisposalCompletionAdapter(DisposalRepository repository) { this.repository = repository; }

    @Override
    public boolean completedExists(String subjectKind, String subjectId) {
        return repository.completedExists(subjectKind, subjectId);
    }
}
