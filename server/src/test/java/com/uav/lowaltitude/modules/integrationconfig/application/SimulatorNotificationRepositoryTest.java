package com.uav.lowaltitude.modules.integrationconfig.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.infrastructure.DeviceMaintenanceNoticeRepository;
import com.uav.lowaltitude.modules.directory.infrastructure.DirectoryRepository;
import com.uav.lowaltitude.modules.flight.infrastructure.FlightVerificationRepository;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;

class SimulatorNotificationRepositoryTest {
    @Test void matchingAndSupersededAttemptsInH2(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:simulator_projection_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        verifyReceiptMatching(new JdbcTemplate(source));
    }
    static void verifyReceiptMatching(JdbcTemplate jdbc){
        jdbc.execute("CREATE TABLE flight_plan_feedback(feedback_id VARCHAR(36) PRIMARY KEY,delivery_status VARCHAR(32),receipt_status VARCHAR(32),blocked_reason VARCHAR(200),delivered_at BIGINT,acknowledged_at BIGINT)");
        jdbc.execute("CREATE TABLE ops_device_maintenance_task(task_id VARCHAR(36) PRIMARY KEY,status VARCHAR(30),workflow_state VARCHAR(30),notification_delivery_status VARCHAR(32),notification_receipt_status VARCHAR(32),notification_blocked_reason VARCHAR(200))");
        jdbc.execute("CREATE TABLE ops_device_maintenance_notice_attempt(attempt_id VARCHAR(36) PRIMARY KEY,task_id VARCHAR(36),attempt_no INTEGER,delivery_status VARCHAR(32),receipt_status VARCHAR(32),blocked_reason VARCHAR(200),delivered_at BIGINT,acknowledged_at BIGINT,outcome_state VARCHAR(32))");
        jdbc.update("INSERT INTO flight_plan_feedback(feedback_id,delivery_status,receipt_status,blocked_reason) VALUES('feedback','SUBMITTED','PENDING','marker')");
        jdbc.update("INSERT INTO ops_device_maintenance_task VALUES('task','PENDING','PENDING','SUBMITTED','PENDING','marker-new')");
        jdbc.update("INSERT INTO ops_device_maintenance_notice_attempt(attempt_id,task_id,attempt_no,delivery_status,receipt_status,blocked_reason,outcome_state) VALUES('old','task',1,'SUBMITTED','PENDING','marker-old','SUBMITTED'),('new','task',2,'SUBMITTED','PENDING','marker-new','SUBMITTED')");
        var at=Instant.ofEpochMilli(10000).atOffset(ZoneOffset.UTC);
        var ack=new DeliveryOutcome("DELIVERED","ACKNOWLEDGED",null,null,null,at,at.plusSeconds(1));
        var feedback=new FlightVerificationRepository(jdbc,mock(DirectoryRepository.class));
        var maintenance=new DeviceMaintenanceNoticeRepository(jdbc,new ObjectMapper());
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(ignored->{
            assertFalse(feedback.completeSimulatorReceipt("feedback","wrong",ack));
            assertTrue(feedback.completeSimulatorReceipt("feedback","marker",ack));
            assertFalse(feedback.completeSimulatorReceipt("feedback","marker",ack));
            assertFalse(maintenance.completeSimulatorReceipt("old","task","marker-old",ack,"COMPLETED"));
            assertTrue(maintenance.completeSimulatorReceipt("new","task","marker-new",ack,"COMPLETED"));
            assertFalse(maintenance.completeSimulatorReceipt("new","task","marker-new",ack,"COMPLETED"));
        });
        assertEquals(10000L,jdbc.queryForObject("SELECT delivered_at FROM flight_plan_feedback",Long.class));
        assertEquals("SUBMITTED",jdbc.queryForObject("SELECT delivery_status FROM ops_device_maintenance_notice_attempt WHERE attempt_id='old'",String.class));
        assertEquals("ACKNOWLEDGED",jdbc.queryForObject("SELECT notification_receipt_status FROM ops_device_maintenance_task",String.class));
        assertEquals("PENDING",jdbc.queryForObject("SELECT workflow_state FROM ops_device_maintenance_task",String.class));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM ops_device_maintenance_task",String.class));
    }
}
