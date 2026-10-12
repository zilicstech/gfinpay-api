package com.fintech.identity;

import com.fintech.platform.web.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public Gfinpay page that continues a retailer to PaySprint eKYC. */
@RestController
@RequestMapping("/api/v1/public/ekyc")
public class RetailerEkycLinkController {

    public record CompleteRequest(String data) {}

    private final RetailerPaysprintEkycService ekyc;

    public RetailerEkycLinkController(RetailerPaysprintEkycService ekyc) {
        this.ekyc = ekyc;
    }

    @GetMapping("/{token}")
    public ApiResponse<Map<String, Object>> status(@PathVariable String token) {
        return ApiResponse.ok(ekyc.publicStatus(token));
    }

    @PostMapping("/{token}/start")
    public ApiResponse<Map<String, String>> start(@PathVariable String token) {
        return ApiResponse.ok(ekyc.start(token));
    }

    @PostMapping("/{token}/complete")
    public ApiResponse<Map<String, Object>> complete(@PathVariable String token,
                                                     @RequestBody CompleteRequest body) {
        return ApiResponse.ok(ekyc.complete(token, body == null ? null : body.data()));
    }
}
