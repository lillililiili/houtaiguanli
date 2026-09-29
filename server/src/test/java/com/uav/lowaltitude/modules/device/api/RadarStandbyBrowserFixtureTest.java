package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.uav.lowaltitude.integration.device.radar.RadarV300Codec;

/** Explicit, disposable browser fixture: loopback read-only protocol, never field hardware. */
@EnabledIfSystemProperty(named="qa.radar.browser", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:radar_browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "app.network.allow-loopback-when-listed=true", "app.live-device.enabled=false",
        "app.mqtt.enabled=false", "app.device-monitor-events.enabled=false"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class RadarStandbyBrowserFixtureTest extends DeviceProtocolApiTest {
    @LocalServerPort int port;
    private final List<Integer> commands = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();

    @Test void serveBrowserFixture() throws Exception {
        try (var connection=jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:radar_browser");
        }
        Path dir=Path.of("target", "radar-browser").toAbsolutePath();
        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve("stop"));
        try (ServerSocket radar=new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            radar.setSoTimeout(500);
            var login=request("/api/v1/auth/login", null, Map.of("account","admin1","password","changeme"));
            String token=login.path("session_id").asText();
            var source=request("/api/v1/integration-sources",token,Map.of("source_code","QA-RADAR-STANDBY",
                    "name","隔离只读待机雷达", "protocol_code","RADAR_TCP_V3_0_0",
                    "protocol_version","3.0.0", "allowed_cidrs","127.0.0.1/32"));
            // Only this guarded in-memory fixture may mark its newly created source as simulated.
            jdbc.update("UPDATE ops_integration_source SET simulated=TRUE,enabled=TRUE WHERE source_id=?", source.path("source_id").asText());
            var device=request("/api/v1/devices",token,Map.of("source_id",source.path("source_id").asText(),
                    "external_device_id","QA-RADAR-STANDBY", "device_no","QA-RADAR-STANDBY",
                    "name","隔离只读待机雷达", "device_type_code","radar", "device_type_name","雷达", "channel","雷达直连",
                    "connection",Map.of("transport","TCP","host","127.0.0.1","port",radar.getLocalPort(),"timeout_millis",1000),
                    "protocol_configuration",Map.of("login_role","DATA","rtk_enabled",false,"coordinate_transform_enabled",false)));
            String id=device.path("device").path("device_id").asText();
            assertThat(device.path("device").path("simulated").asBoolean()).isTrue();
            Files.writeString(dir.resolve("manifest.json"),mapper.writeValueAsString(Map.of("port",port,"device_id",id,
                    "radar_port",radar.getLocalPort(),"database","isolated H2 memory","simulated",true)));
            long deadline=System.nanoTime()+Duration.ofMinutes(30).toNanos();
            while (!Files.exists(dir.resolve("stop")) && System.nanoTime()<deadline) {
                try {
                    Socket socket=radar.accept();
                    Thread worker=new Thread(() -> respond(socket),"qa-read-only-radar");
                    worker.setDaemon(true); worker.start();
                } catch (java.net.SocketTimeoutException expected) { }
                Files.writeString(dir.resolve("protocol-evidence.json"),mapper.writeValueAsString(Map.of("commands",commands,"errors",errors,"business_frames_sent",0)));
            }
            assertThat(errors).isEmpty();
            assertThat(commands).containsOnly(1,2,0x41);
        }
    }

    private JsonNode request(String path,String token,Object body) throws Exception {
        var request=post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        if(token!=null) request.header("Authorization","Bearer "+token);
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private void respond(Socket socket) {
        try(socket) {
            socket.setSoTimeout(6000);
            while(true) {
                byte[] header=socket.getInputStream().readNBytes(8);
                if(header.length==0)return; // The connection probe deliberately sends no login.
                if(header.length!=8)throw new IllegalStateException("truncated header");
                var buffer=ByteBuffer.wrap(header);
                if(buffer.getInt()!=0x55AA55AA)throw new IllegalStateException("invalid header");
                int length=buffer.getInt();
                if(length<10||length>4096)throw new IllegalStateException("invalid length");
                byte[] body=socket.getInputStream().readNBytes(length);
                byte[] frame=ByteBuffer.allocate(8+body.length).put(header).put(body).array();
                var decoded=new RadarV300Codec.StreamDecoder(4096,false).feed(frame);
                if(decoded.size()!=1)throw new IllegalStateException("invalid frame or CRC");
                var request=decoded.get(0); commands.add(request.command());
                byte[] payload=switch(request.command()) {
                    case 2 -> ByteBuffer.allocate(6).putInt(5).putShort((short)0).array();
                    case 1 -> request.payload();
                    case 0x41 -> {
                        assertThat(request.payload()).containsExactly(0,0,0,2,0,0,4,0x40,0,0,4,1);
                        yield ByteBuffer.allocate(20).putInt(2).putInt(0x440).putInt(0x07030201).putInt(0x401).putInt(0).array();
                    }
                    default -> throw new IllegalStateException("refused non-read-only command "+request.command());
                };
                socket.getOutputStream().write(RadarV300Codec.encode(request.command(),request.frameId(),payload));
                socket.getOutputStream().flush();
            }
        } catch(Exception|AssertionError ex) { errors.add(ex.getClass().getSimpleName()+": "+ex.getMessage()); }
    }
}
