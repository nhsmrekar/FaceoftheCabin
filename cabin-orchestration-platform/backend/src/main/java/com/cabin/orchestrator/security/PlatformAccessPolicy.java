package com.cabin.orchestrator.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Explicit, fail-closed admission policy for ordinary platform sessions. */
@Component
public class PlatformAccessPolicy {

    private final Set<String> allowedEmails;

    public PlatformAccessPolicy(
        @Value("${cabin.security.platformSession.allowedEmails:}") String configuredEmails) {
        this.allowedEmails = Arrays.stream(configuredEmails.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }

    public String requireAllowed(String verifiedEmail) {
        if (allowedEmails.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Platform sign-in is disabled until an account allowlist is configured");
        }
        String email = verifiedEmail == null ? "" : verifiedEmail.trim().toLowerCase(Locale.ROOT);
        if (email.isBlank() || !allowedEmails.contains(email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "This Google account is not admitted to this orchestration hub");
        }
        return email;
    }
}
