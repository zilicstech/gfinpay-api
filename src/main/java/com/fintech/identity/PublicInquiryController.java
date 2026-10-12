package com.fintech.identity;

import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/inquiries")
public class PublicInquiryController {

    public record SubmitInquiryRequest(
            @NotBlank String fullName,
            @NotBlank @Pattern(regexp = "\\d{10}", message = "10-digit mobile required") String mobile,
            @NotBlank @Email(message = "A valid email is required") String email,
            @NotBlank String city,
            String message) {}

    private final PartnerInquiryService inquiries;

    public PublicInquiryController(PartnerInquiryService inquiries) {
        this.inquiries = inquiries;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> submit(@Valid @RequestBody SubmitInquiryRequest req) {
        return ApiResponse.ok(inquiries.submit(req.fullName(), req.mobile(), req.email(), req.city(), req.message()));
    }
}
