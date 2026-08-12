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

    /**
     * Remet tous les compteurs à zéro.
     *
     * <p>Réservé aux tests. Les compteurs d'inscription n'ont pas de fenêtre
     * glissante : ils s'accumulent pour la durée de vie du composant. Or ce
     * composant est un singleton du contexte Spring, partagé par toutes les
     * méthodes d'une classe de test d'intégration — la sixième inscription de la
     * classe échoue donc en 429, quelle que soit la méthode qui la demande.
     *
     * <p>Cela rendait les tests intermittents pour une raison sans rapport avec
     * ce qu'ils vérifient : {@code MapVisibilityIntegrationTest} inscrit huit
     * utilisateurs, et c'est l'ordre d'exécution de JUnit — arbitraire, mais
     * stable pour un classpath donné — qui décidait lesquels de ses tests
     * tombaient dans les trois inscriptions refusées.
     *
     * <p>{@code AbstractIntegrationTest} appelle donc cette méthode avant chaque
     * test : chaque méthode part du même budget, et l'ordre n'influe plus.
     */
    public void reset() {
        loginAttempts.clear();
        lockouts.clear();
        registerAttempts.clear();
        passwordResetAttempts.clear();
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
