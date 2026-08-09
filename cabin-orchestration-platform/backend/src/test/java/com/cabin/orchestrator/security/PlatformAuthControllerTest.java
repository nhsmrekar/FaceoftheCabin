package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAuthControllerTest {

    @Test
    void familyHubGoogleCallbackCreatesProtectedFirstPartySession() {
        GoogleIdentityVerifier verifier = mock(GoogleIdentityVerifier.class);
        PlatformAccessPolicy policy = mock(PlatformAccessPolicy.class);
        PlatformSessionService sessions = mock(PlatformSessionService.class);
        long expiresAt = Instant.now().plusSeconds(3600).toEpochMilli();
        when(verifier.verify("google-token"))
            .thenReturn(new GoogleIdentityVerifier.VerifiedGoogleIdentity("owner@example.com"));
        when(policy.requireAllowed("owner@example.com")).thenReturn("owner@example.com");
        when(sessions.create("owner@example.com", "FAMILY_HUB"))
            .thenReturn(new PlatformSessionService.CreatedSession("opaque-secret",
                new PlatformSessionService.Session(
                    "owner@example.com", "FAMILY_HUB", Instant.now().toEpochMilli(), expiresAt)));
        PlatformAuthController controller = new PlatformAuthController(
            verifier, policy, sessions, true);

        ResponseEntity<PlatformAuthController.SessionView> response = controller.create(
            "Bearer google-token", "FAMILY_HUB");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("owner@example.com", response.getBody().email());
        assertEquals("FAMILY_HUB", response.getBody().authSource());
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.contains("CABIN_PLATFORM_SESSION=opaque-secret"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertFalse(cookie.contains("Domain="));
    }

    @Test
    void inheritedCookieResolvesWithoutAnotherGoogleVerification() {
        GoogleIdentityVerifier verifier = mock(GoogleIdentityVerifier.class);
        PlatformAccessPolicy policy = mock(PlatformAccessPolicy.class);
        PlatformSessionService sessions = mock(PlatformSessionService.class);
        when(sessions.resolve("opaque-secret")).thenReturn(Optional.of(
            new PlatformSessionService.Session("owner@example.com", "FAMILY_HUB", 1L, 2L)));
        PlatformAuthController controller = new PlatformAuthController(
            verifier, policy, sessions, true);

        ResponseEntity<PlatformAuthController.SessionView> response = controller.current("opaque-secret");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("FAMILY_HUB", response.getBody().authSource());
        verify(sessions).resolve("opaque-secret");
    }
}
