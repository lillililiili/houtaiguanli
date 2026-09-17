package com.uav.lowaltitude.modules.flight.infrastructure;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.uav.lowaltitude.modules.flight.api.FlightVerificationDtos.Verification;
import com.uav.lowaltitude.modules.flight.api.FlightVerificationDtos.Feedback;

@Repository
public class FlightVerificationRepository {
    private final JdbcTemplate jdbc;
    private final com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository directory;
    public FlightVerificationRepository(JdbcTemplate jdbc,com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository directory) { this.jdbc=jdbc;this.directory=directory; }
    public void lockPlan(String planId) { jdbc.queryForObject("SELECT plan_id FROM flight_plan WHERE plan_id=? FOR UPDATE",String.class,planId); }
    public List<Verification> verifications(String planId) {
        return jdbc.query("SELECT * FROM flight_plan_verification WHERE plan_id=? ORDER BY revision_no DESC",(rs,i)->
            new Verification(rs.getString("verification_id"),rs.getString("plan_id"),rs.getLong("revision_no"),
                rs.getString("conclusion"),rs.getString("takeoff_status"),rs.getString("evidence"),rs.getString("note"),
                rs.getString("handled_by"),rs.getString("handled_by_name"),rs.getLong("handled_at")),planId);
    }
    public List<Feedback> feedback(String planId) {
        return jdbc.query("SELECT * FROM flight_plan_feedback WHERE plan_id=? ORDER BY created_at DESC,feedback_id DESC",(rs,i)->
            new Feedback(rs.getString("feedback_id"),rs.getString("verification_id"),rs.getString("plan_id"),
                rs.getString("recipient_id"),rs.getString("recipient_name"),rs.getString("delivery_status"),rs.getString("receipt_status"),
                rs.getString("processing_result"),rs.getString("blocked_reason"),rs.getLong("created_at"),
                rs.getObject("submitted_at",Long.class),rs.getObject("delivered_at",Long.class),rs.getObject("acknowledged_at",Long.class),directory.decodeSnapshot(rs.getString("recipient_snapshot"))),planId);
    }
    public boolean sourceEnabled(String id) {
        return id != null && jdbc.queryForObject("SELECT COUNT(*) FROM integration_source WHERE source_id=? AND enabled=TRUE",Long.class,id)>0;
    }
    public void insert(Verification v) {
        jdbc.update("INSERT INTO flight_plan_verification(verification_id,plan_id,revision_no,conclusion,takeoff_status,evidence,note,handled_by,handled_by_name,handled_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            v.verificationId(),v.planId(),v.revisionNo(),v.conclusion(),v.takeoffStatus(),v.evidence(),v.note(),v.handledBy(),v.handledByName(),v.handledAt());
    }
    public void insert(Feedback f,String snapshot,String actor) {
        jdbc.update("INSERT INTO flight_plan_feedback(feedback_id,verification_id,plan_id,recipient_id,recipient_name,material_snapshot,delivery_status,receipt_status,processing_result,blocked_reason,submitted_by,created_at,submitted_at,delivered_at,acknowledged_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            f.feedbackId(),f.verificationId(),f.planId(),f.recipientId(),f.recipientName(),snapshot,f.deliveryStatus(),f.receiptStatus(),
            f.processingResult(),f.blockedReason(),actor,f.createdAt(),f.submittedAt(),f.deliveredAt(),f.acknowledgedAt());
    }
}
