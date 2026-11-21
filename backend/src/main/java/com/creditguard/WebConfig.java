package com.creditguard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Very small auth layer:
 *  - /api/auth/login, /api/auth/register  -> public
 *  - /api/admin/**                        -> header  X-Admin-Key: <ADMIN_KEY>
 *  - everything else                      -> header  Authorization: Bearer <token from login>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final VendorRepository vendors;

    @Value("${app.admin-key}")
    private String adminKey;

    @Value("${app.cors-origin}")
    private String corsOrigin;

    public WebConfig(VendorRepository vendors) {
        this.vendors = vendors;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsOrigin)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                if ("OPTIONS".equals(req.getMethod())) return true;
                String path = req.getRequestURI();

                if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) return true;

                if (path.startsWith("/api/admin")) {
                    if (adminKey.equals(req.getHeader("X-Admin-Key"))) return true;
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong admin key");
                }

                String h = req.getHeader("Authorization");
                if (h == null || !h.startsWith("Bearer ")) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in");
                }
                Vendor v = vendors.findByApiToken(h.substring(7))
                        .filter(x -> x.status == Vendor.Status.APPROVED)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                "Session expired or access revoked"));
                req.setAttribute("vendor", v);
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}
