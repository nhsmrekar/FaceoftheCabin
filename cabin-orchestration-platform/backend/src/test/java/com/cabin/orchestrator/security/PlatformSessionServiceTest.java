package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PlatformSessionServiceTest {

    @Test
    void persistsOnlyCredentialDigestAndNormalizedIdentity() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformSessionService service = new PlatformSessionService(jdbc, 12);

        PlatformSessionService.CreatedSession created = service.create(
            " Owner@Example.com ", "family_hub");

        assertEquals("owner@example.com", created.session().email());
        assertEquals("FAMILY_HUB", created.session().authSource());
        assertTrue(created.credential().matches("[A-Za-z0-9_-]{43}"));
        String digest = PlatformSessionService.hashCredential(created.credential());
        assertEquals(64, digest.length());
        assertFalse(digest.contains(created.credential()));
        verify(jdbc).update(contains("INSERT INTO platform_auth_session"),
            eq(digest), eq("owner@example.com"), eq("FAMILY_HUB"),
            anyLong(), anyLong(), anyLong());
    }

    @Test
    void unknownAuthSourceCannotCreateAThirdTrustPath() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformSessionService service = new PlatformSessionService(jdbc, 12);

        PlatformSessionService.CreatedSession created = service.create(
            "owner@example.com", "URL_TOKEN_TRANSFER");

        assertEquals("DIRECT_CABIN", created.session().authSource());
    }
}
