package com.uav.lowaltitude.modules.alarm.application;

/** 电话录音通道边界。当前仅模拟；正式外呼需另外接入授权号码、幂等受理、可信接通/播完回执与对账。 */
public interface AdvisoryVoicePort {
    boolean simulationAvailable(String sourceMode);
    Delivery simulate(String sourceMode,AdvisoryVoiceRecording.Recording recording,String idempotencyKey);
    default Delivery simulate(String sourceMode,com.uav.lowaltitude.modules.directory.api.DirectoryDtos.RecipientSnapshot recipient,AdvisoryVoiceRecording.Recording recording,String key) {
        if(recipient==null||!recipient.configured()||recipient.contactId()==null)throw new IllegalArgumentException("缺少明确的执行飞手接收对象");
        return simulate(sourceMode,recording,key);
    }
    record Delivery(boolean simulated,String status,String providerCallId,Long answeredAt,Long playbackCompletedAt) { }
}
