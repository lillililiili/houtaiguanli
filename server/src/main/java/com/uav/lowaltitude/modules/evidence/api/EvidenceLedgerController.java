package com.uav.lowaltitude.modules.evidence.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import com.uav.lowaltitude.modules.evidence.application.EvidenceLedgerService;
import com.uav.lowaltitude.modules.evidence.api.EvidenceLedgerDtos.*;
import com.uav.lowaltitude.platform.api.ApiResponse;

@RestController
@RequestMapping("/api/v1/evidence-ledger")
public class EvidenceLedgerController {
    private final EvidenceLedgerService ledger;
    public EvidenceLedgerController(EvidenceLedgerService ledger){this.ledger=ledger;}
    @GetMapping public ApiResponse<EvidenceDtos.PageDto<Entry>> list(@RequestParam MultiValueMap<String,String> p){return ApiResponse.ok(ledger.list(p));}
    @GetMapping("/stats") public ApiResponse<Stats> stats(@RequestParam MultiValueMap<String,String> p){return ApiResponse.ok(ledger.stats(p));}
    @GetMapping("/records/{kind}/{id}") public ApiResponse<Detail> detail(@PathVariable String kind,@PathVariable String id,@RequestParam MultiValueMap<String,String> p){return ApiResponse.ok(ledger.detail(kind,id,p));}
    @GetMapping("/materials/{kind}/{id}") public ApiResponse<EvidenceChainDtos.ChainDto> materials(@PathVariable String kind,@PathVariable String id,@RequestParam MultiValueMap<String,String> p){return ApiResponse.ok(ledger.materials(kind,id,p));}
    @GetMapping("/export.csv") public ResponseEntity<byte[]> export(@RequestParam MultiValueMap<String,String> p,HttpServletRequest request){return ledger.export(p,request.getRemoteAddr(),request.getHeader("User-Agent"));}
}
