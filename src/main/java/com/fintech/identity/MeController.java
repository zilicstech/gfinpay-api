package com.fintech.identity;

import com.fintech.platform.security.AuthPrincipal;
import com.fintech.platform.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    public record UpdateProfileRequest(
            @NotBlank String fullName,
            String email,
            String shopName) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8) String newPassword) {}

    private final UserService users;

    public MeController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> me(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(users.me(me.userId()));
    }

    @GetMapping("/kyc")
    public ApiResponse<Map<String, Object>> kyc(@AuthenticationPrincipal AuthPrincipal me) {
        return ApiResponse.ok(users.ownKyc(me.userId()));
    }

    @PatchMapping
    public ApiResponse<Map<String, Object>> update(@AuthenticationPrincipal AuthPrincipal me,
                                                   @Valid @RequestBody UpdateProfileRequest req) {
        return ApiResponse.ok(users.updateProfile(me.userId(), req.fullName(), req.email(), req.shopName()));
    }

    @PostMapping("/password")
    public ApiResponse<Map<String, Object>> password(@AuthenticationPrincipal AuthPrincipal me,
                                                     @Valid @RequestBody ChangePasswordRequest req) {
        users.changePassword(me.userId(), req.currentPassword(), req.newPassword());
        return ApiResponse.ok(Map.of("updated", true));
    }
}
