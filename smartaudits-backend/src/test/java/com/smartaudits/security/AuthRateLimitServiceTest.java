package com.smartaudits.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimitServiceTest {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-09-09T00:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void accountAndIpIsBlockedAfterFiveLoginFailures() {

        AuthRateLimitService service =
                new AuthRateLimitService(clock);

        String ip = "203.0.113.10";
        String email = "user@example.invalid";

        for (int i = 0; i < 5; i++) {
            service.checkLoginAllowed(ip, email);
            service.recordLoginFailure(ip, email);
        }

        assertThatThrownBy(
                () ->
                        service.checkLoginAllowed(
                                ip,
                                email
                        )
        )
                .isInstanceOf(
                        RateLimitExceededException.class
                )
                .hasMessageContaining(
                        "Demasiados intentos"
                );
    }

    @Test
    void successfulLoginClearsAccountSpecificFailures() {

        AuthRateLimitService service =
                new AuthRateLimitService(clock);

        String ip = "203.0.113.20";
        String email = "user@example.invalid";

        for (int i = 0; i < 4; i++) {
            service.recordLoginFailure(ip, email);
        }

        service.recordLoginSuccess(ip, email);

        assertThatCode(
                () ->
                        service.checkLoginAllowed(
                                ip,
                                email
                        )
        ).doesNotThrowAnyException();
    }

    @Test
    void fourthRegistrationAttemptFromSameIpIsBlocked() {

        AuthRateLimitService service =
                new AuthRateLimitService(clock);

        String ip = "203.0.113.30";

        service.consumeRegisterAttempt(ip);
        service.consumeRegisterAttempt(ip);
        service.consumeRegisterAttempt(ip);

        assertThatThrownBy(
                () ->
                        service.consumeRegisterAttempt(ip)
        )
                .isInstanceOf(
                        RateLimitExceededException.class
                )
                .hasMessageContaining(
                        "Demasiados intentos de registro"
                );
    }
}