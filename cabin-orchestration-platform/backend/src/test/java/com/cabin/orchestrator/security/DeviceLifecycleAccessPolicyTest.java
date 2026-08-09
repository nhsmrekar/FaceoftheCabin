package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class DeviceLifecycleAccessPolicyTest {

    @Test
    void emptyAllowlistFailsClosed() {
        DeviceLifecycleAccessPolicy policy = new DeviceLifecycleAccessPolicy("");
        MockHttpServletRequest request = requestFor("owner@example.com");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> policy.requireActor(request));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test
    void authenticatedAccountOutsideAllowlistIsDenied() {
        DeviceLifecycleAccessPolicy policy = new DeviceLifecycleAccessPolicy("owner@example.com");
        MockHttpServletRequest request = requestFor("intruder@example.com");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> policy.requireActor(request));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void allowlistMatchIsNormalizedAndReturnedAsDecisionActor() {
        DeviceLifecycleAccessPolicy policy = new DeviceLifecycleAccessPolicy(
            " OWNER@example.com , second@example.com ");

        assertEquals("owner@example.com", policy.requireActor(requestFor("Owner@Example.com")));
    }

    private MockHttpServletRequest requestFor(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, email);
        return request;
    }
}
