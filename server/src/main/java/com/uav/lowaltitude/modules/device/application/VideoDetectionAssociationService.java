package com.uav.lowaltitude.modules.device.application;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.infrastructure.EoEdgeRepository;
import com.uav.lowaltitude.platform.time.AppClock;

/** The producer supplies an observation reference; only stored source facts can supply a target identity. */
@Service
public class VideoDetectionAssociationService {
    public record Association(String observationId,long observedAt,String classCode,String targetId,String associationId,String quality) { }
    private final JdbcTemplate jdbc;
    private final EoEdgeRepository edges;
    private final ObjectMapper json;
    private final AppClock clock;
    public VideoDetectionAssociationService(JdbcTemplate jdbc,EoEdgeRepository edges,ObjectMapper json,AppClock clock) {
        this.jdbc=jdbc;this.edges=edges;this.json=json;this.clock=clock;
    }
    public List<Association> current(VideoStreamRegistry.Stream stream) {
        var binding=edges.binding(stream.deviceId(),false);if(binding==null || !binding.enabled())return List.of();
        long now=clock.nowMillis();
        return jdbc.query("""
                SELECT o.observation_id,o.observed_at,o.class_code,l.target_id,l.link_id,CAST(o.quality AS VARCHAR) AS quality
                FROM source_observation o JOIN integration_source s ON s.source_id=o.source_id AND s.enabled=TRUE
                JOIN target_source_link l ON l.source_id=o.source_id AND l.source_session_key=o.source_session_key
                    AND l.external_target_id=o.external_target_id AND l.device_id=o.device_id
                JOIN target t ON t.target_id=l.target_id AND t.source_mode=o.source_mode
                WHERE o.source_id=? AND o.device_id=? AND o.source_mode=? AND o.source_session_key=?
                    AND t.owner_org_id=? AND t.district_id=?
                    AND NOT EXISTS(SELECT 1 FROM target_current_alias a WHERE a.historical_target_id=t.target_id)
                    AND NOT EXISTS(SELECT 1 FROM target_track_status ts WHERE ts.target_id=t.target_id AND ts.status IN ('MERGE','SPLIT'))
                    AND o.class_source='EO_TRACKING' AND o.observed_at>=? AND o.observed_at<=?
                ORDER BY o.observed_at DESC,o.observation_id DESC FETCH FIRST 20 ROWS ONLY
                """,(rs,i)->new Association(rs.getString("observation_id"),rs.getTimestamp("observed_at").getTime(),
                        rs.getString("class_code"),rs.getString("target_id"),rs.getString("link_id"),rs.getString("quality")),
                binding.sourceId(),binding.deviceId(),binding.sourceMode(),stream.taskId(),binding.ownerOrgId(),binding.districtId(),new Timestamp(now-35_000),new Timestamp(now))
                .stream().filter(a->valid(stream,a)).toList();
    }
    private boolean valid(VideoStreamRegistry.Stream stream,Association association) {
        try {
            var facts=json.readTree(association.quality());if(facts!=null && facts.isTextual())facts=json.readTree(facts.asText());
            return facts!=null && !facts.hasNonNull("eo_fusion_exclusion")
                    && Objects.equals(stream.taskId(),facts.path("eo_task_id").asText())
                    && Objects.equals(association.targetId(),facts.path("eo_target_id").asText());
        } catch(java.io.IOException | IllegalArgumentException ex){return false;}
    }
    public Association resolve(VideoStreamRegistry.Stream stream,String observationId,long frameTimestamp) {
        if(observationId==null || observationId.isBlank())return null;
        return current(stream).stream().filter(a->Objects.equals(a.observationId(),observationId)
                && Math.abs(frameTimestamp-a.observedAt())<=2000).findFirst().orElse(null);
    }
    public boolean stillLinked(VideoStreamRegistry.Stream stream,VideoStreamRegistry.Detection detection,long frameTimestamp) {
        var association=resolve(stream,detection.observationId(),frameTimestamp);
        return association!=null && Objects.equals(association.targetId(),detection.targetId())
                && Objects.equals(association.associationId(),detection.associationId());
    }
}
