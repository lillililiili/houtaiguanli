package com.uav.lowaltitude.modules.device.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.platform.api.ApiException;
import com.uav.lowaltitude.platform.time.AppClock;

/** Only deployment-configured origins and server-issued paths can reach the media service. */
@Component
public class VideoMediaClient {
    private static final Pattern RESOURCE = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,150}\\.(m3u8|mp4|m4s|ts)");
    private static final Pattern MANIFEST_URI = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern SESSION = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final URI apiOrigin, hlsOrigin;
    private final ObjectMapper json;
    private final String authorization;
    private final AppClock clock;
    private final java.util.Map<String, PlaybackSession> playbackSessions = new java.util.LinkedHashMap<>();
    private record PlaybackSession(String path, String upstream, long expiresAt) { }
    private static final long SESSION_TTL_MS = 300_000;
    @org.springframework.beans.factory.annotation.Autowired
    public VideoMediaClient(ObjectMapper json,
            @Value("${app.video.media-api-origin:http://127.0.0.1:9997}") String api,
            @Value("${app.video.media-hls-origin:http://127.0.0.1:8888}") String hls,
            @Value("${app.video.media-username:qa-platform}") String username,
            @Value("${app.video.media-password:}") String password, AppClock clock) {
        this.json = json; apiOrigin = origin(api); hlsOrigin = origin(hls);
        this.clock = clock;
        authorization = password.isEmpty() ? null : "Basic " + java.util.Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
    public VideoMediaClient(ObjectMapper json,String api,String hls) {this(json,api,hls,"","",new AppClock());}
    private static URI origin(String value) {
        URI uri = URI.create(value);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
            throw new IllegalArgumentException("Media origin must be an HTTP origin without path or credentials");
        return URI.create(value.replaceAll("/+$", ""));
    }
    public boolean ready(String path) {
        validatePath(path);
        try {
            byte[] body = read(apiOrigin.resolve("/v3/paths/get/" + path), 64 * 1024);
            return json.readTree(body).path("ready").asBoolean(false);
        } catch (IOException | ApiException e) { return false; }
    }
    public byte[] resource(String path, String resource) {
        return resource(path, resource, null);
    }
    public byte[] resource(String path, String resource, String session) {
        validatePath(path); validateResource(resource); validateSession(session);
        String upstreamSession = session == null ? null : upstreamSession(path, session);
        try {
            // Explicitly choose MediaMTX's no-cookie bootstrap. Never follow redirects.
            String query = upstreamSession != null ? "?session="+upstreamSession
                    : "index.m3u8".equals(resource) ? "?cookieCheck=1" : "";
            byte[] body = read(hlsOrigin.resolve("/" + path + "/" + resource + query), MAX_BYTES);
            if (resource.endsWith(".m3u8")) return proxyManifest(path, body);
            return body;
        } catch (IOException e) { throw unavailable(); }
    }
    private byte[] read(URI uri, int maximum) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2000); connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw unavailable();
            try (var input = connection.getInputStream()) {
                byte[] body = input.readNBytes(maximum + 1);
                if (body.length > maximum) throw unavailable();
                return body;
            }
        } finally { connection.disconnect(); }
    }
    public static void validateResource(String resource) {
        if (resource == null || !RESOURCE.matcher(resource).matches() || resource.contains(".."))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VIDEO_RESOURCE_INVALID", "视频资源路径无效");
    }
    public static void validateSession(String session) {
        if (session != null && !SESSION.matcher(session).matches())
            throw new ApiException(HttpStatus.BAD_REQUEST, "VIDEO_RESOURCE_INVALID", "视频资源会话无效");
    }
    private String proxyReference(String path, String reference) {
        int query = reference.indexOf('?');
        if (query < 0) { validateResource(reference); return reference; }
        validateResource(reference.substring(0, query));
        String parameters = reference.substring(query + 1);
        if (!parameters.startsWith("session="))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VIDEO_RESOURCE_INVALID", "视频资源参数无效");
        validateSession(parameters.substring("session=".length()));
        return reference.substring(0,query)+"?session="+proxySession(path,parameters.substring("session=".length()));
    }
    // The upstream UUID is itself a media credential. Never expose it in browser manifests.
    private synchronized String proxySession(String path, String upstream) {
        long now=clock.nowMillis();
        playbackSessions.values().removeIf(s -> s.expiresAt() <= now);
        for(var entry:playbackSessions.entrySet()) {
            var existing=entry.getValue();
            if(existing.path().equals(path) && existing.upstream().equals(upstream)) {
                entry.setValue(new PlaybackSession(path,upstream,now+SESSION_TTL_MS));
                return entry.getKey();
            }
        }
        if(playbackSessions.size()>=512) playbackSessions.remove(playbackSessions.keySet().iterator().next());
        String id=java.util.UUID.randomUUID().toString();
        playbackSessions.put(id,new PlaybackSession(path,upstream,now+SESSION_TTL_MS));
        return id;
    }
    private synchronized String upstreamSession(String path,String proxy) {
        long now=clock.nowMillis();
        var session=playbackSessions.get(proxy);
        if(session==null || session.expiresAt()<=now || !session.path().equals(path))
            throw new ApiException(HttpStatus.NOT_FOUND,"VIDEO_MEDIA_SESSION_EXPIRED","视频播放会话已失效，请刷新视频状态");
        playbackSessions.put(proxy,new PlaybackSession(path,session.upstream(),now+SESSION_TTL_MS));
        return session.upstream();
    }
    private static void validatePath(String path) {
        if (path == null || !path.matches("qa/[a-f0-9-]{36}"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VIDEO_STREAM_INVALID", "视频流标识无效");
    }
    private byte[] proxyManifest(String path,byte[] data) {
        String content = new String(data, StandardCharsets.UTF_8);
        if (!content.startsWith("#EXTM3U")) throw unavailable();
        StringBuilder output=new StringBuilder();
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) line=proxyReference(path,line);
            else {
                var matcher = MANIFEST_URI.matcher(line);
                StringBuilder attributes=new StringBuilder();
                while(matcher.find()) matcher.appendReplacement(attributes,java.util.regex.Matcher.quoteReplacement("URI=\""+proxyReference(path,matcher.group(1))+"\""));
                matcher.appendTail(attributes);line=attributes.toString();
            }
            output.append(line).append('\n');
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VIDEO_STREAM_UNAVAILABLE", "视频流暂不可用，请检查推流服务");
    }
}
