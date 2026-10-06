package com.uav.lowaltitude.modules.alarm.api;

import java.util.List;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class AdvisoryVoiceRecordingDtos {
    private AdvisoryVoiceRecordingDtos() { }

    /** used：已用于电话通知（领取过外呼或有成功记录），保留备查，不能删除。 */
    public record RecordingDto(String recordingId, String name, String transcript, String originalName, String contentType,
            long sizeBytes, String sha256, long durationMillis, int sampleRate, int channels,
            String uploadedByName, long uploadedAt, boolean active, boolean used, boolean canDelete) { }

    /**
     * 电话通知现在会播放什么。source：UPLOADED 后台选用的上传录音；STARTUP_CONFIG 启动参数配置的录音；
     * NONE 没有录音（核实后电话这一步跳过）。available=false 表示已选用的上传录音文件缺失或损坏。
     */
    public record CurrentDto(String source, boolean available, String recordingId, String name, String message) { }

    /** startupRecordingName：启动参数配置的有效录音名称（没有或无效时省略），停止使用上传录音后改播它。 */
    public record CatalogDto(List<RecordingDto> items, String activeRecordingId, int version, CurrentDto current,
            String startupRecordingName, boolean voiceEnabled, boolean canManage, long maxSizeBytes, List<String> acceptedFormats) { }

    /** active=true 选用这段录音；active=false 停止使用这段（当前须正在使用），之后回到启动参数或无录音。 */
    public record ActiveRequest(@NotNull Boolean active, @NotNull @Min(0) Integer expectedVersion) { }
}
