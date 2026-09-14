package com.uav.lowaltitude.modules.fusion.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;
import com.uav.lowaltitude.modules.fusion.ingest.FrameMapper.Frame;
import com.uav.lowaltitude.modules.fusion.ingest.FrameMapper.Item;

/**
 * 凌云协议 A 的解释规则：纯 Java，不碰数据库。
 * 这些规则一旦错了，错的不是一条数据而是整条链路的前提——所以每条都单独钉住。
 */
class LingyunSenseDataMapperTest {
    private static final long TIME = 1731657474920L;
    private final LingyunSenseDataMapper mapper = new LingyunSenseDataMapper(new ObjectMapper());

    private static InboxRow inbox(String deviceTypeAbbr, String payload) {
        return new InboxRow("inbox-1", "lingyun:" + deviceTypeAbbr + ":227", "8920", "src-1", TIME, payload);
    }

    private static String senseData(String extension) {
        return "{\"deviceId\":\"227\",\"msgCnt\":8920,\"ptTime\":" + (TIME - 20) + ",\"objects\":[{\"objectId\":\"583\",\"time\":" + TIME
                + ",\"longitude\":118.15742,\"latitude\":37.446735,\"altitude\":212.2,\"height\":100,\"speed\":13.5,\"extension\":" + extension + "}]}";
    }

    @Test
    void everyDeviceTypeIsAcceptedAndOnlyOpticalAndAoaLoseTheirPosition() {
        // 七种设备类型缩写都要能解释；协议说光电与 AOA 的经纬度无效，那两类必须落 NULL。
        for (String abbr : List.of("5ga", "radar", "tdoa", "dcd", "rid", "isrs")) {
            Item item = mapper.map(inbox(abbr, senseData("{}"))).items().get(0);
            assertThat(item.longitude()).as(abbr).isEqualTo(118.15742);
            assertThat(item.latitude()).as(abbr).isEqualTo(37.446735);
        }
        for (String abbr : List.of("oe", "aoa")) {
            Item item = mapper.map(inbox(abbr, senseData("{}"))).items().get(0);
            assertThat(item.longitude()).as(abbr).isNull();
            assertThat(item.latitude()).as(abbr).isNull();
            assertThat(item.quality()).as(abbr).containsEntry("position", "REFERENCE_UNKNOWN");
        }
    }

    @Test
    void aoaBearingIsKeptEvenThoughThereIsNoPosition() {
        Item item = mapper.map(inbox("aoa", senseData("{\"direction\":126.5,\"uavSN\":\"SN-9\"}"))).items().get(0);
        assertThat(item.longitude()).isNull();
        // AOA 给的是"看向哪儿"，方位必须留下来，否则这条观测除了时间什么都不剩。
        assertThat(item.quality()).containsEntry("bearing_deg", 126.5);
        assertThat(item.identityClue()).isEqualTo("SN-9");
    }

    @Test
    void altitudeStaysOutOfTheAltitudeColumnsUntilTheDatumIsConfirmed() {
        Item item = mapper.map(inbox("radar", senseData("{}"))).items().get(0);
        assertThat(item.altitudeAmslM()).isNull();
        assertThat(item.quality()).containsEntry("altitude_raw", 212.2).containsEntry("altitude_datum", "REFERENCE_UNKNOWN");
        // height 的基准是设备安装点地面，这个是明确的，可以进列。
        assertThat(item.heightAglM()).isEqualTo(100.0);
        assertThat(item.quality()).containsEntry("height_datum", "DEVICE_GROUND");
    }

    @Test
    void objectTypeTableIsAppliedAndIdentifyingIsNotAClass() {
        assertThat(mapper.map(inbox("radar", senseData("{\"objectType\":30}"))).items().get(0).classCode()).isEqualTo("UAV");
        assertThat(mapper.map(inbox("radar", senseData("{\"objectType\":40}"))).items().get(0).classCode()).isEqualTo("BIRD");
        assertThat(mapper.map(inbox("radar", senseData("{\"objectType\":100}"))).items().get(0).classCode()).isEqualTo("REMOTE_CONTROLLER");
        Item identifying = mapper.map(inbox("radar", senseData("{\"objectType\":255}"))).items().get(0);
        // 255 是"还没认出来"，不是一个类别：给了 class_code 页面就会显示成确定的类型。
        assertThat(identifying.classCode()).isNull();
        assertThat(identifying.quality()).containsEntry("identifying", true);
        Item unknownCode = mapper.map(inbox("radar", senseData("{\"objectType\":77}"))).items().get(0);
        assertThat(unknownCode.classCode()).isNull();
        assertThat(unknownCode.quality()).containsEntry("object_type_raw", 77L);
    }

