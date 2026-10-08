package com.uav.lowaltitude.modules.device.api;

import static org.assertj.core.api.Assertions.*;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.device.infrastructure.VideoMediaClient;

class VideoMediaClientTest {
    private static final String PATH="qa/00000000-0000-0000-0000-000000000001";
    private static final String SESSION="00000000-0000-0000-0000-000000000002";
    @Test void initialManifestUsesFixedInternalCookieBootstrapWithoutRedirects() throws Exception {
        try(var server=new MediaStub(true,true)) {
            var media=new VideoMediaClient(new ObjectMapper(),server.origin(),server.origin());
            assertThat(new String(media.resource(PATH,"index.m3u8"),StandardCharsets.UTF_8)).startsWith("#EXTM3U");
            assertThat(server.lastRequest).contains("/index.m3u8?cookieCheck=1 HTTP/");
            assertThat(media.ready(PATH)).isFalse();
            assertThatThrownBy(()->media.resource(PATH,"seg1.mp4")).hasMessageContaining("暂不可用");
        }
    }
    @Test void mediaSessionIsPreservedButArbitraryQueriesAreRejected() throws Exception {
        try(var server=new MediaStub(false)) {
            var media=new VideoMediaClient(new ObjectMapper(),server.origin(),server.origin());
            String manifest=new String(media.resource(PATH,"session.m3u8"),StandardCharsets.UTF_8);
            assertThat(manifest).contains("?session=").doesNotContain(SESSION);
            var sessionMatcher=java.util.regex.Pattern.compile("session=([a-f0-9-]{36})").matcher(manifest);
            assertThat(sessionMatcher.find()).isTrue();String proxy=sessionMatcher.group(1);
            media.resource(PATH,"seg1.mp4",proxy);
            assertThat(server.lastRequest).contains("/seg1.mp4?session="+SESSION+" HTTP/");
            assertThatThrownBy(()->media.resource(PATH,"seg1.mp4",SESSION)).hasMessageContaining("已失效");
            assertThatThrownBy(()->media.resource("qa/00000000-0000-0000-0000-000000000003","seg1.mp4",proxy)).hasMessageContaining("已失效");
            assertThatThrownBy(()->new VideoMediaClient(new ObjectMapper(),server.origin(),server.origin()).resource(PATH,"seg1.mp4",proxy)).hasMessageContaining("已失效");
            for(String session:new String[]{"",SESSION+"&url=http://x",SESSION+"#fragment","../x","not-a-uuid"})
                assertThatThrownBy(()->media.resource(PATH,"seg1.mp4",session)).hasMessageContaining("会话无效");
            assertThatThrownBy(()->media.resource(PATH,"bad-query.m3u8")).hasMessageContaining("会话无效");
        }
    }
    @Test void resourceAndManifestCannotEscapeConfiguredOrigin() throws Exception {
        try(var server=new MediaStub(false)) {
            var media=new VideoMediaClient(new ObjectMapper(),server.origin(),server.origin());
            assertThat(new String(media.resource(PATH,"safe.m3u8"),StandardCharsets.UTF_8)).contains("seg1.mp4");
            assertThatThrownBy(()->media.resource(PATH,"unsafe.m3u8")).hasMessageContaining("路径无效");
            for(String resource:new String[]{"../index.m3u8","http://x/index.m3u8","%2e%2e.m3u8","file.ts?token=x","a/b.ts"})
                assertThatThrownBy(()->media.resource(PATH,resource)).hasMessageContaining("路径无效");
        }
    }
    @Test void redirectsAreNotFollowed() throws Exception {
        try(var server=new MediaStub(true)) {
            var media=new VideoMediaClient(new ObjectMapper(),server.origin(),server.origin());
            assertThat(media.ready(PATH)).isFalse();assertThatThrownBy(()->media.resource(PATH,"index.m3u8")).hasMessageContaining("暂不可用");
        }
    }
    @Test void readPasswordComesFromSimulatorCredentialsFileAndFollowsItsChanges(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file=directory.resolve("qa-video-credentials.json");
        String first="a".repeat(32), second="b".repeat(32);
        try(var server=new MediaStub(false)) {
            var media=new VideoMediaClient(new ObjectMapper(),server.origin(),server.origin(),"qa-platform","",file.toString(),new com.uav.lowaltitude.platform.time.AppClock());
            media.ready(PATH);
            assertThat(server.lastAuthorization).as("no file yet: anonymous, media denies").isNull();
            java.nio.file.Files.writeString(file,"{\"publish\":\""+"p".repeat(32)+"\",\"read\":\""+first+"\"}");
            media.ready(PATH);
            assertThat(server.lastAuthorization).isEqualTo(basic("qa-platform:"+first));
            java.nio.file.Files.writeString(file,"{\"publish\":\""+"p".repeat(32)+"\",\"read\":\""+second+"\"}");
            java.nio.file.Files.setLastModifiedTime(file,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()+5000));
            media.ready(PATH);
            assertThat(server.lastAuthorization).isEqualTo(basic("qa-platform:"+second));
            var configured=new VideoMediaClient(new ObjectMapper(),server.origin(),server.origin(),"qa-platform","c".repeat(32),file.toString(),new com.uav.lowaltitude.platform.time.AppClock());
            configured.ready(PATH);
            assertThat(server.lastAuthorization).as("explicit password wins over the file").isEqualTo(basic("qa-platform:"+"c".repeat(32)));
        }
    }
    private static String basic(String value){return "Basic "+java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    // Blocking socket avoids the Windows JDK17 AF_UNIX selector pipe dependency in HttpServer.
    private static class MediaStub implements AutoCloseable {
        private final ServerSocket socket;
        private final Thread worker;
        volatile String lastRequest, lastAuthorization;
        MediaStub(boolean redirect) throws Exception { this(redirect,false); }
        MediaStub(boolean redirect,boolean bootstrap) throws Exception {
            socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
            worker=new Thread(()->{
                while(!socket.isClosed()) {
                    try(var connection=socket.accept()) {
                        connection.setSoTimeout(2000);
                        var reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.US_ASCII));
                        String request=reader.readLine(),header;
                        lastRequest=request;lastAuthorization=null;
                        while((header=reader.readLine())!=null&&!header.isEmpty())
                            if(header.regionMatches(true,0,"Authorization:",0,14)) lastAuthorization=header.substring(14).trim();
                        String content=request!=null&&request.contains("/safe.m3u8")
                                ? "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\nseg1.mp4\n"
                                : "#EXTM3U\nhttp://attacker.invalid/stolen.ts\n";
                        if(request!=null&&request.contains("/session.m3u8")) content="#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4?session="+SESSION+"\"\nseg1.mp4?session="+SESSION+"\n";
                        if(request!=null&&request.contains("/bad-query.m3u8")) content="#EXTM3U\nseg1.mp4?session="+SESSION+"&redirect=http://x\n";
                        if(bootstrap) content="#EXTM3U\nvideo1_stream.m3u8?session="+SESSION+"\n";
                        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
                        boolean redirectNow=redirect && !(bootstrap && request!=null && request.contains("?cookieCheck=1 "));
                        String location=bootstrap ? request.split(" ")[1].split("\\?")[0]+"?cookieCheck=1" : "http://attacker.invalid/";
                        String response="HTTP/1.1 "+(redirectNow?"302 Found":"200 OK")+"\r\nConnection: close\r\nContent-Length: "+bytes.length
                                +"\r\n"+(redirectNow?"Location: "+location+"\r\n":"")+"\r\n";
                        connection.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                        connection.getOutputStream().write(bytes);
                    } catch(java.io.IOException error) { if(!socket.isClosed()) throw new IllegalStateException(error); }
                }
            },"test-media-http");worker.setDaemon(true);worker.start();
        }
        String origin(){return "http://127.0.0.1:"+socket.getLocalPort();}
        public void close() throws Exception {socket.close();worker.join(2500);}
    }
}
