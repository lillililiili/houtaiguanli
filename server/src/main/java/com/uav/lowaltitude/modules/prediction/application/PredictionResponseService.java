package com.uav.lowaltitude.modules.prediction.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.uav.lowaltitude.modules.disposal.application.EmergencyStopService;

/** The lifecycle worker calls this in the episode update transaction, not from a read request. */
@Service
public class PredictionResponseService {
    private final PredictionEventGate gate;
    private final EmergencyStopService stops;

    public PredictionResponseService(PredictionEventGate gate, EmergencyStopService stops) {
        this.gate=gate;
        this.stops=stops;
    }

    @Transactional
    public void pause(String eventId,String reason,long revision) {
        gate.lockEpisode(eventId);
        gate.markPaused(eventId,revision);
        // Existing provider receipts remain consumable. New claims and provider dispatches are
        // blocked by the lifecycle gate; accepting a late receipt never advances to another action.
        // A linked actual alarm may carry a separately verified, independently authorized response.
        // Only the original predictive subject's authorizations depend on this episode's lifecycle.
        if(gate.isPrediction(eventId))stops.stopForPrediction(eventId,reason,revision);
    }
}
