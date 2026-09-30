package com.uav.lowaltitude.modules.risk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort;
import com.uav.lowaltitude.modules.handoff.domain.HandoffChannelPort.DeliveryOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

/** PostgreSQL evidence and API-created notification histories; all channel receipts are explicitly synthetic. */
@EnabledIfSystemProperty(named="qa.risk.clearance.browser",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.address=127.0.0.1","app.rule-engine.allow-demo-active=true"})
class RiskClearanceBrowserFixtureTest extends RiskClearancePostgresTest {
    @LocalServerPort int port;
    @MockBean HandoffChannelPort channel;
    private String receiptStatus = "PENDING";

    @Test void serveClearanceMatrix() throws Exception {
        when(channel.simulated()).thenReturn(true);
        when(channel.deliver(any())).thenAnswer(call -> {
            var at = ((HandoffChannelPort.HandoffDispatch) call.getArgument(0)).at();
            return new DeliveryOutcome("DELIVERED", receiptStatus, null, null, at, at,
                    "ACKNOWLEDGED".equals(receiptStatus) ? at : null);
        });
        jdbc.update("update notification_setting set enabled=true,channel_type='MOCK',endpoint_ref=null,valid_until=null where setting_id='risk-superior'");
        var items=new ArrayList<Map<String,Object>>();
        String clearedHandoff=notifyRisk("ACKNOWLEDGED");
        presence.recordC04Clearances();assertThat(readStatus()).isEqualTo("CLEARED");
        items.add(item("已确认解除，通知历史保留", "CLEARED", clearedHandoff));
        fixture();position(37.0001);
        String currentHandoff=notifyRisk("ACKNOWLEDGED");
        assertThat(readStatus()).isEqualTo("CURRENT");
        Map<String,Object> currentItem=item("仍在原风险范围，已签收仍提示", "CURRENT", currentHandoff);
        items.add(currentItem);
        var current=new CurrentContext(risk,target,track,point,plan,session,now);
        fixture();observationTime(now-200000,now-199000);
        String unknownHandoff=notifyRisk("PENDING");
        assertThat(readStatus()).isEqualTo("UNKNOWN");
        items.add(item("观测过期，不能假定解除", "UNKNOWN", unknownHandoff));
        var histories=new LinkedHashMap<String,NotificationHistory>();
        for(var item:items) histories.put(item.get("risk_id").toString(),history(item));
        Path directory=Path.of("target","risk-clearance-browser").toAbsolutePath();Files.createDirectories(directory);
        Path stop=directory.resolve("stop");Files.deleteIfExists(stop);
        Path advance=directory.resolve("advance");Files.deleteIfExists(advance);
        var manifest=new LinkedHashMap<String,Object>();
        manifest.put("port",port);manifest.put("simulated",true);
        manifest.put("database","isolated PostgreSQL schema");manifest.put("scenarios",items);
        manifest.put("stage","BEFORE_CLEAR");manifest.put("advance_risk_id",current.riskId());
        writeManifest(directory,manifest);
        boolean advanced=false;
        long deadline=System.nanoTime()+Duration.ofMinutes(20).toNanos();
        while(!Files.exists(stop)&&System.nanoTime()<deadline) {
            if(!advanced&&Files.exists(advance)) {
                risk=current.riskId();target=current.targetId();track=current.trackId();point=current.pointId();
                plan=current.planId();session=current.sessionId();
                // Keep the test clock frozen, but make the new observation strictly later than the original.
                now=Math.max(clock.nowMillis(),current.at())+5000;
                doReturn(now).when(clock).nowMillis();doReturn(Instant.ofEpochMilli(now)).when(clock).now();
                assertThat(readStatus()).isEqualTo("CURRENT");
                assertThat(count()).isZero();
                appendPosition(37.01);
                assertThat(readStatus()).isEqualTo("UNKNOWN"); // GET must not persist clearance.
                assertThat(count()).isZero();
                presence.recordC04Clearances();
                assertThat(readStatus()).isEqualTo("CLEARED");
                assertThat(count()).isEqualTo(1);
                assertThat(jdbc.queryForObject("select point_id from risk_clearance_evidence where risk_id=?",String.class,risk)).isEqualTo(point);
                presence.recordC04Clearances();assertThat(count()).isEqualTo(1);
                assertHistories(items,histories);
                currentItem.put("expected","CLEARED");
                currentItem.put("clearance_point_id",point);
                manifest.put("stage","AFTER_CLEAR");
                advanced=true;
                writeManifest(directory,manifest);
            }
            Thread.sleep(250);
        }
        assertThat(Files.exists(stop)).as("Browser inspection must be explicitly completed").isTrue();Files.deleteIfExists(stop);
        assertThat(advanced).as("Browser owner must inspect the same risk before and after advance").isTrue();
        for(var item:items) {
            risk=item.get("risk_id").toString();
            assertThat(readStatus()).isEqualTo(item.get("expected"));
        }
        assertHistories(items,histories);
    }

    private String notifyRisk(String expectedReceipt) throws Exception {
        // Only the new disposable fixture risk is reset; real verification/submission APIs create its histories.
        jdbc.update("update flight_risk set state_code='PENDING_VERIFICATION' where risk_id=?",risk);
        mvc.perform(post("/api/v1/risks/{id}/verifications",risk).header("Authorization","Bearer "+session)
                .header("Idempotency-Key",id()).contentType("application/json")
                .content("{\"conclusion\":\"CONFIRMED\",\"note\":\"隔离QA合成通知历史准备\",\"expected_version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("PENDING_NOTIFICATION"));
        receiptStatus=expectedReceipt;
        var response=mvc.perform(post("/api/v1/handoffs").header("Authorization","Bearer "+session)
                .header("Idempotency-Key",id()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("source_kind","RISK","source_id",risk,
                        "handoff_type","RISK_NOTICE","recipient_id","fixed-superior-recipient","expected_version",1))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.delivery_status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.receipt_status").value(expectedReceipt)).andReturn();
        String handoff=json.readTree(response.getResponse().getContentAsString()).path("data").path("handoff_id").asText();
        assertThat(handoff).isNotBlank();
        assertThat(jdbc.queryForObject("select state_code from flight_risk where risk_id=?",String.class,risk))
                .isEqualTo("ACKNOWLEDGED".equals(expectedReceipt)?"ACKNOWLEDGED":"NOTIFIED");
        assertThat(jdbc.queryForObject("select count(*) from handoff_delivery where handoff_id=?",Integer.class,handoff)).isEqualTo(1);
        mvc.perform(get("/api/v1/handoffs/{id}",handoff).header("Authorization","Bearer "+session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.material.risk.state").value("PENDING_NOTIFICATION"))
                .andExpect(jsonPath("$.data.material.risk.version").value(1));
        return handoff;
    }

    private Map<String,Object> item(String label,String expected,String handoff) {
        jdbc.update("update flight_risk set reason_text=? where risk_id=?", "隔离QA："+label,risk);
        return new LinkedHashMap<>(Map.of("label",label,"risk_id",risk,"plan_id",plan,"target_id",target,
                "expected",expected,"handoff_id",handoff,"delivery_status","DELIVERED","receipt_status",receiptStatus));
    }

    private NotificationHistory history(Map<String,Object> item) {
        String handoff=item.get("handoff_id").toString();
        return new NotificationHistory(jdbc.queryForMap("select * from flight_risk where risk_id=?",item.get("risk_id")),
                jdbc.queryForMap("select * from handoff where handoff_id=?",handoff),
                jdbc.queryForList("select * from handoff_delivery where handoff_id=? order by attempt_no",handoff),
                jdbc.queryForMap("select handoff_id,schema_version,CAST(snapshot AS VARCHAR) as snapshot,created_at from handoff_material_snapshot where handoff_id=?",handoff));
    }

    private void assertHistories(List<Map<String,Object>> items,Map<String,NotificationHistory> histories) {
        for(var item:items) {
            assertThat(history(item)).as("Notification and frozen material must remain unchanged for %s",item.get("risk_id"))
                    .isEqualTo(histories.get(item.get("risk_id").toString()));
            assertThat(jdbc.queryForObject("select count(*) from handoff where source_kind='RISK' and source_id=?",Integer.class,item.get("risk_id")))
                    .isEqualTo(1);
        }
    }

    private void writeManifest(Path directory,Map<String,Object> manifest) throws Exception {
        Files.writeString(directory.resolve("manifest.json"),json.writeValueAsString(manifest));
    }

    private record CurrentContext(String riskId,String targetId,String trackId,String pointId,String planId,String sessionId,long at) { }
    private record NotificationHistory(Map<String,Object> risk,Map<String,Object> handoff,
            List<Map<String,Object>> deliveries,Map<String,Object> material) { }
}
