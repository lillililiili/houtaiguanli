package com.uav.lowaltitude.modules.alarm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository.Facts;
import com.uav.lowaltitude.modules.alarm.infrastructure.AutoSmsRepository.Evaluation;
import org.junit.jupiter.api.Test;

class UavCounterEvidenceTest {
    private final long now=1000000L;
    private final Facts facts=new Facts(now,"UAV",now,null);
    private Evaluation evaluation(String alarm,String legal,Long at) {
        return new Evaluation("evaluation",alarm,legal,"FRESH","[]",at,at);
    }
    @Test void currentSystemEvidenceNeedsNoManualObservation() {
        assertThat(reason(facts,evaluation("alarm","ILLEGAL",now),true,true,30)).isEmpty();
    }
    @Test void absentStaleFutureOrNonUavObservationsBlock() {
        for(Facts current:new Facts[]{new Facts(now,"UAV",null,null),new Facts(now,"UAV",now-31000,null),
                new Facts(now,"UAV",now+1,null),new Facts(now,"UNKNOWN",now,null)})
            assertThat(reason(current,evaluation("alarm","ILLEGAL",now),true,true,30)).isNotEmpty();
    }
    @Test void legalUnknownUnlinkedOrExpiredEvaluationBlocks() {
        for(Evaluation current:new Evaluation[]{null,evaluation("alarm","LEGAL",now),evaluation("alarm","UNKNOWN",now),
                evaluation("other","ILLEGAL",now),evaluation("alarm","ILLEGAL",now-31000),evaluation("alarm","ILLEGAL",now+1)})
            assertThat(reason(facts,current,true,true,30)).isNotEmpty();
    }
    @Test void missingAssuranceUnknownReasonsOrMissingThresholdBlocks() {
        assertThat(reason(facts,evaluation("alarm","ILLEGAL",now),false,true,30)).isNotEmpty();
        assertThat(reason(facts,evaluation("alarm","ILLEGAL",now),true,false,30)).isNotEmpty();
        assertThat(reason(facts,evaluation("alarm","ILLEGAL",now),true,true,null)).isNotEmpty();
    }
    @Test void pendingEventStillRequiresExistingAuthorizationPrerequisite() {
        assertThat(UavAdvisoryRules.counterBlockReason("PENDING_VERIFICATION","alarm",facts,
                evaluation("alarm","ILLEGAL",now),true,true,30,now)).isEqualTo("事件尚未核实属实");
    }
    @Test void pendingEventWithMissingEvidenceSaysTheEvidenceIsInsufficientFirst() {
        // ZT-18：证据本身不够时，先说证据不足；只说“先核实”会让人以为核实了就能反制。
        String noObservation=UavAdvisoryRules.counterBlockReason("PENDING_VERIFICATION","alarm",new Facts(now,"UNKNOWN",now,null),
                evaluation("alarm","ILLEGAL",now),true,true,30,now);
        assertThat(noObservation).startsWith("证据不足").contains("缺少当前有效无人机观测","尚未核实");
        String noEvaluation=UavAdvisoryRules.counterBlockReason("PENDING_VERIFICATION","alarm",facts,null,true,true,30,now);
        assertThat(noEvaluation).startsWith("证据不足").contains("违规研判","尚未核实");
        assertThat(UavAdvisoryRules.counterBlockReason("PENDING_VERIFICATION",null,facts,null,true,true,30,now)).startsWith("证据不足");
    }
    @Test void confirmedEventMessagesAreUnchanged() {
        assertThat(reason(new Facts(now,"UNKNOWN",now,null),evaluation("alarm","ILLEGAL",now),true,true,30))
                .isEqualTo("缺少当前有效无人机观测，不能确认反制依据");
        assertThat(reason(facts,null,true,true,30)).isEqualTo("缺少关联本事件且证据充分的当前违规研判，暂不能申请或执行反制");
        assertThat(reason(facts,evaluation("alarm","ILLEGAL",now),true,true,null)).isEqualTo("缺少有效的目标观测时效配置，不能确认当前反制依据");
    }
    private String reason(Facts f,Evaluation e,boolean sufficient,boolean noUnknowns,Integer seconds) {
        return UavAdvisoryRules.counterBlockReason("CONFIRMED","alarm",f,e,sufficient,noUnknowns,seconds,now);
    }
}
