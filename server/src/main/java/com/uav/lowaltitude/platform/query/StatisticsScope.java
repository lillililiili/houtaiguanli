package com.uav.lowaltitude.platform.query;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.platform.config.SimulationPolicy;

/**
 * 统计口径（2026-10-07 用户决定，ZT-17 的后续）：数据大屏和运行统计算设备报来的数据——
 * 真实设备（live）和设备模拟器（replay）；建库时系统自带的演示样例（mock）不计入。
 * 只有允许模拟的环境（local+qa、test，见 {@link SimulationPolicy}）才把设备模拟器算进来；
 * 正式环境仍只计 live，库里即便留有历史模拟记录也不进统计。要改口径只改这里。
 *
 * <p>大屏和运行统计各处的计数、设备台数、研判与风险都按这一口径取数，免得同一屏上有的数含样例、有的不含。
 * 导出的业务报表仍只计 live，不走这里。</p>
 */
@Component
public class StatisticsScope {

    /** 设备模拟器的来源；页面据此写明统计里有多少来自模拟器。 */
    public static final String SIMULATOR = "replay";

    private static final List<String> FORMAL = List.of("live");
    private static final List<String> WITH_SIMULATOR = List.of("live", SIMULATOR);

    private final SimulationPolicy simulation;

    public StatisticsScope(SimulationPolicy simulation) {
        this.simulation = simulation;
    }

    /** 计入统计的来源。 */
    public List<String> sourceModes() {
        return simulation.allowed() ? WITH_SIMULATOR : FORMAL;
    }

    public boolean counted(String sourceMode) {
        return sourceModes().contains(sourceMode);
    }

    /** SQL 片段，如 {@code ('live','replay')}；只由上面的常量拼成，不含请求输入。 */
    public String sqlIn() {
        return sourceModes().stream().map(mode -> "'" + mode + "'").collect(Collectors.joining(",", "(", ")"));
    }

    /**
     * 设备的 SQL 条件。设备按设备列表显示的来源判断：source_mode=live 但标了 simulated 的是后台自带的本机模拟设备，
     * 列表里显示为演示样例（mock），这里同样不计。正式环境因此正好是"live 且不是模拟设备"，与正式接入口径一致。
     */
    public String deviceSql(String alias) {
        return "(CASE WHEN " + alias + ".simulated=TRUE AND " + alias + ".source_mode='live' THEN 'mock' ELSE "
                + alias + ".source_mode END) IN " + sqlIn();
    }
}
