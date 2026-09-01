package org.program.pair.domain.guardian;

import lombok.RequiredArgsConstructor;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * La page qu'un contact ouvre, sans compte, pour accepter ou refuser d'être
 * prévenu.
 *
 * <p><b>{@code GET} rend une page, {@code POST} applique la décision — et c'est
 * le point de conception le plus important de ce contrôleur.</b> Les scanners de
 * messagerie, les aperçus de liens des applications de SMS et les proxys
 * d'entreprise suivent automatiquement les liens {@code GET}, sans intervention
 * humaine. Si accepter ou refuser était un {@code GET}, un robot fabriquerait un
 * consentement, ou pire, un refus — définitif et global au numéro — sans que le
 * propriétaire du téléphone ait rien fait ni rien su. La décision passe donc par
 * un formulaire en {@code POST}, que rien ne pré-charge. Le {@code GET} reste
 * sûr à visiter.
 *
 * <p><b>Contrôleur de vue, comme {@code PublicSafetyController}.</b> Le
 * destinataire est un navigateur, pas une application : {@code @Controller} pour
 * résoudre une vue, et le 404 traité localement pour ne pas rendre du JSON à qui
 * vient d'ouvrir une page web.
 *
 * <p>La page ne dit jamais qui d'autre a été désigné, ni ne révèle l'existence
 * d'un lien à qui essaie des jetons : un jeton inconnu rend la même page « demande
 * introuvable » qu'un jeton révoqué.
 */
@Controller
@RequiredArgsConstructor
public class PublicGuardianConsentController {

    private final GuardianService guardianService;

    @GetMapping("/public/guardian-consent/{token}")
    public String view(@PathVariable String token, Model model) {
        GuardianService.ConsentView consent = guardianService.consentView(token);
        model.addAttribute("token", token);
        model.addAttribute("ownerName", consent.ownerName());
        model.addAttribute("state", consent.state().name());
        return "guardian-consent";
    }

    @PostMapping("/public/guardian-consent/{token}/accept")
    public String accept(@PathVariable String token, Model model) {
        guardianService.acceptConsent(token);
        model.addAttribute("decision", "accepted");
        return "guardian-consent-done";
    }

    @PostMapping("/public/guardian-consent/{token}/refuse")
    public String refuse(@PathVariable String token, Model model) {
        guardianService.refuseConsent(token);
        model.addAttribute("decision", "refused");
        return "guardian-consent-done";
    }

    /**
     * Jeton inconnu ou demande retirée — la même page dans les deux cas, pour ne
     * pas confirmer, à qui essaie des jetons, qu'une demande a existé.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String introuvable() {
        return "guardian-consent-expired";
    }
}
