package org.program.pair.domain.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.AuthTokenRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.email.EmailService;
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
        emailService.sendVerificationEmail(user, token);
    }

    /**
     * Demande le passage à une nouvelle adresse : le lien part <b>vers elle</b>,
     * et rien ne bouge sur le compte tant qu'il n'est pas cliqué.
     *
     * <p>L'adresse demandée est posée en attente sur le compte. Elle n'y sert
     * qu'à deux choses : retrouver quoi basculer au clic, et empêcher — par
     * l'index unique de V105 — que deux comptes convoitent la même. Ce n'est pas
     * une garantie suffisante à elle seule, d'où la seconde vérification au clic.
     */
    @Transactional
    public void demanderChangementEmail(User user, String nouvelleAdresse) {
        user.setPendingEmail(nouvelleAdresse);
        userRepository.save(user);
        String token = emettre(user, AuthTokenType.EMAIL_CHANGE, VALIDITE_VERIFICATION);
        emailService.sendEmailChangeEmail(user, nouvelleAdresse, token);
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
        Optional<AuthToken> trouve = retrouver(token, AuthTokenType.EMAIL_VERIFICATION);
        if (trouve.isPresent()) {
            return verifierAdresse(trouve.get());
        }

        // Un lien de changement d'adresse emprunte le même chemin — même route,
        // même page. Le distinguer par l'URL aurait demandé un second chemin
        // dans le fichier d'association Apple, donc une seconde occasion de
        // diverger, pour un lien que l'utilisateur ne lit pas.
        Optional<AuthToken> changement = retrouver(token, AuthTokenType.EMAIL_CHANGE);
        if (changement.isPresent()) {
            return appliquerChangement(changement.get());
        }

        return ResultatVerification.INCONNU;
    }

    private ResultatVerification verifierAdresse(AuthToken jeton) {
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
     * La bascule d'adresse, au clic et pas avant.
     *
     * <p><b>L'unicité est revérifiée ici</b>, et pas seulement à la demande :
     * entre les deux, il s'écoule jusqu'à vingt-quatre heures pendant lesquelles
     * quelqu'un peut avoir inscrit cette adresse pour de bon. L'index unique de
     * V105 ne couvre que les adresses <i>en attente</i> ; celle-ci pourrait être
     * devenue l'adresse ferme d'un autre compte.
     *
     * <p>Le compte devient vérifié par la même occasion : le clic prouve que
     * l'adresse existe et qu'elle reçoit, ce qui est exactement ce que la
     * vérification demande. Faire recommencer un cycle de vérification ensuite
     * ferait redemander à quelqu'un la preuve qu'il vient de donner.
     */
    private ResultatVerification appliquerChangement(AuthToken jeton) {
        if (jeton.estConsomme()) {
            return ResultatVerification.DEJA_VERIFIE;
        }
        if (jeton.estExpire()) {
            return ResultatVerification.EXPIRE;
        }

        User user = jeton.getUser();
        String nouvelle = user.getPendingEmail();
        if (nouvelle == null || nouvelle.isBlank()) {
            // Demande annulée, ou déjà appliquée par un autre jeton.
            return ResultatVerification.DEJA_VERIFIE;
        }
        if (userRepository.existsByEmail(nouvelle)) {
            user.setPendingEmail(null);
            userRepository.save(user);
            jeton.setConsumedAt(Instant.now());
            authTokenRepository.save(jeton);
            log.info("Changement d'adresse abandonné pour {} : adresse prise entre-temps",
                user.getId());
            return ResultatVerification.ADRESSE_INDISPONIBLE;
        }

        user.setEmail(nouvelle);
        user.setPendingEmail(null);
        user.setVerificationStatus(VerificationStatus.EMAIL_VERIFIED);
        user.setVerifiedAt(Instant.now());
        userRepository.save(user);

        jeton.setConsumedAt(Instant.now());
        authTokenRepository.save(jeton);

        log.info("Adresse changée et vérifiée pour l'utilisateur {}", user.getId());
        return ResultatVerification.ADRESSE_CHANGEE;
    }

    @Transactional
    public String generatePasswordResetToken(User user) {
        String token = emettre(user, AuthTokenType.PASSWORD_RESET, VALIDITE_REINITIALISATION);
        emailService.sendPasswordResetEmail(user.getEmail(), token);
        return token;
    }

    @Transactional(readOnly = true)
    public Optional<UUID> validatePasswordResetToken(String token) {
        return retrouver(token, AuthTokenType.PASSWORD_RESET)
            .filter(jeton -> !jeton.estConsomme())
            .filter(jeton -> !jeton.estExpire())
            .map(jeton -> jeton.getUser().getId());
    }

    /**
     * L'état d'un lien de réinitialisation, lu sans rien consommer : c'est ce que
     * la page publique affiche avant que le formulaire ne soit envoyé.
     */
    @Transactional(readOnly = true)
    public EtatReinitialisation etatReinitialisation(String token) {
        return retrouver(token, AuthTokenType.PASSWORD_RESET)
            .map(jeton -> jeton.estConsomme() ? EtatReinitialisation.UTILISE
                : jeton.estExpire() ? EtatReinitialisation.EXPIRE
                : EtatReinitialisation.VALIDE)
            .orElse(EtatReinitialisation.INCONNU);
    }

    @Transactional
    public void consumePasswordResetToken(String token) {
        retrouver(token, AuthTokenType.PASSWORD_RESET)
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
     *
     * <p><b>Deux formes du même jeton, et pour combien de temps (P-BS-09).</b>
     * La ligne porte l'empreinte — la seule par laquelle {@link #retrouver} sait
     * chercher — <b>et</b> encore la valeur en clair. Cette seconde écriture n'a
     * qu'une raison : tant qu'elle a lieu, revenir à la version précédente du
     * code reste sûr, puisque cette version ne cherche que par la valeur. Sans
     * elle, un retour arrière laisserait sans recours tous les liens émis depuis
     * le déploiement. Elle cesse au Lot 4, au moins sept jours plus tard — bien
     * au-delà des 24 h de validité maximale, donc sans qu'aucun lien vivant n'en
     * dépende encore —, et la colonne est supprimée par une migration derrière.
     */
    private String emettre(User user, AuthTokenType type, Duration validite) {
        authTokenRepository.consommerJetonsOuverts(user.getId(), type, Instant.now());

        String token = UUID.randomUUID().toString();
        authTokenRepository.save(AuthToken.builder()
            .token(token)
            .tokenHash(AuthToken.empreinte(token))
            .user(user)
            .type(type)
            .expiresAt(Instant.now().plus(validite))
            .build());
        return token;
    }

    /**
     * Retrouve un jeton présenté par un lien, par son empreinte et jamais par sa
     * valeur.
     *
     * <p>Un seul endroit pour les quatre recherches : c'est ce qui garantit
     * qu'aucune ne puisse retomber sur la colonne en clair par distraction, et
     * c'est le seul endroit à retoucher le jour où l'empreinte change de forme.
     *
     * <p>Une valeur vide ou absente ne se condense pas et ne désigne rien : elle
     * rend « introuvable », comme le faisait la recherche par valeur avant. La
     * route {@code /v/{token}} peut être appelée sans jeton utile — un robot
     * d'aperçu de lien suffit — et cela ne doit pas lever.
     */
    private Optional<AuthToken> retrouver(String jetonPresente, AuthTokenType type) {
        if (jetonPresente == null || jetonPresente.isBlank()) {
            return Optional.empty();
        }
        return authTokenRepository.findByTokenHashAndType(
            AuthToken.empreinte(jetonPresente), type);
    }
}
