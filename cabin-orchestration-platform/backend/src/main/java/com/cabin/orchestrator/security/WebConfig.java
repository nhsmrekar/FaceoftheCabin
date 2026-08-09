package com.cabin.orchestrator.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Only /api/notes, /api/chores, /api/profiles, /api/camera,
 * /api/device-catalog, device writes (GET remains open; see
 * GoogleAuthInterceptor), and (PATCH only, see GoogleAuthInterceptor)
 * /api/tech-id/findings require an authenticated platform session or the
 * same verified Google bearer used to establish one —
 * every other endpoint (device status, dashboard config, events) stays open,
 * matching how it already worked before this interceptor existed.
 *
 * cabin.security.googleAuth.enabled defaults to true (secure by default —
 * absence of the property changes nothing). The only reason it exists is
 * local verification: there's no way to obtain a real Google access token
 * or platform session in an automated/offline integration run, so testing
 * needs a way to turn the gate off. Deliberately not referenced in any
 * shipped compose/.env file — set it only as an ad-hoc local override.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final GoogleAuthInterceptor authInterceptor;

    @Value("${cabin.security.googleAuth.enabled:true}")
    private boolean googleAuthEnabled;

    public WebConfig(GoogleAuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!googleAuthEnabled) return;
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/api/notes/**", "/api/chores/**", "/api/profiles/**",
                "/api/camera/**", "/api/devices/**", "/api/device-catalog/**",
                "/api/tech-id/findings/**");
    }
}
