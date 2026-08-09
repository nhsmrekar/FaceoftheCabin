package com.cabin.orchestrator.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/** Creates, inherits, inspects, and revokes the cross-app platform session. */
@RestController
@RequestMapping("/api/auth/session")
@CrossOrigin(
    origins = "${cabin.security.cors.allowedOrigins:https://hub.unicornpingpong.com,https://cabin.unicornpingpong.com,http://localhost:5173,http://127.0.0.1:5173,http://localhost:4080,http://127.0.0.1:4080,http://localhost:4081,http://127.0.0.1:4081}",
    allowCredentials = "true")
public class PlatformAuthController {

    public static final String COOKIE_NAME = "CABIN_PLATFORM_SESSION";

    private final GoogleIdentityVerifier verifier;
    private final PlatformAccessPolicy accessPolicy;
    private final PlatformSessionService sessions;
    private final boolean secureCookie;

    public PlatformAuthController(
        GoogleIdentityVerifier verifier,
        PlatformAccessPolicy accessPolicy,
        PlatformSessionService sessions,
        @Value("${cabin.security.platformSession.secureCookie:true}") boolean secureCookie) {
        this.verifier = verifier;
        this.accessPolicy = accessPolicy;
        this.sessions = sessions;
        this.secureCookie = secureCookie;
    }

    @PostMapping
    public ResponseEntity<SessionView> create(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = "X-Cabin-Auth-Source", required = false) String authSource) {
        String token = bearerToken(authorization);
        GoogleIdentityVerifier.VerifiedGoogleIdentity identity;
        try {
            identity = verifier.verify(token);
        } catch (GoogleIdentityVerifier.VerificationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String email = accessPolicy.requireAllowed(identity.email());
        PlatformSessionService.CreatedSession created = sessions.create(email, authSource);
        PlatformSessionService.Session session = created.session();
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(
                created.credential(), session.expiresAt()).toString())
            .body(SessionView.from(session));
    }

    @GetMapping
    public ResponseEntity<SessionView> current(
        @CookieValue(value = COOKIE_NAME, required = false) String credential) {
        return sessions.resolve(credential)
            .map(session -> ResponseEntity.ok(SessionView.from(session)))
            .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    @DeleteMapping
    public ResponseEntity<Void> revoke(
        @CookieValue(value = COOKIE_NAME, required = false) String credential) {
        sessions.revoke(credential);
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
            .build();
    }

    private ResponseCookie sessionCookie(String credential, long expiresAt) {
        long seconds = Math.max(1, Duration.between(
            Instant.now(), Instant.ofEpochMilli(expiresAt)).toSeconds());
        return cookie(credential).maxAge(Duration.ofSeconds(seconds)).build();
    }

    private ResponseCookie expiredCookie() {
        return cookie("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder cookie(String value) {
        // Intentionally host-only: both UIs call api.unicornpingpong.com with
        // credentials included, but sibling apps cannot read or overwrite it.
        return ResponseCookie.from(COOKIE_NAME, value)
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Lax")
            .path("/");
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            return null;
        }
        return authorization.substring(7);
    }

    public record SessionView(String email, String authSource, long expiresAt) {
        static SessionView from(PlatformSessionService.Session session) {
            return new SessionView(session.email(), session.authSource(), session.expiresAt());
        }
    }
}
