package com.uav.lowaltitude.modules.punishment.api;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.ActionResultDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.CaseDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.CaseEventDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.DecisionDocumentDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.PageDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.PenaltyRuleDto;
import com.uav.lowaltitude.modules.punishment.application.PunishmentCaseService;
import com.uav.lowaltitude.modules.punishment.application.PunishmentReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/**
 * 处罚案件接口。请求体以原文传进服务层做严格解析（未知字段一律拒绝），
 * 不用 @RequestBody 绑定成对象——那会把拼错的字段静默丢掉，让人以为自己填的参数生效了。
 */
@RestController
@RequestMapping("/api/v1")
public class PunishmentController {
    private final PunishmentCaseService service;
    private final PunishmentReadService read;

    public PunishmentController(PunishmentCaseService service, PunishmentReadService read) {
        this.service = service; this.read = read;
    }

    @GetMapping("/penalty-rules")
    public ApiResponse<List<PenaltyRuleDto>> rules() {
        return ApiResponse.ok(read.rules());
    }

    @GetMapping("/punishment-cases")
    public ApiResponse<PageDto<CaseDto>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String event_id, @RequestParam(required = false) String handoff_id,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(read.list(status, event_id, handoff_id, page, size));
    }

    @GetMapping("/punishment-cases/{id}")
    public ApiResponse<CaseDto> detail(@PathVariable String id) {
        return ApiResponse.ok(read.detail(id));
    }

    @GetMapping("/punishment-cases/{id}/events")
    public ApiResponse<List<CaseEventDto>> events(@PathVariable String id) {
        return ApiResponse.ok(read.events(id));
    }

    @PostMapping("/punishment-cases")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CaseDto> file(@RequestHeader("Idempotency-Key") String key,
                                     @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.file(body, key));
    }

    @PostMapping("/punishment-cases/{id}/assign")
    public ApiResponse<ActionResultDto> assign(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.assign(id, body, key));
    }

    @PostMapping("/punishment-cases/{id}/leads")
    public ApiResponse<ActionResultDto> addLead(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.addLead(id, body, key));
    }

    @PostMapping("/punishment-cases/{id}/leads/{leadId}/resolve")
    public ApiResponse<ActionResultDto> resolveLead(@PathVariable String id, @PathVariable String leadId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.resolveLead(id, leadId, body, key));
    }

    @PostMapping("/punishment-cases/{id}/discretions")
    public ApiResponse<ActionResultDto> draftDiscretion(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.draftDiscretion(id, body, key));
    }

    @PostMapping("/punishment-cases/{id}/discretions/{did}/confirm")
    public ApiResponse<ActionResultDto> confirmDiscretion(@PathVariable String id, @PathVariable String did,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.confirmDiscretion(id, did, body, key));
    }

    @PostMapping("/punishment-cases/{id}/reviews")
    public ApiResponse<ActionResultDto> review(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.review(id, body, key));
    }

    @PostMapping("/punishment-cases/{id}/decision-documents")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DecisionDocumentDto> issueDocument(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.issueDocument(id, body, key));
    }

    @GetMapping("/punishment-cases/{id}/decision-documents")
    public ApiResponse<List<DecisionDocumentDto>> documents(@PathVariable String id) {
        return ApiResponse.ok(read.documents(id));
    }

    /**
     * 文书正文下载。纯文本 UTF-8 附件——本期不做 PDF、不盖章（决策 14-10）：
     * 做成像模像样的 PDF 只会让人更容易把一份演示文书当真的用。
     */
    @GetMapping("/decision-documents/{docId}/content")
    public ResponseEntity<byte[]> content(@PathVariable String docId) {
        PunishmentReadService.RenderedDocument rendered = read.content(docId);
        byte[] bytes = rendered.text().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + rendered.documentNo() + ".txt\"")
                .body(bytes);
    }

    @PostMapping("/decision-documents/{docId}/revoke")
    public ApiResponse<DecisionDocumentDto> revokeDocument(@PathVariable String docId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.revokeDocument(docId, body, key));
    }

    @PostMapping("/punishment-cases/{id}/close")
    public ApiResponse<ActionResultDto> close(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.close(id, body, key));
    }

    @PostMapping("/punishment-cases/{id}/withdraw")
    public ApiResponse<ActionResultDto> withdraw(@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) String body) {
        return ApiResponse.ok(service.withdraw(id, body, key));
    }
}
