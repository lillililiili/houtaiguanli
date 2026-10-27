package com.uav.lowaltitude.modules.alarm.application;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Map;
import com.uav.lowaltitude.platform.time.AppClock;

/** Align a live PostgreSQL read's cutoff with its own MVCC snapshot, not an earlier config read. */
public final class UavRiskReadClock {
    private UavRiskReadClock() { }

    public static String align(String sql, Map<String,Object> parameters, boolean postgis, AppClock clock) {
        if (!postgis || !clock.usesSystemClock()) return sql;
        String at="(SELECT at FROM risk_read_clock)";
        String changed=sql;
        if (parameters.get("measured_now") instanceof Timestamp now
                && parameters.get("measured_since") instanceof Timestamp since) {
            parameters.put("risk_measured_lifetime",now.getTime()-since.getTime());
            changed=changed.replace(":measured_since","("+at+" - :risk_measured_lifetime * INTERVAL '1 millisecond')")
                    .replace(":measured_now",at);
        }
        if (parameters.get("attention_now") instanceof OffsetDateTime now
                && parameters.get("observation_since") instanceof OffsetDateTime since) {
            parameters.put("risk_observation_lifetime",java.time.Duration.between(since,now).toMillis());
            changed=changed.replace(":observation_since","("+at+" - :risk_observation_lifetime * INTERVAL '1 millisecond')")
                    .replace(":attention_now",at);
        }
        changed=changed.replace(":prediction_now","CAST(FLOOR(EXTRACT(EPOCH FROM "+at+")*1000) AS BIGINT)");
        if (changed.equals(sql)) return sql;
        // clock_timestamp is evaluated once after this statement acquired its snapshot.
        // A fixed test/replay clock bypasses this wrapper; no future tolerance or TTL is added.
        String cte="risk_read_clock AS MATERIALIZED (SELECT clock_timestamp() AS at)";
        return changed.startsWith("WITH ") ? "WITH "+cte+","+changed.substring(5) : "WITH "+cte+" "+changed;
    }
}
