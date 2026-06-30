package org.program.pair.shared.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import lombok.RequiredArgsConstructor;
import org.program.pair.shared.exception.TooManyRequestsException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "redis.enabled", havingValue = "true", matchIfMissing = false)
public class RateLimiterService {

    // En mémoire pour les tests (dans un vrai environnement, utiliser Redis avec ProxyManager)
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final String KEY_LOGIN = "pair:rl:login:";
    private static final String KEY_REGISTER = "pair:rl:register:";
    private static final String KEY_SEARCH = "pair:rl:search:";
    private static final String KEY_UPLOAD = "pair:rl:upload:";

    // Login : 10 tentatives par 15 minutes par IP
    public void checkLogin(String ip) {
        Bucket bucket = buckets.computeIfAbsent(KEY_LOGIN + ip, key ->
            Bucket.builder()
                .addLimit(Bandwidth.simple(10, Duration.ofMinutes(15)))
                .build()
        );

        if (!bucket.tryConsume(1)) {
            throw new TooManyRequestsException(
                "Trop de tentatives de connexion. Réessayez dans 15 minutes.");
        }
    }

    // Inscription : 5 par heure par IP
    public void checkRegister(String ip) {
        Bucket bucket = buckets.computeIfAbsent(KEY_REGISTER + ip, key ->
            Bucket.builder()
                .addLimit(Bandwidth.simple(5, Duration.ofHours(1)))
                .build()
        );

        if (!bucket.tryConsume(1)) {
            throw new TooManyRequestsException(
                "Trop d'inscriptions depuis cette adresse IP.");
        }
    }

    // Recherche : 30 par minute par utilisateur (LLM coûte des tokens)
    public void checkSearch(UUID userId) {
        Bucket bucket = buckets.computeIfAbsent(KEY_SEARCH + userId.toString(), key ->
            Bucket.builder()
                .addLimit(Bandwidth.simple(30, Duration.ofMinutes(1)))
                .build()
        );

        if (!bucket.tryConsume(1)) {
            throw new TooManyRequestsException(
                "Trop de recherches. Patientez un moment.");
        }
    }

    // Upload média : 20 par heure par utilisateur
    public void checkMediaUpload(UUID userId) {
        Bucket bucket = buckets.computeIfAbsent(KEY_UPLOAD + userId.toString(), key ->
            Bucket.builder()
                .addLimit(Bandwidth.simple(20, Duration.ofHours(1)))
                .build()
        );

        if (!bucket.tryConsume(1)) {
            throw new TooManyRequestsException(
                "Limite d'upload atteinte pour aujourd'hui.");
        }
    }
}
