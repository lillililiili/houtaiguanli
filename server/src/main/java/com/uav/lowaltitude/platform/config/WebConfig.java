package com.uav.lowaltitude.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                if (handler instanceof org.springframework.web.method.HandlerMethod method
                        && (method.hasMethodAnnotation(com.uav.lowaltitude.platform.security.BackendOnly.class)
                            || method.getBeanType().isAnnotationPresent(com.uav.lowaltitude.platform.security.BackendOnly.class))
                        && com.uav.lowaltitude.modules.identity.domain.UserType.forRole(
                            com.uav.lowaltitude.platform.security.AuthContext.require().roleCode())
                            != com.uav.lowaltitude.modules.identity.domain.UserType.BACKEND) {
                    throw new com.uav.lowaltitude.platform.api.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                            "BACKEND_ACCESS_DENIED", "该操作仅限后台用户");
                }
                return true;
            }
        }).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://127.0.0.1:*", "http://localhost:*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
