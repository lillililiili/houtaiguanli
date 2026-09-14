package com.uav.lowaltitude.modules.fusion.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 四个前缀各归各的映射器；认不出的前缀必须抛，不能猜一个去解释别人的协议。 */
class InboxSourceRouterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final InboxSourceRouter router = new InboxSourceRouter(List.of(
            new ReplayFrameMapper(json), new LingyunSenseDataMapper(json),
            new EoTrackingReportMapper(json), new LiveRadarFrameMapper(json)));

    @Test
    void everyContractPrefixReachesItsOwnMapper() {
        assertThat(router.mapperFor("replay:radar-a:ds-1")).isInstanceOf(ReplayFrameMapper.class);
        assertThat(router.mapperFor("lingyun:tdoa:227")).isInstanceOf(LingyunSenseDataMapper.class);
        assertThat(router.mapperFor("eo-edge:1421840000010157")).isInstanceOf(EoTrackingReportMapper.class);
        assertThat(router.mapperFor("live-radar:R-1")).isInstanceOf(LiveRadarFrameMapper.class);
        assertThat(router.prefixes()).containsExactlyInAnyOrder("replay:", "lingyun:", "eo-edge:", "live-radar:");
    }

    @Test
    void unknownPrefixesAreRejectedInsteadOfGuessed() {
        // ops 的设备行由设备模块自己处理；融合侧领到了也不该猜着解释。
        assertThatThrownBy(() -> router.mapperFor("live-device:D-1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> router.mapperFor(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> router.mapperFor("")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void twoMappersCannotClaimTheSamePrefix() {
        assertThatThrownBy(() -> new InboxSourceRouter(List.of(new ReplayFrameMapper(json), new ReplayFrameMapper(json))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("replay:");
    }

    @Test
    void mutuallyPrefixingRegistrationsAreRejectedAtStartup() {
        // "live-" 与 "live-radar:" 互为前缀时，命中哪个映射器取决于 bean 注入顺序——
        // 同一条报文在不同启动顺序下被不同协议解释，是最难查的一类问题，宁可启动就失败。
        FrameMapper broader = new FixedPrefixMapper("live-");
        assertThatThrownBy(() -> new InboxSourceRouter(List.of(broader, new LiveRadarFrameMapper(json))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("live-");
        // 注册顺序反过来同样要拒绝：不能靠"先注册谁"来碰运气。
        assertThatThrownBy(() -> new InboxSourceRouter(List.of(new LiveRadarFrameMapper(json), broader)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("live-");
        assertThatThrownBy(() -> new InboxSourceRouter(List.of(new FixedPrefixMapper(" "))))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 只为验证前缀校验：不参与任何真实报文解析。 */
    private record FixedPrefixMapper(String prefix) implements FrameMapper {
        @Override
        public Frame map(com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow inbox) {
            throw new UnsupportedOperationException("测试桩不解析报文");
        }
    }
}
