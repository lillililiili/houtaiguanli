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
                evaluation("alarm","ILLEGAL",now),true,true,30,now)).isNotEmpty();
    }
    private String reason(Facts f,Evaluation e,boolean sufficient,boolean noUnknowns,Integer seconds) {
        return UavAdvisoryRules.counterBlockReason("CONFIRMED","alarm",f,e,sufficient,noUnknowns,seconds,now);
    }
}
