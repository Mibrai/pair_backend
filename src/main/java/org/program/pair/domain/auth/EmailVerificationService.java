package org.program.pair.domain.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.shared.exception.InvalidTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final UserRepository userRepository;
    private final Map<String, UUID> verificationTokens = new ConcurrentHashMap<>();
    private final Map<String, Instant> tokenExpiry = new ConcurrentHashMap<>();

    public void sendVerificationEmail(User user) {
        String token = UUID.randomUUID().toString();
        verificationTokens.put(token, user.getId());
        tokenExpiry.put(token, Instant.now().plusSeconds(86400));

        log.info("Email verification token for {}: {}", user.getEmail(), token);
        // TODO Phase 2: Envoyer l'email via EmailService
    }

    @Transactional
    public void verifyToken(String token) {
        UUID userId = verificationTokens.get(token);
        if (userId == null) {
            throw new InvalidTokenException("Token de vérification invalide.");
        }

        Instant expiry = tokenExpiry.get(token);
        if (expiry == null || Instant.now().isAfter(expiry)) {
            verificationTokens.remove(token);
            tokenExpiry.remove(token);
            throw new InvalidTokenException("Token de vérification expiré.");
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new InvalidTokenException("Utilisateur introuvable."));

        user.setVerificationStatus(VerificationStatus.EMAIL_VERIFIED);
        user.setVerifiedAt(Instant.now());
        userRepository.save(user);

        verificationTokens.remove(token);
        tokenExpiry.remove(token);
    }
}
