package com.uav.lowaltitude.platform.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * 报表导出的设备编号与后台设备管理页同一写法（问题清单 #18）。期望值由 ruoyi-ui/src/utils/format.js 的 formatDeviceNo
 * 直接算出：换设备类型、换编号长度、旧模拟编号、真实编号、非 ASCII 编号都要一致。
 */
class DeviceDisplayNoTest {
    @ParameterizedTest
    @CsvSource({
        "map-sim-eo-acceptance-eo,EO-S67GL",
        "map-sim-radar-acceptance-risk-radar,RADAR-QMIN7",
        "map-sim-5ga-acceptance-5ga,5G-A-4FNGK",
        "map-sim-weather-acceptance-weather,WX-5TWIZ",
        "map-sim-countermeasure-d-x-ab12,CM-ab12",
        "map-sim-eo-short,EO-short",
        "QA-LOCAL-CM4,QA-LOCAL-CM4",
        "sim-1234567890-ab12-3,SIM-ab12-3",
        "map-sim-radar-中文设备编号很长的,RADAR-FT6NK",
        "map-sim-eo-a😀bcdefghij,EO-8JSTN"
    })
    void matchesTheDeviceManagementPage(String raw, String expected) {
        assertThat(DeviceDisplayNo.format(raw)).isEqualTo(expected);
    }

    @Test
    void emptyStaysEmpty() {
        assertThat(DeviceDisplayNo.format(null)).isNull();
        assertThat(DeviceDisplayNo.format("")).isNull();
    }
}
