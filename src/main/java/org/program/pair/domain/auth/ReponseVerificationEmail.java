package org.program.pair.domain.auth;

import lombok.RequiredArgsConstructor;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.InvalidTokenException;
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
        // Un seul appel, quel que soit le format demandé, et il est calculé
        // AVANT qu'on décide comment le dire. Ce n'était pas le cas : le chemin
        // JSON passait par une variante qui levait, et l'exception annulait la
        // transaction — donc les écritures que la vérification venait de faire.
        // Sans conséquence tant que les seuls refus étaient « expiré » et
        // « inconnu », qui n'écrivent rien ; avec le changement d'adresse (V105),
        // l'abandon d'une demande devenue impossible se défaisait tout seul.
        ResultatVerification etat = authService.verifierEmailPourNavigateur(token);

        if (accept != null && accept.contains(MediaType.TEXT_HTML_VALUE)) {
            Context contexte = new Context();
            contexte.setVariable("etat", etat);
            // 200 dans tous les cas : la page dit elle-même ce qui s'est passé,
            // et un code d'erreur exposerait le message à être remplacé par la
            // page d'erreur d'un intermédiaire — c'est-à-dire à ne jamais
            // atteindre la personne à qui il est destiné.
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(templateEngine.process("verify-email", contexte));
        }

        return reponseJson(etat);
    }

    /**
     * Le contrat JSON de l'application : 200 quand il n'y a rien à faire de
     * plus, un refus nommé sinon.
     *
     * <p>Les exceptions sont levées <b>ici</b>, une fois la transaction du
     * service refermée, et non depuis le service lui-même.
     */
    private ResponseEntity<Void> reponseJson(ResultatVerification etat) {
        return switch (etat) {
            case VERIFIE, DEJA_VERIFIE, ADRESSE_CHANGEE -> ResponseEntity.ok().build();
            case EXPIRE -> throw new InvalidTokenException("Token de vérification expiré.");
            case INCONNU -> throw new InvalidTokenException("Token de vérification invalide.");
            // Le jeton était bon ; c'est l'adresse qui ne l'est plus. Un
            // INVALID_TOKEN ferait chercher un défaut de lien là où il n'y en a
            // pas, et l'app doit pouvoir en proposer une autre.
            case ADRESSE_INDISPONIBLE -> throw new BusinessException(
                ErrorCode.EMAIL_EXISTS,
                "Cette adresse a été inscrite entre-temps. Choisissez-en une autre.");
        };
    }
}
