package com.cabin.orchestrator.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Durable, opaque, revocable session store. Only a SHA-256 digest of the
 * browser credential is persisted; Google access tokens never enter this
 * table.
 */
@Service
public class PlatformSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CREDENTIAL_BYTES = 32;

    private final JdbcTemplate jdbc;
    private final Duration lifetime;

    public PlatformSessionService(
        JdbcTemplate jdbc,
        @Value("${cabin.security.platformSession.lifetimeHours:12}") long lifetimeHours) {
        this.jdbc = jdbc;
        this.lifetime = Duration.ofHours(Math.max(1, lifetimeHours));
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS platform_auth_session (
              credential_hash CHAR(64) PRIMARY KEY,
              subject_email   VARCHAR(320) NOT NULL,
              auth_source     VARCHAR(32) NOT NULL,
              created_at      BIGINT NOT NULL,
              expires_at      BIGINT NOT NULL,
              last_seen_at    BIGINT NOT NULL,
              revoked_at      BIGINT
            )""");
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_platform_auth_session_subject
            ON platform_auth_session(subject_email, expires_at)""");
    }

    public CreatedSession create(String subjectEmail, String authSource) {
        byte[] bytes = new byte[CREDENTIAL_BYTES];
        RANDOM.nextBytes(bytes);
        String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = Instant.now().toEpochMilli();
        long expiresAt = now + lifetime.toMillis();
        String email = subjectEmail.trim().toLowerCase(Locale.ROOT);
        String source = normalizeSource(authSource);

        jdbc.update("""
            INSERT INTO platform_auth_session
              (credential_hash, subject_email, auth_source, created_at, expires_at, last_seen_at, revoked_at)
            VALUES (?, ?, ?, ?, ?, ?, NULL)
            """, hashCredential(credential), email, source, now, expiresAt, now);
        return new CreatedSession(credential,
            new Session(email, source, now, expiresAt));
    }

    public Optional<Session> resolve(String credential) {
        if (credential == null || credential.isBlank()) return Optional.empty();
        long now = Instant.now().toEpochMilli();
        List<Session> matches = jdbc.query("""
            SELECT subject_email, auth_source, created_at, expires_at
            FROM platform_auth_session
            WHERE credential_hash = ? AND revoked_at IS NULL AND expires_at > ?
            """, (rs, rowNum) -> new Session(
                rs.getString("subject_email"),
                rs.getString("auth_source"),
                rs.getLong("created_at"),
                rs.getLong("expires_at")),
            hashCredential(credential), now);
        if (matches.isEmpty()) return Optional.empty();
        jdbc.update("UPDATE platform_auth_session SET last_seen_at = ? WHERE credential_hash = ?",
            now, hashCredential(credential));
        return Optional.of(matches.getFirst());
    }

    public void revoke(String credential) {
        if (credential == null || credential.isBlank()) return;
        jdbc.update("""
            UPDATE platform_auth_session SET revoked_at = ?
            WHERE credential_hash = ? AND revoked_at IS NULL
            """, Instant.now().toEpochMilli(), hashCredential(credential));
    }

    static String hashCredential(String credential) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(credential.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    private static String normalizeSource(String source) {
        return "FAMILY_HUB".equalsIgnoreCase(source) ? "FAMILY_HUB" : "DIRECT_CABIN";
    }

    public record Session(String email, String authSource, long createdAt, long expiresAt) {}
    public record CreatedSession(String credential, Session session) {}
}
