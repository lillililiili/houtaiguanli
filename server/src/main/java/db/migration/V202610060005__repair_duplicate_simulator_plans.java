package db.migration;

import java.sql.Connection;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Link redundant simulator submissions without changing any flight or receipt. */
public class V202610060005__repair_duplicate_simulator_plans extends BaseJavaMigration {
    private static final Set<String> CLOCK_STATES = Set.of("PENDING", "EXECUTING", "COMPLETED");
    private static final Set<String> VOLATILE = Set.of("plan_id", "plan_no", "status_code", "created_at", "updated_at", "version");
    private record Identity(String actor, JsonNode payload, Map<String,String> current) {}
    private record Candidate(String id, Identity identity) {}

    @Override public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        ObjectMapper json = new ObjectMapper();
        List<Candidate> candidates = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT p.*,m.created_by AS request_actor,m.payload AS request_payload
                  FROM flight_plan p JOIN local_interface_message m ON m.subject_id=p.plan_id
                 WHERE p.source_id='local-flight-plan-simulator' AND p.source_mode IN ('mock','replay')
                   AND p.status_code IN ('PENDING','EXECUTING','COMPLETED')
                   AND m.kind='FLIGHT_PLAN' AND m.direction='IN' AND m.state='ACCEPTED'
                   AND m.external_id LIKE 'sim-map-plan-%'
                 ORDER BY p.created_at,p.plan_id,m.created_at,m.message_id
                """)) {
            while (rows.next()) {
                JsonNode parsed;
                try { parsed=json.readTree(rows.getString("request_payload")); }
                catch (com.fasterxml.jackson.core.JsonProcessingException error) { continue; }
                if (!(parsed instanceof ObjectNode payload) || !CLOCK_STATES.contains(payload.path("status_code").asText("PENDING"))) continue;
                if (!payload.path("route_version_id").asText().equals(rows.getString("route_version_id"))
                        || !payload.path("uav_sn").asText().equals(rows.getString("uav_sn"))) continue;
                if (rows.getTimestamp("start_at")==null || rows.getTimestamp("end_at")==null
                        || payload.path("start_at").asLong()!=rows.getTimestamp("start_at").getTime()
                        || payload.path("end_at").asLong()!=rows.getTimestamp("end_at").getTime()) continue;
                payload.remove(List.of("message_id","status_code"));
                if (!payload.hasNonNull("source_mode")) payload.put("source_mode","mock");
                if (!payload.path("source_mode").asText().equals(rows.getString("source_mode"))) continue;
                Map<String,String> current=new TreeMap<>();
                var metadata=rows.getMetaData();
                for(int column=1;column<=metadata.getColumnCount()-2;column++) {
                    String name=metadata.getColumnLabel(column).toLowerCase(Locale.ROOT);
                    if(!VOLATILE.contains(name)) current.put(name,rows.getString(column));
                }
                candidates.add(new Candidate(rows.getString("plan_id"),new Identity(rows.getString("request_actor"),payload,current)));
            }
        }
        Map<Identity,String> first=new HashMap<>();
        for(Candidate candidate:candidates) {
            String canonical=first.putIfAbsent(candidate.identity(),candidate.id());
            if(canonical==null || canonical.equals(candidate.id()) || hasIndependentHistory(connection,candidate.id())) continue;
            try(var insert=connection.prepareStatement("""
                    INSERT INTO flight_plan_duplicate(duplicate_plan_id,canonical_plan_id,reason)
                    SELECT ?,?,'SIMULATOR_LIFECYCLE_RESUBMISSION'
                     WHERE NOT EXISTS(SELECT 1 FROM flight_plan_duplicate WHERE duplicate_plan_id=?)
                    """)) {
                insert.setString(1,candidate.id());insert.setString(2,canonical);insert.setString(3,candidate.id());insert.executeUpdate();
            }
        }
    }

    private boolean hasIndependentHistory(Connection connection,String id) throws Exception {
        for(String table:List.of("flight_risk","assessment_result","rule_evaluation","evidence_link",
                "flight_plan_verification","flight_plan_feedback","ops_device_maintenance_task")) {
            try(var statement=connection.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE plan_id=?")) {
                statement.setString(1,id);
                try(var result=statement.executeQuery()) { result.next();if(result.getLong(1)>0)return true; }
            }
        }
        return false;
    }
}
