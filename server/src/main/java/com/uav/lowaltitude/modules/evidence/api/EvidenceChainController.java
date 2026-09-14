package com.uav.lowaltitude.modules.evidence.api;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.evidence.api.EvidenceChainDtos.ChainDto;
import com.uav.lowaltitude.modules.evidence.application.EvidenceChainService;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/evidence-chains")
public class EvidenceChainController {
    private final EvidenceChainService chains;

    public EvidenceChainController(EvidenceChainService chains) {
        this.chains = chains;
    }

    @GetMapping("/{subjectKind}/{subjectId}")
    public ApiResponse<ChainDto> get(@PathVariable String subjectKind, @PathVariable String subjectId,
            @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.ok(chains.get(subjectKind, subjectId, parameters));
    }
}
