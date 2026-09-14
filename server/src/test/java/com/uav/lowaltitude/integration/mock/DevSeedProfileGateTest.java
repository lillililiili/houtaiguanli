package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 源码门禁：凡是受 {@code app.dev-seed.enabled} 控制的 ApplicationRunner，必须同时排除 production。
 * 只靠属性开关不够——隔离测试会故意在 production 上打开该开关，缺 {@code @Profile} 会把演示数据写进生产启动路径。
 */
class DevSeedProfileGateTest {

    @Test
    void everyDevSeedApplicationRunnerExcludesProduction() throws Exception {
        Path root = Path.of("src/main/java");
        assertThat(root).isDirectory();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.getFileName().toString().endsWith(".java")).forEach(path -> {
                String text;
                try {
                    text = Files.readString(path);
                } catch (Exception ex) {
                    throw new IllegalStateException(path.toString(), ex);
                }
                if (!text.contains("implements ApplicationRunner")) return;
                if (!text.contains("app.dev-seed")) return;
                if (!text.contains("@ConditionalOnProperty")) return;
                boolean excludesProduction = text.contains("!production") || hasTestOnlyProfile(text);
                if (!excludesProduction) offenders.add(root.relativize(path).toString().replace('\\', '/'));
            });
        }
        assertThat(offenders)
                .as("dev-seed ApplicationRunner 必须带 @Profile(\"!production ...\") 或 @Profile(\"test\")")
                .isEmpty();
    }

    private static boolean hasTestOnlyProfile(String text) {
        return text.contains("@Profile(\"test\")") || text.contains("@Profile({\"test\"})");
    }
}
