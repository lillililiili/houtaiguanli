package com.uav.lowaltitude.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 审计日志里不许出现英文码（阶段 18）。
 *
 * <p>逐个补中文是补不完的：阶段 15 的风险导出写的是复数 `risks`、告警核实写的是单数 `alarm`，
 * 两个都没进字典，于是审计日志上直接显示英文。所以这里不是再列一遍清单，而是**从源码里把真正写进审计的码扫出来**，
 * 挨个要求字典有中文——以后谁新增一个动作码而忘了配中文，这条就红。
 */
class AuditLabelsCoverageTest {

    /**
     * `record` 有两个重载，字面量的位置不同，混在一起扫会把动作码当成模块码：
     * 十一参的形状是 `record(id, account, roleCode, "模块", "动作", ...)`；
     * 七参的形状是 `record(id, account, "动作", "对象类型", ...)`——**没有模块码**。
     * 用第三个参数是不是字符串字面量把两者分开。
     */
    private static final Pattern MODULE_CONSTANT = Pattern.compile("MODULE\\s*=\\s*\"([a-z_]+)\"");
    private static final Pattern WITH_CONSTANT = Pattern.compile("MODULE\\s*,\\s*\"([a-z_]+)\"");
    private static final Pattern ELEVEN_ARGS =
            Pattern.compile("record\\([^;\"]*?roleCode\\(\\)\\s*,\\s*\"([a-z_]+)\"\\s*,\\s*\"([a-z_]+)\"", Pattern.DOTALL);
    private static final Pattern SEVEN_ARGS =
            Pattern.compile("record\\([^;\"]*?account\\(\\)\\s*,\\s*\"([a-z_]+)\"\\s*,", Pattern.DOTALL);

    @Test
    void everyModuleAndActionCodeWrittenToTheAuditLogHasChinese() throws IOException {
        Set<String> modules = new LinkedHashSet<>(), actions = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains("audit.record(") && !source.contains("MODULE =")) continue;
                Matcher constant = MODULE_CONSTANT.matcher(source);
                if (constant.find()) modules.add(constant.group(1));
                Matcher withConstant = WITH_CONSTANT.matcher(source);
                while (withConstant.find()) actions.add(withConstant.group(1));
                Matcher eleven = ELEVEN_ARGS.matcher(source);
                while (eleven.find()) { modules.add(eleven.group(1)); actions.add(eleven.group(2)); }
                Matcher seven = SEVEN_ARGS.matcher(source);
                while (seven.find()) actions.add(seven.group(1));
            }
        }
        assertThat(modules).as("扫到的模块码").isNotEmpty();

        List<String> missingModules = modules.stream()
                .filter(code -> AuditLabels.module(code).equals(code)).sorted().toList();
        List<String> missingActions = actions.stream()
                .filter(code -> AuditLabels.action(code).equals(code)).sorted().toList();

        assertThat(missingModules).as("这些模块码在审计日志里会显示成英文").isEmpty();
        assertThat(missingActions).as("这些动作码在审计日志里会显示成英文").isEmpty();
    }
}
