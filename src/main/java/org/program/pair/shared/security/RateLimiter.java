package org.program.pair.shared.security;

import org.program.pair.shared.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimiter {

    private final Map<String, AtomicInteger> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockouts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> registerAttempts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> passwordResetAttempts = new ConcurrentHashMap<>();

    public void checkLogin(String ip) {
        if (isLockedOut(ip, lockouts)) {
            throw new TooManyRequestsException("Trop de tentatives. Réessayez dans 15 minutes.");
        }
        int attempts = loginAttempts.computeIfAbsent(ip, k -> new AtomicInteger(0))
                                    .incrementAndGet();
        if (attempts >= 10) {
            lockouts.put(ip, Instant.now().plusSeconds(900));
            loginAttempts.remove(ip);
        }
    }

    public void checkRegister(String ip) {
        int attempts = registerAttempts.computeIfAbsent(ip, k -> new AtomicInteger(0))
                                      .incrementAndGet();
        if (attempts > 5) {
            throw new TooManyRequestsException("Trop d'inscriptions. Réessayez dans 1 heure.");
        }
    }

    public void checkPasswordReset(String ip) {
        int attempts = passwordResetAttempts.computeIfAbsent(ip, k -> new AtomicInteger(0))
                                           .incrementAndGet();
        if (attempts > 3) {
            throw new TooManyRequestsException("Trop de demandes. Réessayez dans 1 heure.");
        }
    }

    private boolean isLockedOut(String ip, Map<String, Instant> lockoutMap) {
        Instant lockUntil = lockoutMap.get(ip);
        if (lockUntil == null) return false;
        if (Instant.now().isAfter(lockUntil)) {
            lockoutMap.remove(ip);
            return false;
        }
        return true;
    }
}
