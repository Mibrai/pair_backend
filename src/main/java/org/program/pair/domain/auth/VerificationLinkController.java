package org.program.pair.domain.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Le chemin court du lien de vérification, {@code /v/{token}}.
 *
 * <p>Il existe pour une seule raison : <b>un fichier d'association Apple ne
 * raisonne que sur le chemin</b>. Déclarer {@code /api/*} y offrirait à iOS de
 * détourner vers l'application tout ce qui ressemblera un jour à cette API ;
 * {@code /v/*} ne recouvre que la vérification. C'est le même arbitrage qui a
 * donné {@code /s/} et {@code /p/} au lot Partage, avec en prime un lien qui se
 * lit dans un e-mail et survit aux messageries qui tronquent.
 *
 * <p>Servi directement, et non par une redirection vers la route historique :
 * une redirection ferait voyager le jeton dans une seconde URL sans rien
 * apporter, l'application interceptant de toute façon l'adresse avant que la
 * requête ne parte.
 *
 * <p><b>Le comportement est celui de {@code /api/auth/verify-email}</b>, sans
 * écart — voir {@link ReponseVerificationEmail}. Le JSON y est honoré lui aussi,
 * bien que l'application annonce ne jamais laisser partir la requête : une route
 * qui rendrait du HTML à un client qui demande du JSON serait un trou qu'on ne
 * découvrirait que le jour où l'interception échoue.
 *
 * <p>Le jeton est un UUID ({@code EmailVerificationService}) : rien à encoder
 * pour le porter dans un segment de chemin.
 */
@RestController
@RequiredArgsConstructor
public class VerificationLinkController {

    private final ReponseVerificationEmail reponse;

    @GetMapping("/v/{token}")
    public ResponseEntity<?> verifier(
            @PathVariable String token,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        return reponse.repondre(token, accept);
    }
}
