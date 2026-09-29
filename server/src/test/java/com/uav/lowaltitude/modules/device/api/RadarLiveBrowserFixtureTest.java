package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.integration.device.radar.RadarV300Codec;

/** Explicit browser fixture: real TCP bytes, disposable database, no field hardware. */
@EnabledIfSystemProperty(named="qa.radar.live.browser", matches="true")
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL", matches="jdbc:postgresql://[^/]+/radar_browser_verify_[a-z0-9_]+")
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "server.address=127.0.0.1", "app.network.allow-loopback-when-listed=true",
        "app.live-device.enabled=true", "app.mqtt.enabled=false", "app.fusion.enabled=true",
        "app.fusion.live-promotion.enabled=true", "app.rule-engine.enabled=false",
        "app.automation-rules.enabled=false", "app.device-monitor-events.enabled=false",
        "app.device.mock-adapter.enabled=false", "app.fusion.replay.run-on-start=false",
        "app.rule-engine.replay.run-on-start=false"})
class RadarLiveBrowserFixtureTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private volatile String mode="HOLD";
    private final AtomicLong sequence=new AtomicLong(System.currentTimeMillis());
    private final AtomicLong sent=new AtomicLong();
    private final Set<Integer> commands=ConcurrentHashMap.newKeySet();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final List<String> errors=new CopyOnWriteArrayList<>();

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("POSTGRES_TEST_URL");
        if(url==null || !url.matches("jdbc:postgresql://[^/]+/radar_browser_verify_[a-z0-9_]+"))
            throw new IllegalArgumentException("Only disposable radar browser databases are allowed");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("POSTGRES_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/postgresql");
    }

    @Test void serveBrowserFixture() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).matches("jdbc:postgresql://[^/]+/radar_browser_verify_[a-z0-9_]+(?:\\?.*)?");
        }
        Path dir=Path.of("target","radar-live-browser").toAbsolutePath();
        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve("control"));
        var existingPorts=jdbc.queryForList("SELECT p.port FROM device_connection_profile p JOIN ops_device d ON d.device_id=p.device_id WHERE d.device_no='QA-F31-TCP' AND p.host='127.0.0.1'",Integer.class);
        int listenPort=existingPorts.isEmpty()?0:existingPorts.get(0);
        try(var radar=new ServerSocket(listenPort,8,InetAddress.getByName("127.0.0.1"))) {
            radar.setSoTimeout(250);
            long deadline=System.nanoTime()+Duration.ofMinutes(40).toNanos();
            while(System.nanoTime()<deadline) {
                Path control=dir.resolve("control");
                if(Files.exists(control)) {
                    String next=Files.readString(control).trim();
                    assertThat(List.of("RUN","HOLD","QUIET","CLOSE","STOP")).contains(next);
                    mode=next;
                    Files.delete(control);
                    if("STOP".equals(next))break;
                    if("CLOSE".equals(next))for(Socket socket:sockets)socket.close();
                }
                // Fixture identity only; no operational state, report or observation is fabricated.
                jdbc.update("UPDATE ops_device SET simulated=TRUE WHERE device_no='QA-F31-TCP' AND simulated=FALSE");
                jdbc.update("UPDATE ops_integration_source SET simulated=TRUE WHERE source_id IN (SELECT source_id FROM ops_device WHERE device_no='QA-F31-TCP') AND simulated=FALSE");
                try {
                    Socket socket=radar.accept();
                    if("CLOSE".equals(mode))socket.close();
                    else {
                        sockets.add(socket);
                        Thread worker=new Thread(() -> respond(socket),"qa-radar-live");
                        worker.setDaemon(true); worker.start();
                    }
                } catch(java.net.SocketTimeoutException expected) { }
                Map<String,Object> manifest=new LinkedHashMap<>();
                manifest.put("port",port); manifest.put("radar_port",radar.getLocalPort());
                manifest.put("mode",mode); manifest.put("simulated",true); manifest.put("clock",System.currentTimeMillis());
                manifest.put("commands",commands); manifest.put("track_frames_sent",sent.get()); manifest.put("errors",errors);
                manifest.put("devices",jdbc.queryForList("SELECT device_id,source_id,device_no,enabled,simulated FROM ops_device WHERE device_no='QA-F31-TCP'"));
                Files.writeString(dir.resolve("manifest.json"),mapper.writeValueAsString(manifest));
            }
        } finally {
            mode="STOP";
            for(Socket socket:sockets)socket.close();
        }
        assertThat(errors).isEmpty();
        assertThat(commands).contains(1,2,0x41).isSubsetOf(1,2,0x41,0x10005);
        assertThat(sent.get()).isPositive();
    }

    private void respond(Socket socket) {
        try(socket) {
            socket.setSoTimeout(250);
            var decoder=new RadarV300Codec.StreamDecoder(4096,false);
            byte[] buffer=new byte[4096];
            boolean loggedIn=false, ready=false;
            long lastSent=0;
            while(!socket.isClosed() && !"STOP".equals(mode)) {
                try {
                    int read=socket.getInputStream().read(buffer);
                    if(read<0)return;
                    for(var request:decoder.feed(java.util.Arrays.copyOf(buffer,read))) {
                        commands.add(request.command());
                        if(!List.of(1,2,0x41,0x10005).contains(request.command()))
                            throw new IllegalStateException("refused unexpected command "+request.command());
                        if(!"RUN".equals(mode))continue;
                        byte[] payload=switch(request.command()) {
                            case 2 -> { loggedIn=true; yield ByteBuffer.allocate(6).putInt(5).putShort((short)0).array(); }
                            case 1 -> request.payload();
                            case 0x41 -> {
                                assertThat(request.payload()).containsExactly(0,0,0,2,0,0,4,0x40,0,0,4,1);
                                ready=true;
                                yield ByteBuffer.allocate(20).putInt(2).putInt(0x440).putInt(0x07030201).putInt(0x401).putInt(0x0401).array();
                            }
                            case 0x10005 -> request.payload();
                            default -> throw new IllegalStateException("unreachable");
                        };
                        send(socket,request.command(),request.frameId(),payload);
                        if(request.command()==0x10005)send(socket,0x10006,sequence.incrementAndGet(),rtk());
                    }
                } catch(java.net.SocketTimeoutException expected) { }
                if(loggedIn && ready && "RUN".equals(mode) && System.currentTimeMillis()-lastSent>=1000) {
                    send(socket,0x10006,sequence.incrementAndGet(),rtk());
                    long frame=sequence.incrementAndGet();
                    send(socket,0x30002,frame,track(frame));
                    sent.incrementAndGet(); lastSent=System.currentTimeMillis();
                }
            }
        } catch(java.net.SocketException expectedDisconnect) {
            // Normal end of a connect probe, commissioning session or explicit CLOSE.
        } catch(Exception|AssertionError ex) {
            errors.add(ex.getClass().getSimpleName()+": "+ex.getMessage());
        } finally { sockets.remove(socket); }
    }

    private static void send(Socket socket,int command,long frame,byte[] payload) throws java.io.IOException {
        socket.getOutputStream().write(RadarV300Codec.encode(command,frame,payload));
        socket.getOutputStream().flush();
    }

    private static byte[] rtk() {
        return ByteBuffer.allocate(32).putLong(37_450_000_000L).putLong(118_360_000_000L)
                .putLong(0).putInt(18).putInt(0).array();
    }

    private static byte[] track(long frame) {
        ByteBuffer data=ByteBuffer.allocate(104);
        data.putLong(31_000_000).putLong(frame).putLong(System.currentTimeMillis())
                .putInt(0).putInt(3_600_000).put((byte)0).put((byte)0).put((byte)1).put((byte)0).putInt(1);
        data.putInt(10_000).putInt(20_000).putInt(8_000).putInt(0).putInt(0).putInt(0)
                .putInt(31).putShort((short)1500).putShort((short)1).put(new byte[6])
                .put((byte)3).put((byte)0).put(new byte[16]).putInt(10_000).putInt(0);
        return data.array();
    }
}
