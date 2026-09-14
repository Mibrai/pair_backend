package org.program.pair.domain.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.program.pair.shared.exception.InvalidTokenException;
import org.program.pair.shared.exception.TooManyRequestsException;
import org.program.pair.shared.security.RateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.regex.Pattern;

/**
 * La page du lien « mot de passe oublié » — qui n'existait pas.
 *
 * <p>{@code EmailService} composait {@code /reset-password?token=…} depuis
 * l'origine, et aucun contrôleur ne servait ce chemin : il retombait sur
 * {@code anyRequest().authenticated()} et rendait
 * {@code 401 UNAUTHORIZED} à qui venait de demander un nouveau mot de passe.
 * Personne ne pouvait donc réinitialiser le sien depuis l'e-mail, ni dans
 * l'application — le fichier d'association ne déclarait pas le chemin — ni dans
 * un navigateur. Signalé par le chantier mobile le 14/09/2026.
 *
 * <p><b>Deux chemins, un comportement.</b> {@code /r/{token}} est le lien court
 * que portent désormais les e-mails, sur le modèle de {@code /v/{token}} :
 * déclaré dans le fichier d'association Apple, lisible, et qui survit aux
 * messageries qui tronquent. {@code /reset-password?token=} reste servi pour
 * les e-mails déjà partis.
 *
 * <p><b>Le formulaire est posté ici, pas sur {@code /api/auth/reset-password}.</b>
 * Cette route attend du JSON, qu'un formulaire HTML ne sait pas envoyer sans
 * script ; et un script qui la viserait dépendrait de la politique CORS, fermée
 * depuis le 13/09. Le formulaire classique fonctionne sans JavaScript, dans le
 * navigateur intégré d'une messagerie comme sur un poste de bureau, et aboutit
 * au même {@link AuthService#resetPassword} — mêmes règles, même plafond, mêmes
 * sessions coupées.
 *
 * <p><b>Le jeton ne sort pas de cette page.</b> Chaque réponse porte
 * {@code Referrer-Policy: no-referrer} — le pied de page renvoie vers
 * meetdo.fun, et sans cet en-tête l'adresse complète, jeton compris, partirait
 * dans le {@code Referer} de ce clic — et {@code Cache-Control: no-store}, pour
 * qu'aucun cache intermédiaire ne garde une page porteuse du jeton. Rien n'est
 * journalisé ici.
 *
 * <p>Toujours du HTML, jamais du JSON : la route n'a pas de client applicatif,
 * l'application interceptant le lien avant que la requête ne parte.
 */
@Controller
@RequiredArgsConstructor
public class ReinitialisationLinkController {

    private static final String VUE = "reset-password";

    /** Même borne que {@code ResetPasswordRequest}. */
    private static final int LONGUEUR_MIN = 8;
    private static final int LONGUEUR_MAX = 100;

    /**
     * Une forme d'adresse, pas une validation : la demande répond la même chose
     * qu'un compte existe ou non, et ce filtre ne sert qu'à signaler une faute de
     * frappe évidente plutôt que de promettre un e-mail qui ne partira pas.
     */
    private static final Pattern ADRESSE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final EmailVerificationService jetons;
    private final AuthService authService;
    private final RateLimiter rateLimiter;

