package com.uav.lowaltitude.modules.risk.api;

import java.math.BigDecimal;
import java.util.List;

/** WGS-84 闭合外环；高度范围尚未提供，不代表全高度受影响。时间均为 epoch 毫秒。 */
public record WeatherRiskDto(List<List<Double>> polygon, long publishedAt, long validFrom, long validTo,
        BigDecimal windSpeedMps, BigDecimal windFromDegrees, BigDecimal visibilityM, String sourceMode) { }
