package org.program.pair.domain.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.AuthTokenRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.email.EmailService;
import org.program.pair.shared.exception.InvalidTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Émission et validation des jetons envoyés par e-mail.
 *
 * <p>Les jetons étaient conservés dans quatre {@code ConcurrentHashMap}
 * d'instance. Cela tenait tant qu'on ne redéployait pas entre l'inscription et
 * le clic — c'est-à-dire tant qu'on ne s'en servait qu'en développement. En
 * production, chaque déploiement invalidait silencieusement tous les liens en
 * circulation. Ils sont désormais en base (V79).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final Duration VALIDITE_VERIFICATION = Duration.ofHours(24);
    private static final Duration VALIDITE_REINITIALISATION = Duration.ofMinutes(30);

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final EmailService emailService;

    @Transactional
    public void sendVerificationEmail(User user) {
        String token = emettre(user, AuthTokenType.EMAIL_VERIFICATION, VALIDITE_VERIFICATION);
        emailService.sendVerificationEmail(user.getEmail(), token);
    }

    /**
     * Vérifie un jeton et rend l'issue, sans lever d'exception.
     *
     * <p>C'est la forme dont la page HTML a besoin : elle doit dire quelque
     * chose de différent dans chacun des quatre cas, et une exception ne
     * transporte pas cette nuance.
     */
    @Transactional
    public ResultatVerification verifier(String token) {
        Optional<AuthToken> trouve =
            authTokenRepository.findByTokenAndType(token, AuthTokenType.EMAIL_VERIFICATION);

        if (trouve.isEmpty()) {
            return ResultatVerification.INCONNU;
        }

        AuthToken jeton = trouve.get();
        if (jeton.estConsomme()) {
            return ResultatVerification.DEJA_VERIFIE;
        }
        if (jeton.estExpire()) {
            return ResultatVerification.EXPIRE;
        }

        User user = jeton.getUser();
        user.setVerificationStatus(VerificationStatus.EMAIL_VERIFIED);
        user.setVerifiedAt(Instant.now());
        userRepository.save(user);

        jeton.setConsumedAt(Instant.now());
        authTokenRepository.save(jeton);

        log.info("Adresse vérifiée pour l'utilisateur {}", user.getId());
        return ResultatVerification.VERIFIE;
    }

    /**
     * Variante levant une exception, pour les appelants qui attendent un
     * contrat JSON binaire (l'app mobile, qui appelle la route en Accept: JSON).
     */
    @Transactional
    public void verifyToken(String token) {
        ResultatVerification resultat = verifier(token);
        switch (resultat) {
            case VERIFIE, DEJA_VERIFIE -> { }
            case EXPIRE -> throw new InvalidTokenException("Token de vérification expiré.");
            case INCONNU -> throw new InvalidTokenException("Token de vérification invalide.");
        }
    }

    @Transactional
    public String generatePasswordResetToken(User user) {
        String token = emettre(user, AuthTokenType.PASSWORD_RESET, VALIDITE_REINITIALISATION);
        emailService.sendPasswordResetEmail(user.getEmail(), token);
        return token;
    }

    @Transactional(readOnly = true)
    public Optional<UUID> validatePasswordResetToken(String token) {
        return authTokenRepository.findByTokenAndType(token, AuthTokenType.PASSWORD_RESET)
            .filter(jeton -> !jeton.estConsomme())
            .filter(jeton -> !jeton.estExpire())
            .map(jeton -> jeton.getUser().getId());
    }

    @Transactional
    public void consumePasswordResetToken(String token) {
        authTokenRepository.findByTokenAndType(token, AuthTokenType.PASSWORD_RESET)
            .filter(jeton -> !jeton.estConsomme())
            .ifPresent(jeton -> {
                jeton.setConsumedAt(Instant.now());
                authTokenRepository.save(jeton);
            });
    }

    /**
     * Émet un jeton et clôt ceux du même usage restés ouverts.
     *
     * <p>Fermer les précédents évite qu'un renvoi laisse plusieurs liens actifs
     * pour la même adresse : l'utilisateur, qui a deux e-mails sous les yeux,
     * n'a aucun moyen de savoir lequel porte le bon.
     */
    private String emettre(User user, AuthTokenType type, Duration validite) {
        authTokenRepository.consommerJetonsOuverts(user.getId(), type, Instant.now());

        String token = UUID.randomUUID().toString();
        authTokenRepository.save(AuthToken.builder()
            .token(token)
            .user(user)
            .type(type)
            .expiresAt(Instant.now().plus(validite))
            .build());
        return token;
    }
}