    @Test
    void pilotPositionAndRadioCluesAreCarried() {
        Item item = mapper.map(inbox("dcd", senseData(
                "{\"uavSN\":\"SN-1\",\"uavModel\":\"DJI4P_Mavic\",\"channel\":\"5.73\",\"bandWidth\":\"10.00\",\"pilotLon\":118.2,\"pilotLat\":37.5}")))
                .items().get(0);
        assertThat(item.pilotLongitude()).isEqualTo(118.2);
        assertThat(item.pilotLatitude()).isEqualTo(37.5);
        // 序列号指向具体这一台，型号只说是哪一款：身份线索优先取序列号。
        assertThat(item.identityClue()).isEqualTo("SN-1");
        assertThat(item.quality()).containsEntry("uav_model", "DJI4P_Mavic");
        assertThat(item.quality().get("rf").toString()).contains("5.73").contains("10.00");
        assertThat(item.classSource()).isEqualTo("SENSE_DATA");
    }

    @Test
    void messageCounterAndHeadingAreDerived() {
        Frame frame = mapper.map(inbox("5ga", senseData("{\"speedX\":0,\"speedY\":10,\"taskId\":\"T-1\",\"probability\":0.8}")));
        Item item = frame.items().get(0);
        assertThat(item.quality()).containsEntry("msg_cnt", 8920L);
        // X 正东、Y 正北：只朝正北走就是 0 度。
        assertThat(item.headingDeg()).isEqualTo(0.0);
        assertThat(item.classConfidence()).isEqualTo(0.8);
        assertThat(frame.sessionKey()).isEqualTo("T-1");
        assertThat(frame.observedAt().toEpochMilli()).isEqualTo(TIME);
    }

    @Test
    void twoTaskIdsInOneFrameFailTheWholeFrame() {
        String mixed = "{\"deviceId\":\"227\",\"msgCnt\":1,\"ptTime\":" + TIME + ",\"objects\":["
                + "{\"objectId\":\"1\",\"time\":" + TIME + ",\"longitude\":118.1,\"latitude\":37.4,\"extension\":{\"taskId\":\"T-1\"}},"
                + "{\"objectId\":\"2\",\"time\":" + TIME + ",\"longitude\":118.2,\"latitude\":37.5,\"extension\":{\"taskId\":\"T-2\"}}]}";
        // 会话键参与 link 身份：两个任务并成一条 link 是静默错误，宁可整帧失败让 A 看见。
        assertThatThrownBy(() -> mapper.map(inbox("5ga", mixed)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MIXED_TASK_ID");

        // 只有一个任务 id、其余对象没给，不算冲突：整帧照常使用那一个。
        String single = "{\"deviceId\":\"227\",\"msgCnt\":1,\"ptTime\":" + TIME + ",\"objects\":["
                + "{\"objectId\":\"1\",\"time\":" + TIME + ",\"longitude\":118.1,\"latitude\":37.4,\"extension\":{\"taskId\":\"T-1\"}},"
                + "{\"objectId\":\"2\",\"time\":" + TIME + ",\"longitude\":118.2,\"latitude\":37.5,\"extension\":{}}]}";
        assertThat(mapper.map(inbox("5ga", single)).sessionKey()).isEqualTo("T-1");
    }

    @Test
    void emptyObjectListIsAnIdleFrameNotABadMessage() {
        // 设备按周期上报，视野里没目标时照发一条空的。当成坏报文会让 inbox 变 FAILED 并累计重试次数，
        // 几个空闲周期就能把一台正常设备推到上限——之后真有目标了反而领不进来（决策 10-16）。
        String idle = "{\"deviceId\":\"227\",\"msgCnt\":42,\"ptTime\":" + TIME + ",\"objects\":[]}";
        Frame frame = mapper.map(inbox("radar", idle));
        assertThat(frame.items()).isEmpty();
        // 帧内没有任何 time，观测时刻只能取报文级的 ptTime。
        assertThat(frame.observedAt().toEpochMilli()).isEqualTo(TIME);
        assertThat(frame.recordNo()).isEqualTo(42);

        // 连 ptTime 都没有就真的说不清这帧是什么时候的，那才是坏报文。
        assertThatThrownBy(() -> mapper.map(inbox("radar", "{\"deviceId\":\"227\",\"objects\":[]}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ptTime");
    }

    @Test
    void malformedPayloadsAreRejectedSoTheWholeFrameFails() {
        assertThatThrownBy(() -> mapper.map(inbox("radar", "{\"deviceId\":\"227\"}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("objects");
        assertThatThrownBy(() -> mapper.map(inbox("radar", "{\"objects\":[{\"objectId\":\"1\"}]}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("time");
        assertThatThrownBy(() -> mapper.map(new InboxRow("i", "lingyun:227", "1", "s", TIME, senseData("{}"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lingyun:<设备类型>");
    }
}
