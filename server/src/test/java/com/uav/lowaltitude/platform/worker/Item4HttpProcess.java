package com.uav.lowaltitude.platform.worker;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;

/** Exact owned child handle; authentication stays in memory and never enters evidence files. */
public record Item4HttpProcess(Process process, int port) implements AutoCloseable {
    public static Item4HttpProcess start(String databaseUrl, Path output, String name) throws Exception {
        Files.createDirectories(output);
        Path marker=output.resolve(name+".ready"), log=output.resolve(name+".log"), cpJar=output.resolve(name+".jar");
        var manifest=new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION,"1.0");
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.CLASS_PATH,java.util.Arrays.stream(cp.split(java.io.File.pathSeparator))
                .map(value->Path.of(value).toUri().toASCIIString()).collect(java.util.stream.Collectors.joining(" ")));
        try(var archive=new java.util.jar.JarOutputStream(Files.newOutputStream(cpJar),manifest)) { }
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var builder=new ProcessBuilder(executable,"-Dspring.devtools.restart.enabled=false","-Dfile.encoding=UTF-8","-cp",cpJar.toString(),
                Item4HttpRestartProcess.class.getName(),marker.toString()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("ITEM4_HTTP_DB_URL",databaseUrl);
        Process process=builder.start();
        try {
            Awaitility.await().atMost(Duration.ofSeconds(60)).until(()->Files.exists(marker)||!process.isAlive());
            assertThat(Files.exists(marker)).withFailMessage("Child startup failed: %s",log).isTrue();
            String[] values=Files.readString(marker).split(":");
            assertThat(Long.parseLong(values[0])).isEqualTo(process.pid());
            return new Item4HttpProcess(process,Integer.parseInt(values[1]));
        } catch(Throwable error) { new Item4HttpProcess(process,0).close(); throw error; }
    }
    public HttpResponse<String> request(String session, String method, String path, String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+session);
        if("GET".equals(method)) request.GET();
        else request.header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
                .method(method,HttpRequest.BodyPublishers.ofString(body==null?"{}":body));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Override public void close() throws Exception {
        if(process.isAlive()) process.destroyForcibly();
        assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();
    }
}
