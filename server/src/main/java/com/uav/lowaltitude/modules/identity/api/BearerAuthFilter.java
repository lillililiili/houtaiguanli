package com.uav.lowaltitude.modules.identity.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.identity.application.AuthService;
import com.uav.lowaltitude.platform.api.ApiResponse;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;

@Component
public class BearerAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_ATTR = "APP_SESSION_ID";

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public BearerAuthFilter(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/v1/auth/login".equals(path)
                || path.startsWith("/actuator/health")
                || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String sessionId = null;
        if (header != null && header.startsWith("Bearer ")) {
            sessionId = header.substring(7).trim();
        }
        AuthUser user = authService.resolve(sessionId);
        if (user == null) {
            writeUnauthorized(response);
            return;
        }
        String path = request.getRequestURI();
        if (user.mustChangePassword()
                && !"/api/v1/auth/me".equals(path)
                && !"/api/v1/auth/logout".equals(path)
                && !"/api/v1/auth/change-password".equals(path)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                    "PASSWORD_CHANGE_REQUIRED", "请先修改临时密码");
            return;
        }
        request.setAttribute(SESSION_ATTR, sessionId);
        AuthContext.set(user);
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "未登录或会话已失效");
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(code, message));
    }
}