    /** Posé avant chaque gestionnaire de ce contrôleur, erreurs comprises. */
    @ModelAttribute
    void protegerLeJeton(HttpServletResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @GetMapping("/r/{token}")
    public String afficher(@PathVariable String token, Model model) {
        return page(token, model);
    }

    /** Le chemin des e-mails envoyés avant le 14/09 — valables 30 minutes, mais ouverts parfois bien plus tard. */
    @GetMapping("/reset-password")
    public String afficherAncienLien(@RequestParam(required = false) String token, Model model) {
        return page(token, model);
    }

    @PostMapping("/r/{token}")
    public String reinitialiser(
            @PathVariable String token,
            @RequestParam(required = false) String motDePasse,
            @RequestParam(required = false) String confirmation,
            HttpServletRequest request, HttpServletResponse response, Model model) {
        try {
            rateLimiter.checkResetPasswordAttempt(request.getRemoteAddr());
        } catch (TooManyRequestsException e) {
            return tropDeTentatives(e, response, model);
        }

        // Lu avant de valider la saisie : à quelqu'un dont le lien a expiré,
        // signaler d'abord que les deux mots de passe diffèrent lui ferait
        // corriger un formulaire qui ne pourra jamais aboutir.
        EtatReinitialisation etat = jetons.etatReinitialisation(token);
        if (etat != EtatReinitialisation.VALIDE) {
            model.addAttribute("etat", etat.name());
            return VUE;
        }

        String erreur = erreurDeSaisie(motDePasse, confirmation);
        if (erreur != null) {
            model.addAttribute("etat", "VALIDE");
            model.addAttribute("token", token);
            model.addAttribute("erreur", erreur);
            return VUE;
        }

        try {
            authService.resetPassword(token, motDePasse);
        } catch (InvalidTokenException e) {
            // Consommé ou échu entre la lecture ci-dessus et l'écriture : un
            // double envoi du formulaire, le plus souvent. On dit ce qu'il en est
            // maintenant plutôt qu'une erreur générique.
            model.addAttribute("etat", jetons.etatReinitialisation(token).name());
            return VUE;
        }
        model.addAttribute("etat", "REUSSI");
        return VUE;
    }

    /**
     * Redemander un lien depuis la page — le « mot de passe oublié » vers lequel
     * renvoie un lien expiré ou déjà utilisé.
     *
     * <p>Même réponse qu'un compte existe ou non, comme
     * {@code /api/auth/forgot-password}, et même plafond.
     */
    @PostMapping("/r")
    public String redemander(
            @RequestParam(required = false) String email,
            HttpServletRequest request, HttpServletResponse response, Model model) {
        String adresse = email == null ? "" : email.strip();
        if (adresse.length() > 254 || !ADRESSE.matcher(adresse).matches()) {
            model.addAttribute("etat", "DEMANDE");
            model.addAttribute("erreur", "Cette adresse e-mail ne semble pas complète.");
            return VUE;
        }
        try {
            rateLimiter.checkPasswordReset(request.getRemoteAddr(), adresse);
        } catch (TooManyRequestsException e) {
            return tropDeTentatives(e, response, model);
        }
        authService.sendPasswordResetEmail(adresse);
        model.addAttribute("etat", "LIEN_ENVOYE");
        return VUE;
    }

    private String page(String token, Model model) {
        EtatReinitialisation etat = jetons.etatReinitialisation(token);
        model.addAttribute("etat", etat.name());
        // Le jeton n'entre dans la page que là où il sert : l'adresse du
        // formulaire. Sur une page d'échec, il n'a plus rien à y faire.
        if (etat == EtatReinitialisation.VALIDE) {
            model.addAttribute("token", token);
        }
        // 200 dans tous les cas, comme la vérification : un code d'erreur
        // exposerait la page à être remplacée par celle d'un intermédiaire.
        return VUE;
    }

    private static String erreurDeSaisie(String motDePasse, String confirmation) {
        if (motDePasse == null || motDePasse.length() < LONGUEUR_MIN) {
            return "Le mot de passe doit contenir au moins " + LONGUEUR_MIN + " caractères.";
        }
        if (motDePasse.length() > LONGUEUR_MAX) {
            return "Le mot de passe doit contenir au plus " + LONGUEUR_MAX + " caractères.";
        }
        if (!motDePasse.equals(confirmation)) {
            return "Les deux mots de passe ne sont pas identiques.";
        }
        return null;
    }

    /**
     * 429 et {@code Retry-After}, comme l'API — mais en page : c'est un
     * navigateur qui attend la réponse.
     */
    private static String tropDeTentatives(TooManyRequestsException e,
                                           HttpServletResponse response, Model model) {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSecondes()));
        model.addAttribute("etat", "TROP_DE_TENTATIVES");
        return VUE;
    }
}
