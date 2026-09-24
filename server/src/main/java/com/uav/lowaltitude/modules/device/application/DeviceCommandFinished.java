package com.uav.lowaltitude.modules.device.application;

/** 设备指令已经进入终态。处置授权据此结案，不等待有人打开详情。 */
public record DeviceCommandFinished(String commandId) { }
