package com.uav.lowaltitude.integration;

/**
 * Mock / 回放 / 正式三种数据源实现同一接口，只换 Adapter。
 */
public interface AdapterPort {

    SourceMode mode();
}
