package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformAccessPolicyTest {

    @Test
    void admitsOnlyExplicitNormalizedAccounts() {
        PlatformAccessPolicy policy = new PlatformAccessPolicy(
            " Owner@example.com, family@example.com ");

        assertEquals("owner@example.com", policy.requireAllowed("OWNER@EXAMPLE.COM"));
        assertThrows(ResponseStatusException.class,
            () -> policy.requireAllowed("neighbor@example.com"));
    }

    @Test
    void emptyAllowlistFailsClosed() {
        PlatformAccessPolicy policy = new PlatformAccessPolicy("");
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> policy.requireAllowed("owner@example.com"));

        assertEquals(503, error.getStatusCode().value());
    }
}
