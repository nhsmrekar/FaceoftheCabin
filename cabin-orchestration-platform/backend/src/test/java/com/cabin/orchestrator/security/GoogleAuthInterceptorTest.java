package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoogleAuthInterceptorTest {

    private final GoogleAuthInterceptor interceptor = new GoogleAuthInterceptor();

    @Test
    void publicDeviceStatusReadRemainsOpen() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices/dev-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void legacyDeviceConfigReadRequiresAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/api/devices/dev-1/config");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void catalogReadRequiresAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/api/device-catalog/candidates");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void inheritedPlatformSessionAuthenticatesCameraWithoutGoogleToken() throws Exception {
        GoogleIdentityVerifier verifier = mock(GoogleIdentityVerifier.class);
        PlatformSessionService sessions = mock(PlatformSessionService.class);
        when(sessions.resolve("opaque-secret")).thenReturn(Optional.of(
            new PlatformSessionService.Session("owner@example.com", "FAMILY_HUB", 1L, 2L)));
        GoogleAuthInterceptor sessionInterceptor = new GoogleAuthInterceptor(verifier, sessions);
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET", "/api/camera/front_door/live");
        request.setCookies(new Cookie(PlatformAuthController.COOKIE_NAME, "opaque-secret"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(sessionInterceptor.preHandle(request, response, new Object()));
        assertEquals("owner@example.com",
            request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL));
        verifyNoInteractions(verifier);
    }
}
