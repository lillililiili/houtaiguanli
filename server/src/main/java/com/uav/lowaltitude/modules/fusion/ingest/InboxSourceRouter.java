package com.uav.lowaltitude.modules.fusion.ingest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.uav.lowaltitude.modules.fusion.infrastructure.FusionInboxRepository.InboxRow;

/**
 * 按 inbox 的 source 前缀把报文分派给对应的映射器（契约 v1.1 §6）。
 * 认不出前缀就抛：宁可让这一帧 FAILED 并留下报错，也不能猜一个映射器去解释别人的协议——
 * 猜错的结果是一批语义错误的观测静默进库，后面所有关联与研判都建立在它之上。
 */
@Component
public class InboxSourceRouter {
    private final Map<String, FrameMapper> byPrefix = new LinkedHashMap<>();

    public InboxSourceRouter(List<FrameMapper> mappers) {
        for (FrameMapper mapper : mappers) {
            String prefix = mapper.prefix();
            if (prefix == null || prefix.isBlank()) throw new IllegalStateException("映射器的 source 前缀不能为空: " + mapper.getClass().getName());
            FrameMapper previous = byPrefix.put(prefix, mapper);
            if (previous != null) {
                throw new IllegalStateException("同一个 source 前缀有两个映射器: " + prefix);
            }
        }
        // 互为前缀的注册（例如 live- 与 live-radar:）会让"命中哪个映射器"取决于 bean 的注入顺序——
        // 同一条报文在不同启动顺序下被不同协议解释，是最难查的一类问题。宁可启动就失败。
        for (String prefix : byPrefix.keySet()) {
            for (String other : byPrefix.keySet()) {
                if (!prefix.equals(other) && other.startsWith(prefix)) {
                    throw new IllegalStateException("source 前缀互为前缀，无法确定归属: " + prefix + " 与 " + other);
                }
            }
        }
    }

    public FrameMapper.Frame map(InboxRow inbox) {
        return mapperFor(inbox.source()).map(inbox);
    }

    /** 构造时已禁止互为前缀的注册，因此最多只会命中一个映射器，与遍历顺序无关。 */
    public FrameMapper mapperFor(String source) {
        String value = source == null ? "" : source;
        for (Map.Entry<String, FrameMapper> entry : byPrefix.entrySet()) {
            if (value.startsWith(entry.getKey())) return entry.getValue();
        }
        throw new IllegalStateException("没有能处理该来源前缀的映射器: " + value);
    }

    /** 供 inbox 领取白名单使用：只领我们真的能解释的前缀。 */
    public List<String> prefixes() { return List.copyOf(byPrefix.keySet()); }
}
