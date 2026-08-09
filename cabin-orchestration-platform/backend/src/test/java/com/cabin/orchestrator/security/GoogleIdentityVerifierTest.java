package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleIdentityVerifierTest {

    @Test
    void reducesMatchingTokenToNormalizedVerifiedEmail() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://oauth2.googleapis.com/tokeninfo"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().string("access_token=token-1"))
            .andRespond(withSuccess("""
                {"aud":"client-1","email":"Owner@Example.com","email_verified":true}
                """, MediaType.APPLICATION_JSON));

        GoogleIdentityVerifier verifier = new GoogleIdentityVerifier("client-1", http);
        assertEquals("owner@example.com", verifier.verify("token-1").email());
        server.verify();
    }

    @Test
    void rejectsTokenForAnotherOAuthClient() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://oauth2.googleapis.com/tokeninfo"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string("access_token=token-2"))
            .andRespond(withSuccess("""
                {"aud":"other-client","email":"owner@example.com","email_verified":true}
                """, MediaType.APPLICATION_JSON));

        GoogleIdentityVerifier verifier = new GoogleIdentityVerifier("client-1", http);
        assertThrows(GoogleIdentityVerifier.VerificationException.class,
            () -> verifier.verify("token-2"));
    }

    @Test
    void rejectsIdentityWithoutAnExplicitVerifiedEmailClaim() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://oauth2.googleapis.com/tokeninfo"))
            .andRespond(withSuccess("""
                {"aud":"client-1","email":"owner@example.com"}
                """, MediaType.APPLICATION_JSON));

        GoogleIdentityVerifier verifier = new GoogleIdentityVerifier("client-1", http);
        assertThrows(GoogleIdentityVerifier.VerificationException.class,
            () -> verifier.verify("token-without-verification"));
    }

    @Test
    void rejectsExplicitlyUnverifiedEmail() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://oauth2.googleapis.com/tokeninfo"))
            .andRespond(withSuccess("""
                {"aud":"client-1","email":"owner@example.com","email_verified":false}
                """, MediaType.APPLICATION_JSON));

        GoogleIdentityVerifier verifier = new GoogleIdentityVerifier("client-1", http);
        assertThrows(GoogleIdentityVerifier.VerificationException.class,
            () -> verifier.verify("unverified-token"));
    }
}
