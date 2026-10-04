package com.uav.lowaltitude.modules.punishment.api;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.CaseDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.CaseEventDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.DecisionDocumentDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.PageDto;
import com.uav.lowaltitude.modules.punishment.api.PunishmentDtos.PenaltyRuleDto;
import com.uav.lowaltitude.modules.punishment.application.PunishmentReadService;
import com.uav.lowaltitude.platform.api.ApiResponse;

/**
 * 处罚案件只读接口。立案、指派、线索、裁量、复核、文书出具和结案已撤回，
 * 页面只看交接后的案件进度和已保存文书。
 */
@RestController
@RequestMapping("/api/v1")
public class PunishmentController {
    private final PunishmentReadService read;

    public PunishmentController(PunishmentReadService read) {
        this.read = read;
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
}
