package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

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
}
