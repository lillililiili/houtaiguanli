package com.uav.lowaltitude.modules.device.application;

import com.uav.lowaltitude.modules.device.api.DeviceMaintenanceDtos.Recovery;

final class DeviceMaintenancePresentation {
    private DeviceMaintenancePresentation(){}
    static String primary(String state,Recovery recovery,boolean allowed){
        if(!allowed)return null;
        if("READY_TO_COMPLETE".equals(state)&&recovery!=null&&"PASS".equals(recovery.result()))return "CONFIRM_COMPLETE";
        return "PENDING".equals(state)?"START":null;
    }
    static String next(String state,Recovery recovery){
        if("COMPLETED".equals(state))return "本次设备异常已确认办结，可查看处理记录";
        if("LEGACY_HANDLED".equals(state))return "历史记录仅保存当时处理反馈，未记录恢复核验依据";
        if("READY_TO_COMPLETE".equals(state)&&recovery!=null&&"PASS".equals(recovery.result()))return "设备已恢复，请核对恢复依据后确认办结";
        if(recovery!=null&&"PASS".equals(recovery.result()))return "设备核验通过，系统正在更新办理状态，请稍候确认办结";
        String reason=recovery==null?"系统正在检查设备恢复情况":recovery.reason();
        return "PENDING".equals(state)?"请接手处理。"+reason:"请检查设备供电、网络及故障情况；系统将自动核验。"+reason;
    }
}
