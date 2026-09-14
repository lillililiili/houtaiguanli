package com.uav.lowaltitude.modules.fusion.application;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.fusion.FusionContracts.SourceObservationPort;

/**
 * 实测雷达 ops → 阶段 2 提升的关闭态实现（决策 8-1 的预留入口，阶段 8.5 起只在开关关闭时注册）。
 * app.fusion.live-promotion.enabled=false 时任何调用都只记日志并丢弃，绝不把 ops 模型的数据写进统一目标库。
 *
 * 开关打开时改由 {@link LiveRadarSourceObservationPort} 注册。两者的 @ConditionalOnProperty 必须互斥：
 * 同时注册会让两个 SourceObservationPort Bean 并存，上下文启动即失败。
 */
@Component
@ConditionalOnProperty(prefix = "app.fusion.live-promotion", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopSourceObservationPort implements SourceObservationPort {
    private static final Logger log = LoggerFactory.getLogger(NoopSourceObservationPort.class);

    @Override
    public void accept(List<Map<String, Object>> observations) {
        // 开关打开时本 Bean 根本不注册（见类注释），所以这里只可能是关闭态：丢弃并记数。
        if (observations != null && !observations.isEmpty()) log.debug("live promotion disabled; dropped {} observations", observations.size());
    }
}
