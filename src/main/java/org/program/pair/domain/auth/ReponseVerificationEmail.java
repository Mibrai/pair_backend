package org.program.pair.domain.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * La réponse à un jeton de vérification, quel que soit le chemin par lequel on
 * y arrive.
 *
 * <p>Il y en a deux : {@code /api/auth/verify-email?token=…}, historique, que
 * portent les liens déjà partis et le contrat de l'application ; et
 * {@code /v/{token}}, court, déclaré dans le fichier d'association Apple et
 * donc le seul que le système remette à l'application. Les deux doivent se
 * comporter <b>exactement</b> pareil : quatre états, même arbitrage sur
 * {@code Accept}. Ce comportement vit ici plutôt que dupliqué dans deux
 * contrôleurs, parce qu'une divergence entre les deux chemins ne se verrait
 * qu'en production, sur un appareil, un jeton à la fois.
 */
@Component
@RequiredArgsConstructor
public class ReponseVerificationEmail {

    private final AuthService authService;
    private final TemplateEngine templateEngine;

    /**
     * <p>On distingue sur l'en-tête {@code Accept}, explicitement plutôt que par
     * la négociation de contenu de Spring : avec deux gestionnaires sur le même
     * chemin, un {@code Accept: *&#47;*} — ce qu'envoient beaucoup de clients —
     * deviendrait ambigu, et l'arbitrage se ferait sans nous.
     *
     * @param accept l'en-tête reçu, {@code null} accepté
     */
    public ResponseEntity<?> repondre(String token, String accept) {
        if (accept != null && accept.contains(MediaType.TEXT_HTML_VALUE)) {
            ResultatVerification etat = authService.verifierEmailPourNavigateur(token);
            Context contexte = new Context();
            contexte.setVariable("etat", etat);
            // 200 dans les quatre cas : la page dit elle-même ce qui s'est
            // passé, et un code d'erreur exposerait le message à être remplacé
            // par la page d'erreur d'un intermédiaire — c'est-à-dire à ne jamais
            // atteindre la personne à qui il est destiné.
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(templateEngine.process("verify-email", contexte));
        }

        authService.verifyEmail(token);
        return ResponseEntity.ok().build();
    }
}
