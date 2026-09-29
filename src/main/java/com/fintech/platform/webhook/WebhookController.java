package com.fintech.platform.webhook;

import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.ApiResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final WebhookService webhookService;
    private final WebhookSigner signer;

    public WebhookController(WebhookService webhookService, WebhookSigner signer) {
        this.webhookService = webhookService;
        this.signer = signer;
    }

    @PostMapping("/{provider}")
    public ApiResponse<Map<String, String>> receive(@PathVariable String provider,
                                                    @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
                                                    @RequestBody String rawBody) throws Exception {
        if (!signer.verify(rawBody, signature)) {
            throw ApiException.of(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID",
                    "HMAC signature verification failed");
        }
        String result = webhookService.ingest(provider, rawBody, true);
        return ApiResponse.ok(Map.of("result", result));
    }
}
