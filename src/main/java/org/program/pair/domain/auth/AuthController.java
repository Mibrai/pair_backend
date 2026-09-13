package org.program.pair.domain.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.auth.dto.ResendVerificationRequest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.ForgotPasswordRequest;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RefreshRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.auth.dto.ResetPasswordRequest;
import org.program.pair.shared.exception.InvalidCredentialsException;
import org.program.pair.shared.security.RateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;
    private final RateLimiter rateLimiter;
    private final ReponseVerificationEmail reponseVerification;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request,
                                  HttpServletRequest httpRequest) {
        rateLimiter.checkRegister(adresseAppelante(httpRequest), request.email());
        return authService.register(request);
    }

    /**
     * Connexion.
     *
     * <p><b>Le plafond porte sur les échecs, pas sur les appels.</b> La
     * vérification ne consomme rien ; c'est l'issue qui décide. Une session de
     * travail à deux comptes depuis un même poste — le cas qui a bloqué le
     * chantier mobile le 01/09 — ne consomme donc plus rien du tout, tant que les
     * mots de passe sont bons. Et réessayer après un refus ne rallonge pas
     * l'attente, ce que l'ancien compteur faisait sans qu'aucun écran ne puisse
     * l'expliquer.
     *
     * <p><b>Et le plafond serré porte sur le couple (compte, adresse)</b> depuis
     * le 12/09 : il portait sur le compte seul, si bien que dix mots de passe
     * faux suffisaient à fermer la porte au propriétaire, depuis chez lui et avec
     * le bon mot de passe. C'est l'adresse d'où l'on échoue qui se ferme
     * désormais — voir {@link RateLimiter}, qui garde par-dessus un plafond de
     * compte très large contre le balayage distribué.
     */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest httpRequest) {
        String ip = adresseAppelante(httpRequest);
        rateLimiter.checkLogin(ip, request.email());
        try {
            AuthResponse response = authService.login(request);
            rateLimiter.recordLoginSuccess(ip, request.email());
            return response;
        } catch (InvalidCredentialsException e) {
            // Seul un identifiant refusé consomme du budget — mot de passe faux,
            // compte inconnu ou désactivé, qui rendent tous ce même refus
            // indifférencié. Une panne de base ou une validation ratée lèvent
            // autre chose et ne rapprochent personne du plafond : ce n'est pas
            // une tentative de deviner un mot de passe.
            rateLimiter.recordLoginFailure(ip, request.email());
            throw e;
        }
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refreshToken(request.refreshToken());
    }

    /**
     * Cible historique du lien envoyé par e-mail, et route d'API de
     * l'application mobile.
     *
     * <p>Les deux appelants ne veulent pas la même chose. L'app attend un
     * contrat JSON binaire, inchangé ici. Un navigateur, lui, affichait
     * jusqu'ici {@code {"message":...}} en pleine page — un testeur qui voit ça
     * conclut que la vérification a échoué, alors qu'elle vient de réussir.
     * L'arbitrage vit dans {@link ReponseVerificationEmail}, partagé avec le
     * chemin court {@code /v/{token}}.
     *
     * <p><b>Cette route reste servie</b> bien que les e-mails partent désormais
     * sur {@code /v/{token}} : les liens déjà en circulation la portent, et ils
     * valent 24 heures.
     */
    @GetMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(
            @RequestParam String token,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        return reponseVerification.repondre(token, accept);
    }

    /**
     * Renvoi d'un lien de vérification.
     *
     * <p>La page « lien expiré » demande à l'utilisateur d'en redemander un ;
     * encore faut-il que ce soit possible. Répond toujours 200, y compris pour
     * une adresse inconnue ou déjà vérifiée, comme {@code /forgot-password} :
     * un code distinct dirait à qui essaie des adresses lesquelles sont
     * inscrites.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request,
            HttpServletRequest httpRequest) {
        rateLimiter.checkResendVerification(adresseAppelante(httpRequest), request.email());
        authService.resendVerificationEmail(request.email());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        rateLimiter.checkPasswordReset(adresseAppelante(httpRequest), request.email());
        authService.sendPasswordResetEmail(request.email());
        // Toujours 200 même si l'email n'existe pas (éviter l'énumération)
        return ResponseEntity.ok().build();
    }

    /**
     * Consommation du lien de réinitialisation.
     *
     * <p><b>Bornée depuis le 12/09</b>, et elle ne l'était pas. Le jeton fait
     * 122 bits, donc personne ne le devine ; ce n'est pas le risque. Ce qui
     * manquait, c'est une borne sur le <b>coût</b> : chaque appel cherche le
     * jeton en base puis hache un mot de passe avec BCrypt, et une boucle sur
     * cette route prenait autant de processeur qu'elle voulait, au détriment de
     * toutes les autres requêtes. Vingt par heure et par connexion — assez large
     * pour qui recommence parce que son nouveau mot de passe est refusé par les
     * règles de forme.
     *
     * <p>Le plafond est par adresse seule : l'appelant présente un jeton, pas une
     * adresse e-mail, et le compte visé n'est donc pas connaissable avant d'avoir
     * fait le travail qu'on veut borner.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                              HttpServletRequest httpRequest) {
        rateLimiter.checkResetPasswordAttempt(adresseAppelante(httpRequest));
        authService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        // For JWT-based authentication, logout is primarily handled client-side
        // by removing the token. This endpoint can be used for:
        // - Logging logout events
        // - Token blacklisting (if implemented)
        // - Session cleanup (if needed)
        authService.logout(httpRequest);
        return ResponseEntity.ok().build();
    }

    /**
     * L'adresse à laquelle les plafonds de ce contrôleur sont comptés.
     *
     * <p>Un seul endroit, et ce n'est pas de la cosmétique : quatre appels
     * lisaient {@code getRemoteAddr()} séparément, si bien que la question « quelle
     * adresse croyons-nous voir ? » n'avait nulle part où être écrite. Elle est
     * pourtant le point (a) de la fiche P-BS-08, et la réponse dépend d'une
     * configuration de déploiement, pas de ce code.
     *
     * <p><b>Ce que rend cette méthode aujourd'hui.</b> En local, l'adresse du
     * pair TCP, qui est la bonne. Sur Railway, {@code application-railway.properties}
     * pose {@code server.forward-headers-strategy=framework} : le
     * {@code ForwardedHeaderFilter} de Spring réécrit alors {@code getRemoteAddr()}
     * avec la <b>première</b> valeur de {@code X-Forwarded-For}, <b>sans vérifier
     * de qui elle vient</b>. Si l'arête Railway ajoute au lieu de remplacer, cette
     * première valeur est celle que le client a annoncée : un en-tête différent à
     * chaque requête et tous les plafonds par adresse de ce contrôleur deviennent
     * gratuits. Le budget par couple (compte, adresse) du limiteur en dépend
     * directement.
     *
     * <p><b>Ce qui manque pour le corriger</b> : le relevé d'exploitation décrit à
     * l'étape 1 de la fiche — une requête de diagnostic en production, journaux
     * lus, qui dit si l'arête ajoute ou remplace et depuis quelles plages elle
     * parle. Il n'a pas été fait. Le remède est prêt et inactif dans
     * {@code config/ProxyDeConfiance} : il n'attend que ces plages, et sa javadoc
     * dit exactement quelles lignes poser. Deviner les plages serait pire que de
     * ne rien poser — une plage fausse fait voir toutes les requêtes comme venant
     * du proxy, et le plafond par adresse devient commun à tout le monde.
     */
    private static String adresseAppelante(HttpServletRequest httpRequest) {
        return httpRequest.getRemoteAddr();
    }
}
