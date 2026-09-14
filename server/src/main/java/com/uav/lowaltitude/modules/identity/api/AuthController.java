package com.uav.lowaltitude.modules.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.uav.lowaltitude.modules.identity.application.AuthService;
import com.uav.lowaltitude.modules.identity.api.AuthDtos.ChangePasswordRequest;
import com.uav.lowaltitude.modules.identity.api.AuthDtos.LoginRequest;
import com.uav.lowaltitude.modules.identity.api.AuthDtos.LoginResponse;
import com.uav.lowaltitude.modules.identity.api.AuthDtos.MeResponse;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return ApiResponse.ok(authService.login(req.account(), req.password(), clientIp(http), userAgent(http)));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest http) {
        AuthUser user = AuthContext.require();
        String sessionId = (String) http.getAttribute(BearerAuthFilter.SESSION_ATTR);
        authService.logout(sessionId, user, clientIp(http), userAgent(http));
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        return ApiResponse.ok(authService.me(AuthContext.require()));
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req, HttpServletRequest http) {
        authService.changePassword(AuthContext.require(), req.currentPassword(), req.newPassword(),
                clientIp(http), userAgent(http));
        return ApiResponse.ok(null);
    }

    private static String clientIp(HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        return ip == null ? "" : ip;
    }

    private static String userAgent(HttpServletRequest http) {
        String value = http.getHeader("User-Agent");
        return value == null ? "" : value.substring(0, Math.min(value.length(), 512));
    }
}
