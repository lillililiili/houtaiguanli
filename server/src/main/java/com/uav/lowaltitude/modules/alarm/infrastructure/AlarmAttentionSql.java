package com.uav.lowaltitude.modules.alarm.infrastructure;

/** Read-only attention classification shared by list, detail and export, before pagination.
 * Observation expiry never changes verification, authorizations or historical evidence. */
final class AlarmAttentionSql {
    private AlarmAttentionSql() { }

    // Only the alarm's own scope and source mode can provide its current observation.
    private static final String VALID_TARGET = "tg.owner_org_id=a.owner_org_id AND tg.district_id=a.district_id AND tg.source_mode=a.source_mode";
    private static final String KNOWN_OBSERVATION = "(" + VALID_TARGET
            + " AND obs.observed_at IS NOT NULL AND obs.observed_at<=:attention_now AND obs.location IS NOT NULL"
            + " AND CAST(obs.unknown_fields AS VARCHAR) NOT LIKE '%TIME_UNTRUSTED%')";
    static final String OBSERVATION_STATUS = "CASE WHEN " + KNOWN_OBSERVATION
            + " THEN CASE WHEN obs.observed_at>:observation_since THEN 'CURRENT' ELSE 'EXPIRED' END ELSE 'UNKNOWN' END";
    private static final String AUTHORIZATION = "SELECT 1 FROM disposal_authorization da WHERE da.subject_kind='UAV_EVENT'"
            + " AND da.subject_id=e.event_id AND e.owner_org_id=a.owner_org_id AND e.district_id=a.district_id"
            + " AND da.owner_org_id=a.owner_org_id AND da.district_id=a.district_id AND da.source_mode=a.source_mode"
            + " AND da.action_type IN ('COUNTERMEASURE','JAMMING') AND da.channel<>'MANUAL'";
    private static final String EXECUTING = "EXISTS (" + AUTHORIZATION + " AND da.status IN ('APPROVED','EXECUTING'))";
    // Same unresolved definition as EmergencyStopRepository.unresolved; successful command receipt alone is not a site check.
    private static final String UNRESOLVED_STOP = "EXISTS (SELECT 1 FROM disposal_emergency_stop_device sd"
            + " JOIN disposal_emergency_stop es ON es.stop_id=sd.stop_id WHERE es.event_id=e.event_id"
            + " AND e.owner_org_id=a.owner_org_id AND e.district_id=a.district_id"
            + " AND sd.source_mode=a.source_mode AND sd.confirmed_at IS NULL AND sd.stop_status<>'NOT_REQUIRED')";
    // A prior completion cannot close a later attempt, nor a violation added after that completion.
    private static final String COMPLETED = "EXISTS (" + AUTHORIZATION + " AND da.status='COMPLETED'"
            + " AND da.updated_at>=COALESCE(esc.created_at,a.received_at)"
            + " AND NOT EXISTS (SELECT 1 FROM disposal_authorization newer WHERE newer.subject_kind=da.subject_kind"
            + " AND newer.subject_id=da.subject_id AND newer.owner_org_id=da.owner_org_id AND newer.district_id=da.district_id"
            + " AND newer.source_mode=da.source_mode AND newer.action_type IN ('COUNTERMEASURE','JAMMING') AND newer.channel<>'MANUAL'"
            + " AND (newer.requested_at>da.requested_at OR (newer.requested_at=da.requested_at AND newer.authorization_id>da.authorization_id))))";
    static final String GROUP_RANK = "CASE WHEN " + EXECUTING + " OR " + UNRESOLVED_STOP + " THEN 0"
            + " WHEN e.state_code='FALSE_POSITIVE' OR " + COMPLETED + " THEN 2"
            + " WHEN " + KNOWN_OBSERVATION + " AND obs.observed_at>:observation_since THEN 0 ELSE 1 END";
    static final String GROUP = "CASE (" + GROUP_RANK + ") WHEN 0 THEN 'CURRENT' WHEN 1 THEN 'AWAITING_CONFIRMATION' ELSE 'HISTORY' END";
}
