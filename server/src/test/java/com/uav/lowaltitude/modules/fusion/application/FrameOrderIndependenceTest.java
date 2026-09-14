package com.uav.lowaltitude.modules.fusion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

/**
 * 同毫秒多源帧的处理顺序不得影响结论（决策 8.5-29）。
 *
 * 两台设备在同一毫秒各报一帧，谁"在前"是不存在的事实——所以稳定排序键买到的是可复现，不是正确。
 * 真正该守住的性质是这条：同一批帧无论以什么顺序处理，目标划分、link 归属与融合后的最新状态都必须一致。
 * 如果这条过不了，说明管线里有真实的顺序依赖，换排序键只是把它藏起来。
 */
@SpringBootTest(properties = {
        "app.dev-seed.enabled=true", "app.fusion.enabled=false", "app.fusion.replay.run-on-start=false",
        "spring.datasource.url=jdbc:h2:mem:stage85_order;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class FrameOrderIndependenceTest {

    /** 同一物理目标、同一毫秒，被三路各看见一次。 */
    private static final long OBSERVED_AT = 1_757_074_000_000L;
    private static final double LON = 118.63, LAT = 37.43;

    @Autowired JdbcTemplate jdbc;
    @Autowired FusionInboxRepository inbox;
    @Autowired FusionPipeline pipeline;

    @Test
    void sameMillisecondFramesGiveTheSameResultInAnyOrder() {
        List<String> sources = replaySourceIds();
        assertThat(sources).as("种子应已登记回放来源").hasSizeGreaterThanOrEqualTo(3);

        Snapshot forward = runInOrder(sources, false);
        Snapshot reversed = runInOrder(sources, true);

        // 前置：这批帧必须真的聚成同一个目标，顺序才有可能影响结论。
        // 若哪天它们各自成目标，下面两条断言就变成"三个互不相干的目标当然一样"，等于什么都没验——
        // 所以把"聚成一个、挂三条 link"这件事本身也钉住。
        assertThat(forward.linkGroups()).as("三路同位置同时刻应聚成一个目标，否则本用例失去意义").hasSize(1);
        assertThat(forward.linkGroups().get(0).split(",")).as("该目标下应挂三条来源 link").hasSize(3);
        assertThat(forward.latestStates()).as("融合层应写出该目标的最新状态").isNotEmpty();

        // 目标划分：同一批帧应聚成同样多的目标，且每个目标下挂的 (来源, 外部目标号) 集合一致。
        assertThat(reversed.linkGroups()).as("目标划分随处理顺序改变").isEqualTo(forward.linkGroups());
        // 融合后的最新状态：位置、类别、飞手位置都不该因为谁先被处理而不同。
        assertThat(reversed.latestStates()).as("融合最新状态随处理顺序改变").isEqualTo(forward.latestStates());
    }

    private Snapshot runInOrder(List<String> sourceIds, boolean reversed) {
        clearFusionData();
        List<InboxRow> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) rows.add(frame(sourceIds.get(i), i));
        if (reversed) Collections.reverse(rows);
        for (InboxRow row : rows) pipeline.processFrame(row);
        return snapshot();
    }

    /** 一条凌云协议 A 报文：三路给同一个位置，只有外部目标号不同（各源自己的编号）。 */
    private InboxRow frame(String sourceId, int index) {
        String inboxId = UUID.randomUUID().toString();
        String payload = "{\"deviceId\":\"ORD" + index + "\",\"msgCnt\":" + index + ",\"ptTime\":" + OBSERVED_AT + ",\"objects\":[{"
                + "\"objectId\":\"ORDER-" + index + "\",\"time\":" + OBSERVED_AT + ",\"longitude\":" + LON + ",\"latitude\":" + LAT
                + ",\"speed\":12.0,\"extension\":{\"objectType\":30,\"uavSN\":\"SN-ORDER\",\"pilotLon\":118.60,\"pilotLat\":37.40}}]}";
        jdbc.update("insert into inbox_message (inbox_id,source,source_msg_id,received_at,source_id,payload_hash,payload,status,fusion_attempts)"
                + " values (?,?,?,?,?,?,cast(? as json),'RECEIVED',0)",
                inboxId, "lingyun:tdoa:ORD" + index, UUID.randomUUID().toString(), OBSERVED_AT, sourceId, "0".repeat(64), payload);
        return new InboxRow(inboxId, "lingyun:tdoa:ORD" + index, "m" + index, sourceId, OBSERVED_AT, payload);
    }

    /** 目标划分用"每个目标下的 (来源, 外部目标号) 有序集合"表示：target_id 是随机 UUID，跨运行不可比。 */
    private Snapshot snapshot() {
        Map<String, List<String>> byTarget = new LinkedHashMap<>();
        jdbc.query("select l.target_id, s.source_code, l.external_target_id from target_source_link l"
                + " join integration_source s on s.source_id=l.source_id order by s.source_code, l.external_target_id", rs -> {
            byTarget.computeIfAbsent(rs.getString("target_id"), key -> new ArrayList<>())
                    .add(rs.getString("source_code") + "#" + rs.getString("external_target_id"));
        });
        List<String> groups = new ArrayList<>();
        for (List<String> members : byTarget.values()) { Collections.sort(members); groups.add(String.join(",", members)); }
        Collections.sort(groups);

        Map<String, String> states = new LinkedHashMap<>();
        jdbc.query("select t.target_id, cast(s.location as varchar) as loc, cast(s.pilot_location as varchar) as pilot,"
                + " t.object_type_code from target_latest_state s join target t on t.target_id=s.target_id where t.unified=true", rs -> {
            String key = byTarget.getOrDefault(rs.getString("target_id"), List.of()).stream().sorted().reduce((a, b) -> a + "," + b).orElse("?");
            states.put(key, rs.getString("loc") + "|" + rs.getString("pilot") + "|" + rs.getString("object_type_code"));
        });
        return new Snapshot(groups, states);
    }

    private record Snapshot(List<String> linkGroups, Map<String, String> latestStates) { }

    private List<String> replaySourceIds() {
        return jdbc.queryForList("select source_id from integration_source where source_mode='replay' and source_type is not null"
                + " order by source_code", String.class);
    }

    /**
     * 每轮从同一张白纸开始：留着上一轮的目标会让第二轮"关联到已有目标"，比的就不是同一件事了。
     * 删除顺序按外键依赖自底向上；下面这些表都直接引用 target，少一张都会让 delete target 撞外键。
     */
    private void clearFusionData() {
        jdbc.update("delete from track_point");
        for (String table : List.of("target_attribute_selection", "target_classification_revision", "target_degradation",
                "fusion_event", "target_latest_state", "target_track_status", "target_current_alias", "target_lineage",
                "source_observation", "track", "target_source_link")) {
            jdbc.update("delete from " + table);
        }
        jdbc.update("delete from target where unified=true");
        // inbox 行最后删：track_point 用 inbox_id 记录"这个点来自哪一帧"，先删会撞外键。
        jdbc.update("delete from inbox_message where source like 'lingyun:tdoa:ORD%'");
    }
}
