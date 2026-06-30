package org.program.pair.domain.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.shared.email.EmailService;
import org.program.pair.shared.exception.InvalidTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final UserRepository userRepository;
    private final EmailService emailService;

    private final Map<String, UUID> verificationTokens = new ConcurrentHashMap<>();
    private final Map<String, Instant> verificationExpiry = new ConcurrentHashMap<>();

    private final Map<String, UUID> passwordResetTokens = new ConcurrentHashMap<>();
    private final Map<String, Instant> passwordResetExpiry = new ConcurrentHashMap<>();

    public void sendVerificationEmail(User user) {
        String token = UUID.randomUUID().toString();
        verificationTokens.put(token, user.getId());
        verificationExpiry.put(token, Instant.now().plusSeconds(86400)); // 24h

        log.info("Email verification token for {}: {}", user.getEmail(), token);
        emailService.sendVerificationEmail(user.getEmail(), token);
    }

    @Transactional
    public void verifyToken(String token) {
        UUID userId = verificationTokens.get(token);
        if (userId == null) {
            throw new InvalidTokenException("Token de vérification invalide.");
        }

        Instant expiry = verificationExpiry.get(token);
        if (expiry == null || Instant.now().isAfter(expiry)) {
            verificationTokens.remove(token);
            verificationExpiry.remove(token);
            throw new InvalidTokenException("Token de vérification expiré.");
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new InvalidTokenException("Utilisateur introuvable."));

        user.setVerificationStatus(VerificationStatus.EMAIL_VERIFIED);
        user.setVerifiedAt(Instant.now());
        userRepository.save(user);

        verificationTokens.remove(token);
        verificationExpiry.remove(token);
    }

    public String generatePasswordResetToken(User user) {
        String token = UUID.randomUUID().toString();
        passwordResetTokens.put(token, user.getId());
        passwordResetExpiry.put(token, Instant.now().plusSeconds(1800)); // 30 min

        emailService.sendPasswordResetEmail(user.getEmail(), token);
        return token;
    }

    public Optional<UUID> validatePasswordResetToken(String token) {
        UUID userId = passwordResetTokens.get(token);
        if (userId == null) return Optional.empty();

        Instant expiry = passwordResetExpiry.get(token);
        if (expiry == null || Instant.now().isAfter(expiry)) {
            passwordResetTokens.remove(token);
            passwordResetExpiry.remove(token);
            return Optional.empty();
        }

        return Optional.of(userId);
    }

    public void consumePasswordResetToken(String token) {
        passwordResetTokens.remove(token);
        passwordResetExpiry.remove(token);
    }
}
