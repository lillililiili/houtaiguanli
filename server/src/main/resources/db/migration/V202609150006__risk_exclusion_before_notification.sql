ALTER TABLE flight_risk_verification DROP CONSTRAINT ck_stage4_risk_verification_transition;

ALTER TABLE flight_risk_verification ADD CONSTRAINT ck_stage4_risk_verification_transition CHECK (
    (previous_state = 'PENDING_VERIFICATION'
        AND ((conclusion = 'CONFIRMED' AND resulting_state = 'PENDING_NOTIFICATION')
          OR (conclusion = 'EXCLUDED' AND resulting_state = 'EXCLUDED')))
    OR
    (previous_state = 'PENDING_NOTIFICATION'
        AND conclusion = 'EXCLUDED' AND resulting_state = 'EXCLUDED')
);
