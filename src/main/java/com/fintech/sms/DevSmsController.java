package com.fintech.sms;

import com.fintech.platform.web.ApiException;
import com.fintech.platform.web.ApiResponse;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/dev")
public class DevSmsController {

    private final MockSmsSender mockSms;
    private final Environment env;

    public DevSmsController(@Autowired(required = false) MockSmsSender mockSms, Environment env) {
        this.mockSms = mockSms;
        this.env = env;
    }

    @GetMapping("/last-otp")
    public ApiResponse<Map<String, Object>> lastOtp(@RequestParam(required = false) String mobile) {
        boolean prod = Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
        if (prod || mockSms == null) {
            throw ApiException.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "Endpoint not available");
        }
        MockSmsSender.SentSms sent = mockSms.lastFor(mobile);
        if (sent == null) {
            return ApiResponse.ok(Map.of("found", false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);
        out.put("mobile", sent.mobile());
        out.put("templateKey", sent.templateKey());
        out.put("otp", sent.params().getOrDefault("otp", ""));
        out.put("params", sent.params());
        out.put("sentAt", sent.sentAt());
        return ApiResponse.ok(out);
    }
}

