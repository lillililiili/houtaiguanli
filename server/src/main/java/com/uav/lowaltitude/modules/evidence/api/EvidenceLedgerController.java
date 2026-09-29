package com.uav.lowaltitude.modules.evidence.api;

import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import com.uav.lowaltitude.modules.evidence.application.EvidenceLedgerService;
import com.uav.lowaltitude.modules.evidence.api.EvidenceDtos.PageDto;
import com.uav.lowaltitude.modules.evidence.api.EvidenceLedgerDtos.*;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/evidence-ledger")
public class EvidenceLedgerController {
    private final EvidenceLedgerService service;
    public EvidenceLedgerController(EvidenceLedgerService service) { this.service = service; }
    @GetMapping public ApiResponse<PageDto<Entry>> list(@RequestParam MultiValueMap<String,String> query) { return ApiResponse.ok(service.list(query)); }
    @GetMapping("/stats") public ApiResponse<Stats> stats(@RequestParam MultiValueMap<String,String> query) { return ApiResponse.ok(service.stats(query)); }
    @GetMapping("/records/{kind}/{id}") public ApiResponse<Detail> record(@PathVariable String kind, @PathVariable String id,
            @RequestParam MultiValueMap<String,String> query) { return ApiResponse.ok(service.record(kind, id, query)); }
    @GetMapping("/materials/{kind}/{id}") public ApiResponse<Materials> materials(@PathVariable String kind, @PathVariable String id) { return ApiResponse.ok(service.materials(kind, id)); }
    @GetMapping("/export.csv") public ResponseEntity<byte[]> export(@RequestParam MultiValueMap<String,String> query, HttpServletRequest request) {
        return service.export(query, request.getRemoteAddr(), request.getHeader("User-Agent"));
    }
}
