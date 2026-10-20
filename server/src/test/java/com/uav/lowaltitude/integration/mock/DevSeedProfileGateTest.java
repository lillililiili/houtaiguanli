package com.uav.lowaltitude.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 源码门禁：凡是受 {@code app.dev-seed.enabled} 控制的 ApplicationRunner，必须同时排除 production。
 * 只靠属性开关不够——隔离测试会故意在 production 上打开该开关，缺 {@code @Profile} 会把演示数据写进生产启动路径。
 */
class DevSeedProfileGateTest {

    @Test
    void everyDevSeedApplicationRunnerExcludesProduction() throws Exception {
        List<String> offenders = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/test/java"))) {
            assertThat(root).isDirectory();
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.getFileName().toString().endsWith(".java")).forEach(path -> {
                    String text;
                    try {
                        text = Files.readString(path);
                    } catch (Exception ex) {
                        throw new IllegalStateException(path.toString(), ex);
                    }
                    if (!text.contains("app.dev-seed")) return;
                    if (!text.contains("@ConditionalOnProperty")) return;
                    if (!isApplicationRunner(root, path)) return;
                    checked.add(path.toString());
                    boolean excludesProduction = excludesFormalProfiles(root, path);
                    if (!excludesProduction) offenders.add(root.relativize(path).toString().replace('\\', '/'));
                });
            }
        }
        assertThat(checked).as("must inspect the retained test fixtures").isNotEmpty();
        assertThat(offenders)
                .as("dev-seed ApplicationRunner 必须带 @Profile(\"!production ...\") 或 @Profile(\"test\")")
                .isEmpty();
    }

    @Test
    void businessSeedersAndLegacyReplayAreAbsentFromRuntimeClasses() throws Exception {
        Path classes = Path.of("target/classes");
        assertThat(classes).isDirectory();
        try (Stream<Path> files = Files.walk(classes)) {
            assertThat(files.filter(path -> {
                String name = path.getFileName().toString();
                return name.matches(".*Seeder(?:\\$.*)?\\.class")
                        || name.matches("(?:RuleReplayRunner|RuleDemoVolumeReplayRunner|FieldDeviceScope|LocalAirspaceDemoTarget.*)(?:\\$.*)?\\.class");
            }).toList()).as("legacy fixtures must not enter the runtime artifact").isEmpty();
        }
        assertThat(codeSourcePath(LocalStage7RuleEngineSeeder.class))
                .isEqualTo(Path.of("target/test-classes").toRealPath());
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain-classes", "中文测试类", "classes with spaces", "中文 空格 %20"})
    void classLocationsCompareAsPathsRegardlessOfUrlEncoding(String directory, @TempDir Path root) throws Exception {
        Path classes = Files.createDirectory(root.resolve(directory));
        String name = CodeSourceFixture.class.getName();
        String resource = name.replace('.', '/') + ".class";
        Path classFile = classes.resolve(resource);
        Files.createDirectories(classFile.getParent());
        try (var bytes = getClass().getClassLoader().getResourceAsStream(resource)) {
            Files.copy(bytes, classFile);
        }
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, null)) {
            assertThat(codeSourcePath(Class.forName(name, false, loader))).isEqualTo(classes.toRealPath());
        }
    }

    private static Path codeSourcePath(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
    }

    static final class CodeSourceFixture { }

    private static boolean isApplicationRunner(Path root, Path path) {
        String name = root.relativize(path).toString().replace(java.io.File.separatorChar, '.').replace('/', '.').replaceAll("\\.java$", "");
        try {
            return org.springframework.boot.ApplicationRunner.class.isAssignableFrom(Class.forName(name, false, DevSeedProfileGateTest.class.getClassLoader()));
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException(name, error);
        }
    }

    private static boolean excludesFormalProfiles(Path root, Path path) {
        String name = root.relativize(path).toString().replace('\\', '.').replace('/', '.').replaceAll("\\.java$", "");
        try {
            var profile = Class.forName(name, false, DevSeedProfileGateTest.class.getClassLoader()).getAnnotation(org.springframework.context.annotation.Profile.class);
            if (profile == null) return false;
            var expression = org.springframework.core.env.Profiles.of(profile.value());
            for (var active : List.of(java.util.Set.of("production"), java.util.Set.of("prod"),
                    java.util.Set.of("local"), java.util.Set.of("local", "qa", "production"),
                    java.util.Set.of("test", "prod"), java.util.Set.of("test", "production"))) {
                if (expression.matches(active::contains)) return false;
            }
            return true;
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException(name, error);
        }
    }
}
