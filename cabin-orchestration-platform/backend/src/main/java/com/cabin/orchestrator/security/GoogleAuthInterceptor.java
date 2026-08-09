package com.cabin.orchestrator.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Optional;

/**
 * cabin-backend (api.unicornpingpong.com) is public now, same as family-hub
 * — see docs/EXECUTION_PLAN_2026-07-30.md / session notes on the "public
 * core app, Tailscale-only admin surfaces" decision. Notes and chore-
 * completion are the first *write* endpoints exposed to the open internet
 * on this backend, so unlike the existing device-status endpoints they
 * need a real gate. A verified Google bearer remains accepted for Family
 * Hub's existing calls, while cabin-ui uses the first-party platform session
 * created from that same Google authentication. Camera access therefore never
 * needs a separate OAuth token or query-parameter credential.
 */
@Component
public class GoogleAuthInterceptor implements HandlerInterceptor {

    private final GoogleIdentityVerifier googleVerifier;
    private final PlatformSessionService sessions;

    /** Kept for narrow routing tests whose requests return before auth lookup. */
    GoogleAuthInterceptor() {
        this.googleVerifier = null;
        this.sessions = null;
    }

    @Autowired
    public GoogleAuthInterceptor(
        GoogleIdentityVerifier googleVerifier,
        PlatformSessionService sessions) {
        this.googleVerifier = googleVerifier;
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS preflight (OPTIONS) is a browser-internal permissions check --
        // it never carries a real credential, and Firefox/Chrome both
        // require the preflight response itself to be a plain 2xx or they
        // refuse to send the real request at all, regardless of which CORS
        // headers are present on a non-2xx response. This interceptor was
        // rejecting every preflight with 401 (no token on an OPTIONS
        // request, correctly -- there never is one), which silently broke
        // every authenticated cross-origin call (notes/chores/profiles/
        // camera) from any real browser -- found 2026-08-03 via a real
        // user's Firefox network trace after curl-based testing repeatedly
        // (and wrongly) looked fine, since curl doesn't enforce this rule.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // The exact /api/tech-id/findings collection endpoint carries its
        // own, method-specific gating (TechIdController): POST there checks
        // a shared-secret API key since submitters are automated providers,
        // not signed-in humans; GET is intentionally open, matching
        // /api/events. This is an EXACT match, not a prefix -- sub-paths
        // like /api/tech-id/findings/{id} (PATCH: human adjudication) and
        // /api/tech-id/findings/{id}/actions (POST: human action-logging,
        // added for the Opportunity Map's See/Think/Act log) must still go
        // through the Google-token check below. Spring's addPathPatterns is
        // method-agnostic, so this split has to happen here instead of in
        // WebConfig.
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        boolean isFindingsCollection = path.equals(contextPath + "/api/tech-id/findings");
        if (isFindingsCollection
                && ("GET".equalsIgnoreCase(request.getMethod()) || "POST".equalsIgnoreCase(request.getMethod()))) {
            return true;
        }
        // Preserve the current public read-only device-status surface while
        // closing every device write. Candidate/catalog reads are deliberately
        // not included here; those require both this token check and the
        // narrower operator allowlist in DeviceCatalogController.
        boolean isPrivateDeviceConfig = path.endsWith("/config");
        boolean isDeviceRead = !isPrivateDeviceConfig
            && (path.equals(contextPath + "/api/devices")
                || path.startsWith(contextPath + "/api/devices/"))
            && "GET".equalsIgnoreCase(request.getMethod());
        if (isDeviceRead) return true;
        String sessionCredential = cookieValue(request, PlatformAuthController.COOKIE_NAME);
        if (sessions != null && sessionCredential != null) {
            Optional<PlatformSessionService.Session> session = sessions.resolve(sessionCredential);
            if (session.isPresent()) {
                request.setAttribute(REQUEST_ATTR_EMAIL, session.get().email());
                return true;
            }
        }

        String token = extractBearerToken(request);
        if (token == null || googleVerifier == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or expired platform session");
            return false;
        }
        try {
            GoogleIdentityVerifier.VerifiedGoogleIdentity identity = googleVerifier.verify(token);
            // Stashed for controllers that need "who did this" (e.g.
            // TechIdController's action log) without a second network
            // round-trip to Google -- see REQUEST_ATTR_EMAIL's own javadoc.
            request.setAttribute(REQUEST_ATTR_EMAIL, identity.email());
            return true;
        } catch (GoogleIdentityVerifier.VerificationException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token validation failed");
            return false;
        }
    }

    /** Request attribute key holding the token's verified Google account email, set only after a successful check above. */
    public static final String REQUEST_ATTR_EMAIL = "cabin.auth.googleEmail";

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && header.length() > 7) {
            return header.substring(7);
        }
        return null;
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
            .filter(cookie -> name.equals(cookie.getName()))
            .map(jakarta.servlet.http.Cookie::getValue)
            .filter(value -> !value.isBlank())
            .findFirst()
            .orElse(null);
    }
}
