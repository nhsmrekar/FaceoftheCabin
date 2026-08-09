package com.cabin.orchestrator.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Narrow owner/operator allowlist for high-consequence device lifecycle
 * decisions. GoogleAuthInterceptor establishes the email; this policy decides
 * whether that verified account may admit, reject, configure, or enable.
 */
@Component
public class DeviceLifecycleAccessPolicy {

    private final Set<String> allowedEmails;

    public DeviceLifecycleAccessPolicy(
        @Value("${cabin.security.deviceAdmission.allowedEmails:}") String configuredEmails) {
        this.allowedEmails = Arrays.stream(configuredEmails.split(","))
            .map(String::trim).filter(value -> !value.isBlank())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }

    public String requireActor(HttpServletRequest request) {
        if (allowedEmails.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Device lifecycle decisions are disabled until an operator allowlist is configured");
        }
        Object raw = request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL);
        String email = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (email.isBlank() || !allowedEmails.contains(email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "This authenticated account cannot make device lifecycle decisions");
        }
        return email;
    }
}
