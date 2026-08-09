package com.cabin.orchestrator.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Locale;
import java.util.Map;

/**
 * Verifies a short-lived Google access token and reduces it to the identity
 * facts the platform is allowed to trust. The raw token is never retained.
 */
@Component
public class GoogleIdentityVerifier {

    private final String expectedClientId;
    private final RestTemplate http;

    public GoogleIdentityVerifier(
        @Value("${cabin.google.oauthClientId:}") String expectedClientId,
        RestTemplateBuilder restTemplateBuilder) {
        this(expectedClientId, restTemplateBuilder.build());
    }

    GoogleIdentityVerifier(String expectedClientId, RestTemplate http) {
        this.expectedClientId = expectedClientId == null ? "" : expectedClientId.trim();
        this.http = http;
    }

    public VerifiedGoogleIdentity verify(String accessToken) {
        if (expectedClientId.isBlank()) {
            throw new VerificationException("Google OAuth client ID is not configured");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new VerificationException("Missing Google access token");
        }
        try {
            // Google supports POST for tokeninfo. Keep the credential in the
            // form body so it cannot leak through request URLs, proxy access
            // logs, browser history, or exception messages containing a URL.
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("access_token", accessToken);
            Map<?, ?> info = http.postForObject(
                "https://oauth2.googleapis.com/tokeninfo",
                new HttpEntity<>(form, headers),
                Map.class);
            if (info == null || info.get("error") != null) {
                throw new VerificationException("Invalid or expired Google access token");
            }
            if (!expectedClientId.equals(String.valueOf(info.get("aud")))) {
                throw new VerificationException("Google token was not issued for this app");
            }
            String email = normalizedEmail(info.get("email"));
            if (email.isBlank()) {
                throw new VerificationException("Google token did not identify an email account");
            }
            Object verified = info.get("email_verified");
            if (!Boolean.parseBoolean(String.valueOf(verified))) {
                throw new VerificationException("Google account email is not verified");
            }
            return new VerifiedGoogleIdentity(email);
        } catch (VerificationException e) {
            throw e;
        } catch (RestClientException e) {
            throw new VerificationException("Google token validation failed", e);
        }
    }

    private static String normalizedEmail(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
    }

    public record VerifiedGoogleIdentity(String email) {}

    public static class VerificationException extends RuntimeException {
        VerificationException(String message) { super(message); }
        VerificationException(String message, Throwable cause) { super(message, cause); }
    }
}
