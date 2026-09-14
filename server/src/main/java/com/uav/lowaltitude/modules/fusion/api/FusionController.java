package com.uav.lowaltitude.modules.fusion.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import com.uav.lowaltitude.modules.fusion.api.FusionDtos.ClassificationRevisionDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.ConfigDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.ConfigOverviewDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.FusionStatusDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.LineageDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.MergeResultDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.ObservationDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.PageDto;
import com.uav.lowaltitude.modules.fusion.api.FusionDtos.SplitResultDto;
import com.uav.lowaltitude.modules.fusion.application.FusionCommandService;
import com.uav.lowaltitude.modules.fusion.application.FusionConfigService;
import com.uav.lowaltitude.modules.fusion.application.FusionReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/** 阶段 8 融合读写接口。目标详情的可空字段由 TargetReadService 追加，不在这里重复暴露目标读取。 */
@RestController
@RequestMapping("/api/v1")
public class FusionController {
    private final FusionReadService read;
    private final FusionConfigService config;
    private final FusionCommandService command;

    public FusionController(FusionReadService read, FusionConfigService config, FusionCommandService command) {
        this.read = read; this.config = config; this.command = command;
    }

    @GetMapping("/fusion/config")
    public ApiResponse<ConfigOverviewDto> config() { return ApiResponse.ok(config.config()); }

    @GetMapping("/fusion/status")
    public ApiResponse<FusionStatusDto> status() { return ApiResponse.ok(read.status()); }

    @GetMapping("/targets/{targetId}/lineage")
    public ApiResponse<PageDto<LineageDto>> lineage(@PathVariable String targetId, @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(read.lineage(targetId, parameters));
    }

    @GetMapping("/targets/{targetId}/observations")
    public ApiResponse<PageDto<ObservationDto>> observations(@PathVariable String targetId, @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(read.observations(targetId, parameters));
    }

    @PostMapping("/targets/{targetId}/classification-revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ClassificationRevisionDto> reviseClass(@PathVariable String targetId, @RequestBody(required = false) String body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(command.reviseClassification(targetId, body, idempotencyKey));
    }

    @PostMapping("/targets/merge")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MergeResultDto> merge(@RequestBody(required = false) String body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(command.merge(body, idempotencyKey));
    }

    @PostMapping("/targets/{targetId}/split")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SplitResultDto> split(@PathVariable String targetId, @RequestBody(required = false) String body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(command.split(targetId, body, idempotencyKey));
    }

    @PostMapping("/fusion/config/{configVersion}/activate")
    public ApiResponse<ConfigDto> activate(@PathVariable String configVersion, @RequestBody(required = false) String body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(config.activate(configVersion, body, idempotencyKey));
    }
}
