package com.fintech.identity;

import com.fintech.platform.web.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

    public record LoginRequest(String identifier, String mobile, @NotBlank String password) {}

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request) {
        String identifier = request.identifier() != null && !request.identifier().isBlank()
                ? request.identifier()
                : request.mobile();
        return ApiResponse.ok(userService.login(identifier, request.password()));
    }
}
