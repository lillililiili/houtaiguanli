package com.uav.lowaltitude.modules.assessment.application;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy.MergeInput;
import com.uav.lowaltitude.modules.alarm.application.AlarmMergePolicy.MergeOutcome;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityReviewRepository.ReviewRow;
import com.uav.lowaltitude.modules.assessment.infrastructure.LegalityStatusProjection;
import com.uav.lowaltitude.platform.time.AppClock;

/**
 * 人工转告警：把一条已锁定的研判交给 C06 的人工升级路径（{@link AlarmMergePolicy#escalateManually}）。
 * 这里只负责把研判事实翻译成 MergeInput；鉴权、幂等、版本与历史仍由 {@link LegalityReviewService} 在同一事务内完成。
 * 等级由人工合并策略按有效非法结论与违规原因计算，其余沿用 grade；不改写原研判，也不依赖 C06 参数。
 * 告警 occurred_at 取研判 as_of（业务时刻），received_at 与合并时刻取时钟 now（操作时刻）：观测时刻不能冒充接收时刻。
 * 操作人与说明随行传入：并入同一目标的现有告警时，升级记录要写明谁、为什么转告警。
 */
@Service
public class AlarmEscalationService {
    private final AlarmMergePolicy policy;
    private final ObjectMapper json;
    private final AppClock clock;

    public AlarmEscalationService(AlarmMergePolicy policy, ObjectMapper json, AppClock clock) { this.policy = policy; this.json = json; this.clock = clock; }

    @Transactional
    public MergeOutcome escalate(ReviewRow review, String actorId, String note) {
        MergeInput input = new MergeInput(review.evaluationId(), review.targetId(), review.ownerOrgId(), review.districtId(), review.sourceMode(),
                review.ruleSetId(), review.ruleSetVersionId(),
                LegalityStatusProjection.effective(review.legalStatus(), review.mode(), review.reviewState(), review.manualStatus()),
                review.planMatchCode(), review.grade(), review.score(),
                violations(review.violationReasons()), review.asOf(), clock.now().atOffset(ZoneOffset.UTC));
        return policy.escalateManually(input, actorId, note);
    }

    private List<String> violations(String text) {
        List<String> output = new ArrayList<>();
        if (text == null) return output;
        try {
            JsonNode root = json.readTree(text);
            if (root.isTextual()) root = json.readTree(root.textValue());
            for (JsonNode item : root) if (item.isTextual()) output.add(item.textValue());
        } catch (Exception ignored) {
            // 违规原因只是告警明细的摘要；解析失败不应阻断人工升级，告警仍以研判 ID 为准回溯。
        }
        return output;
    }
}
