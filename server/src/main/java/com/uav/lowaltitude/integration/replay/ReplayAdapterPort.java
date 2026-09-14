package com.uav.lowaltitude.integration.replay;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.uav.lowaltitude.integration.AdapterPort;
import com.uav.lowaltitude.integration.SourceMode;

/**
 * 回放模式的适配器声明：SourceModeGuard 要求 app.source-mode 对应的 AdapterPort 存在。
 * 它不做任何 I/O——回放数据由 FusionReplayRunner 直接写 inbox_message，这里只是让 source-mode=replay 的部署能启动。
 * 生产不注册（!production）：生产不生成也不消费回放数据。
 */
@Component
@Profile("!production")
public class ReplayAdapterPort implements AdapterPort {
    @Override
    public SourceMode mode() { return SourceMode.replay; }
}
