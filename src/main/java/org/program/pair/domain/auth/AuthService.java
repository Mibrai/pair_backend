package org.program.pair.domain.auth;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.LogoutRequest;
import org.program.pair.domain.auth.session.SessionService;
import org.program.pair.domain.notification.DeviceTokenService;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.shared.exception.EmailAlreadyExistsException;
import org.program.pair.shared.exception.InvalidCredentialsException;
import org.program.pair.shared.exception.InvalidTokenException;
import org.program.pair.shared.exception.UserNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final EmailVerificationService emailVerificationService;
    private final SessionService sessionService;
    private final DeviceTokenService deviceTokenService;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email().toLowerCase())) {
            throw new EmailAlreadyExistsException("Cet email est déjà utilisé.");
        }

        User user = new User();
        user.setEmail(request.email().toLowerCase().strip());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().strip());
        user.setVerificationStatus(VerificationStatus.UNVERIFIED);
        user.setIsActive(true);
        user = userRepository.save(user);

        emailVerificationService.sendVerificationEmail(user);

        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().toLowerCase())
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .orElseThrow(() -> new InvalidCredentialsException("Identifiants invalides."));

        String hash = user.getPasswordHash();
        if (hash == null) {
            throw new InvalidCredentialsException("Identifiants invalides.");
        }
        hash = hash.strip();
        if (hash.length() < 60 || !hash.startsWith("$2")) {
            throw new InvalidCredentialsException("Identifiants invalides.");
        }
        if (!hash.equals(user.getPasswordHash())) {
            user.setPasswordHash(hash);
            userRepository.save(user);
        }

        if (!passwordEncoder.matches(request.password(), hash)) {
            throw new InvalidCredentialsException("Identifiants invalides.");
        }

        user.setLastActiveAt(Instant.now());
        userRepository.save(user);

        return buildAuthResponse(user);
    }

    /**
     * Réémet une session entière contre un jeton de rafraîchissement.
     *
     * <p><b>La réémission est glissante, et c'est délibéré.</b> Le nouveau jeton
     * de rafraîchissement vaut trente jours à compter de <i>son</i> émission, et
     * non de celle de son prédécesseur : {@code generateRefreshToken} part de
     * {@code System.currentTimeMillis()} et ne lit aucune échéance antérieure —
     * il ne le pourrait pas, rien n'est persisté pour ces jetons. Une session
     * utilisée au moins une fois par mois ne finit donc jamais, ce que le client
     * nous demandait de confirmer.
     *
     * <p><b>Trois refus, et pourquoi ils rendent tous le même.</b>
     * <ul>
     *   <li>Le jeton ne se valide pas (signature, échéance) : c'était déjà le
     *       cas.</li>
     *   <li>Le jeton se valide mais n'est <b>pas</b> un jeton de
     *       rafraîchissement. Un jeton d'accès était accepté ici et rendait une
     *       session complète : le claim {@code type} n'était lu nulle part. Le
     *       filtre ferme la même faille dans l'autre sens.</li>
     *   <li>Le compte n'ouvre plus — désactivé, ou disparu. {@code login} filtre
     *       depuis toujours sur {@code isActive} ; cette route chargeait par
     *       identifiant et émettait sans rien vérifier, si bien qu'un compte
     *       désactivé renouvelait sa session indéfiniment. Il n'y perdait que la
     *       possibilité de se reconnecter, ce qu'il n'avait aucune raison de
     *       faire.</li>
     * </ul>
     *
     * <p>Un compte introuvable rendait auparavant un 404. C'est le seul
     * changement de code de statut de ce lot, et il est volontaire : le client
     * ne ferme une session que sur 401 ou 403, et un compte effacé — la
     * suppression RGPD en efface réellement la ligne — laissait donc l'app
     * réessayer sans fin avec un jeton que rien ne ranimera. « Cette session est
     * finie » est exactement ce qu'il faut lui dire, et {@code INVALID_TOKEN}
     * est ce qu'elle sait déjà lire ici. Les trois refus se confondent aussi
     * pour ne rien apprendre à qui présenterait un jeton qui n'est pas le sien.
     */
    /**
     * Échange un jeton de rafraîchissement (P-BS-03).
     *
     * <p>{@code noRollbackFor} ici aussi, et c'est lui qui compte : cette
     * transaction englobe celle de {@link SessionService#echanger}. Un rejeu y
     * révoque la session puis refuse ; sans cette ligne, le refus remontant
     * jusqu'ici annulerait la révocation, et le voleur garderait la session.
     */
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public AuthResponse refreshToken(String refreshToken) {
        // Chaque jeton échangé est une ligne, et la rotation tolérante de P-BS/D4
        // vit dans SessionService (P-BS-03).
        SessionService.Jetons jetons = sessionService.echanger(refreshToken);
        User user = userRepository.findById(tokenProvider.extractUserId(jetons.acces()))
            .orElseThrow(() -> new InvalidTokenException("Refresh token invalide ou expiré."));
        return buildAuthResponse(user, jetons);
    }

    public ResultatVerification verifierEmailPourNavigateur(String token) {
        return emailVerificationService.verifier(token);
    }

    /**
     * Renvoie un lien de vérification si — et seulement si — l'adresse
     * correspond à un compte actif qui n'est pas déjà vérifié. Ne signale rien
     * dans les autres cas : l'appelant répond 200 quoi qu'il arrive.
     */
    public void resendVerificationEmail(String email) {
        userRepository.findByEmail(email.toLowerCase().strip())
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .filter(u -> u.getVerificationStatus() == VerificationStatus.UNVERIFIED)
            .ifPresent(emailVerificationService::sendVerificationEmail);
    }

    public void sendPasswordResetEmail(String email) {
        // Toujours répondre 200 même si l'email n'existe pas (éviter l'énumération)
        userRepository.findByEmail(email.toLowerCase().strip())
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .ifPresent(emailVerificationService::generatePasswordResetToken);
    }

    public void resetPassword(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new ValidationException("Le mot de passe doit contenir au moins 8 caractères.");
        }

        UUID userId = emailVerificationService.validatePasswordResetToken(token)
            .orElseThrow(() -> new InvalidTokenException("Token de réinitialisation invalide ou expiré."));

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        emailVerificationService.consumePasswordResetToken(token);

        // Une réinitialisation coupe TOUTES les sessions, et les jetons d'accès en
        // cours avec (P-BS-03) : c'est le geste de quelqu'un qui croit son compte
        // compromis.
        sessionService.revoquerToutes(userId, null, SessionService.MOTIF_MDP_REINITIALISE);
    }

    /**
     * Ferme la session du jeton de rafraîchissement présenté, et détache le jeton
     * de notification de l'appareil (P-BS-03, P-BS-10, P-BL-12 étape 3).
     *
     * <p>Ne lève jamais : un jeton inconnu, illisible ou déjà révoqué ne change
     * rien à la réponse. Le jeton d'appareil n'est détaché que si le jeton de
     * rafraîchissement a désigné son propriétaire — sans quoi n'importe qui
     * détacherait l'appareil de n'importe qui en connaissant son jeton.
     */
    public void logout(LogoutRequest request) {
        if (request == null) {
            return;
        }
        sessionService.fermer(request.refreshToken()).ifPresent(userId -> {
            if (request.deviceToken() != null && !request.deviceToken().isBlank()) {
                deviceTokenService.unregisterToken(userId, request.deviceToken());
            }
        });
    }

    private AuthResponse buildAuthResponse(User user) {
        return buildAuthResponse(user, sessionService.ouvrir(user));
    }

    private AuthResponse buildAuthResponse(User user, SessionService.Jetons jetons) {
        return new AuthResponse(
            jetons.acces(),
            jetons.rafraichissement(),
            user.getId(),
            user.getDisplayName(),
            user.getVerificationStatus().name(),
            tokenProvider.accessTokenExpirySeconds(),
            tokenProvider.refreshTokenExpirySeconds()
        );
    }
}
