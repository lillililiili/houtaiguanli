package com.uav.lowaltitude.modules.disposal.infrastructure;

/** Alias a is a disposal authorization. Unknown starts remain busy until an explicit stop covers them. */
public final class DisposalSafetySql {
    private DisposalSafetySql() { }
    public static final String UNCERTAIN_START = "a.status='FAILED' AND a.channel='COUNTERMEASURE_4CH'"
            + " AND a.result_code='COUNTERMEASURE_OUTCOME_UNKNOWN'"
            + " AND NOT EXISTS (SELECT 1 FROM disposal_emergency_stop_authorization stopped"
            + " WHERE stopped.authorization_id=a.authorization_id)";
}
