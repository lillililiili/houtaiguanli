package com.uav.lowaltitude.modules.flightavoidance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.uav.lowaltitude.modules.flightavoidance.application.AdsbReceiverService;
import com.uav.lowaltitude.modules.flightavoidance.domain.FlightCheck;
import com.uav.lowaltitude.platform.time.AppClock;

/** 不起 Spring：报文先攒着，每架只留时间最新的一条，一次 flush 每台接收机只写一次。 */
class PingStationUdpListenerTest {
    private static final String GUID = "754104714b10e9a8";

    @Test
    void flushWritesEachStationOnceWithOnlyTheNewestReportPerAircraft() throws Exception {
        AdsbReceiverService service = mock(AdsbReceiverService.class);
        when(service.receiveFromStation(eq(GUID), anyList(), anyLong())).thenReturn(1);
        PingStationUdpListener listener = new PingStationUdpListener(service, new ObjectMapper(), new AppClock(), false, 0, 1000);
        long base = 1_760_000_000_000L;
        assertThat(listener.handle(traffic(GUID, "780C01", 37.50, base + 1000), "127.0.0.1")).isEqualTo(1);
        assertThat(listener.handle(traffic(GUID, "780C01", 37.51, base + 2000), "127.0.0.1")).isEqualTo(1);
        assertThat(listener.handle(traffic(GUID, "780C01", 37.49, base), "127.0.0.1")).isEqualTo(1); // 最老的最后到
        assertThat(listener.handle(traffic(GUID, "780C02", 37.60, base + 1500), "127.0.0.1")).isEqualTo(1);

        assertThat(listener.flush()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FlightCheck.Aircraft>> batch = ArgumentCaptor.forClass(List.class);
        verify(service).receiveFromStation(eq(GUID), batch.capture(), anyLong());
        assertThat(batch.getValue()).extracting(FlightCheck.Aircraft::icao24, FlightCheck.Aircraft::observedAt)
                .containsExactly(tuple("780C01", base + 2000), tuple("780C02", base + 1500));
        assertThat(listener.flush()).isZero();
        assertThat(listener.unmatchedStations()).isEmpty();
    }

    @Test
    void unregisteredStationsAreCountedByMessageAtFlush() throws Exception {
        AdsbReceiverService service = mock(AdsbReceiverService.class);
        when(service.receiveFromStation(eq("00000000deadbeef"), anyList(), anyLong())).thenReturn(-1);
        PingStationUdpListener listener = new PingStationUdpListener(service, new ObjectMapper(), new AppClock(), false, 0, 1000);
        listener.handle(traffic("00000000deadbeef", "780C03", 37.50, 1_760_000_000_000L), "10.0.0.9");
        listener.handle(traffic("00000000deadbeef", "780C03", 37.50, 1_760_000_001_000L), "10.0.0.9");
        assertThat(listener.flush()).isZero();
        assertThat(listener.unmatchedStations()).singleElement().satisfies(s -> {
            assertThat(s.stationGuid()).isEqualTo("00000000deadbeef");
            assertThat(s.fromAddress()).isEqualTo("10.0.0.9");
            assertThat(s.messages()).isEqualTo(2);
        });
    }

    private static String traffic(String guid, String icao, double lat, long observedAt) {
        return "{\"aircraft\":[{\"icaoAddress\":\"" + icao + "\",\"latDD\":" + lat + ",\"lonDD\":118.6,\"altitudeMM\":2000000,"
                + "\"headingDE2\":9000,\"horVelocityCMS\":25000,\"callsign\":\"CSN3101\",\"pingStationGuid\":\"" + guid
                + "\",\"timeStamp\":\"" + java.time.Instant.ofEpochMilli(observedAt) + "\"}]}";
    }
}
