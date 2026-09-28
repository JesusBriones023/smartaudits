package com.smartaudits.security;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthRateLimitService {

    /*
     * Protección local / single-instance.
     *
     * Cuando SmartAudits tenga varias instancias, estos contadores
     * deberán moverse a un almacenamiento compartido como Redis.
     */
    static final int LOGIN_MAX_FAILURES_PER_ACCOUNT_AND_IP = 5;
    static final int LOGIN_MAX_FAILURES_PER_IP = 20;
    static final int REGISTER_MAX_ATTEMPTS_PER_IP = 3;

    static final Duration LOGIN_WINDOW = Duration.ofMinutes(5);
    static final Duration REGISTER_WINDOW = Duration.ofMinutes(15);

    private final ConcurrentHashMap<String, AttemptWindow> loginAccountFailures =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AttemptWindow> loginIpFailures =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AttemptWindow> registerAttempts =
            new ConcurrentHashMap<>();

    private final Clock clock;

    public AuthRateLimitService() {
        this(Clock.systemUTC());
    }

    AuthRateLimitService(Clock clock) {
        this.clock = clock;
    }

    public void checkLoginAllowed(String ip, String email) {

        Instant now = clock.instant();

        long accountRetry = retryAfterSeconds(
                loginAccountFailures,
                loginAccountKey(ip, email),
                LOGIN_MAX_FAILURES_PER_ACCOUNT_AND_IP,
                now
        );

        long ipRetry = retryAfterSeconds(
                loginIpFailures,
                normalizeIp(ip),
                LOGIN_MAX_FAILURES_PER_IP,
                now
        );

        long retry = Math.max(accountRetry, ipRetry);

        if (retry > 0) {
            throw new RateLimitExceededException(
                    "Demasiados intentos de inicio de sesión. Inténtalo de nuevo más tarde.",
                    retry
            );
        }
    }

    public void recordLoginFailure(String ip, String email) {

        Instant now = clock.instant();

        increment(
                loginAccountFailures,
                loginAccountKey(ip, email),
                LOGIN_WINDOW,
                now
        );

        increment(
                loginIpFailures,
                normalizeIp(ip),
                LOGIN_WINDOW,
                now
        );
    }

    public void recordLoginSuccess(String ip, String email) {

        /*
         * Un login correcto limpia el contador de esa cuenta concreta.
         *
         * El contador global por IP se conserva para impedir que un atacante
         * utilice una cuenta válida para reiniciar un ataque contra otras.
         */
        loginAccountFailures.remove(
                loginAccountKey(ip, email)
        );
    }

    public void consumeRegisterAttempt(String ip) {

        Instant now = clock.instant();
        String key = normalizeIp(ip);

        long retry = retryAfterSeconds(
                registerAttempts,
                key,
                REGISTER_MAX_ATTEMPTS_PER_IP,
                now
        );

        if (retry > 0) {
            throw new RateLimitExceededException(
                    "Demasiados intentos de registro. Inténtalo de nuevo más tarde.",
                    retry
            );
        }

        increment(
                registerAttempts,
                key,
                REGISTER_WINDOW,
                now
        );
    }

    private void increment(
            ConcurrentHashMap<String, AttemptWindow> map,
            String key,
            Duration window,
            Instant now) {

        map.compute(key, (ignored, current) -> {

            if (
                    current == null ||
                    !now.isBefore(current.resetAt())
            ) {
                return new AttemptWindow(
                        1,
                        now.plus(window)
                );
            }

            return new AttemptWindow(
                    current.count() + 1,
                    current.resetAt()
            );
        });

        cleanupExpired(map, now);
    }

    private long retryAfterSeconds(
            ConcurrentHashMap<String, AttemptWindow> map,
            String key,
            int limit,
            Instant now) {

        AttemptWindow current = map.get(key);

        if (current == null) {
            return 0;
        }

        if (!now.isBefore(current.resetAt())) {
            map.remove(key, current);
            return 0;
        }

        if (current.count() < limit) {
            return 0;
        }

        return Math.max(
                1,
                Duration.between(
                        now,
                        current.resetAt()
                ).getSeconds()
        );
    }

    private void cleanupExpired(
            ConcurrentHashMap<String, AttemptWindow> map,
            Instant now) {

        /*
         * Evita que el mapa crezca indefinidamente.
         * Para esta fase basta una limpieza oportunista.
         */
        if (map.size() < 1_000) {
            return;
        }

        map.entrySet().removeIf(
                entry ->
                        !now.isBefore(
                                entry.getValue().resetAt()
                        )
        );
    }

    private String loginAccountKey(
            String ip,
            String email) {

        String normalizedEmail =
                email == null
                        ? ""
                        : email.trim()
                                .toLowerCase(Locale.ROOT);

        return normalizeIp(ip)
                + "|"
                + normalizedEmail;
    }

    private String normalizeIp(String ip) {
        return ip == null || ip.isBlank()
                ? "unknown"
                : ip.trim();
    }

    private record AttemptWindow(
            int count,
            Instant resetAt) {
    }
}