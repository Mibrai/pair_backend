package org.program.pair.domain.safety;

import lombok.RequiredArgsConstructor;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * La page qu'un proche ouvre, sans compte, depuis le lien qu'on lui a envoyé.
 *
 * <p><b>Premier contrôleur de vue du dépôt.</b> Les 180 autres endpoints sont
 * en JSON ; celui-ci rend du HTML parce que son destinataire est un navigateur,
 * pas une application. Il est donc annoté {@code @Controller} et non
 * {@code @RestController} — c'est la différence qui fait résoudre une vue au
 * lieu de sérialiser une chaîne.
 *
 * <p><b>Le 404 est traité ici.</b> {@code GlobalExceptionHandler} est un
 * {@code @RestControllerAdvice} : sans le gestionnaire local ci-dessous, un lien
 * expiré rendrait un objet JSON à quelqu'un qui vient d'ouvrir une page web.
 * Fonctionnellement correct, illisible en pratique.
 *
 * <p>Les dates sont composées ici plutôt que dans le gabarit : le fuseau est une
 * décision, pas une question de présentation, et c'est le même que celui du
 * développement des récurrences.
 */
@Controller
@RequiredArgsConstructor
public class PublicSafetyController {

    private static final DateTimeFormatter DAY =
        DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);
    private static final DateTimeFormatter HOUR =
        DateTimeFormatter.ofPattern("H'h'mm", Locale.FRENCH);

    private final SlotSafetyShareService safetyShareService;

    @Value("${pair.recurrence.zone:Europe/Paris}")
    private String zoneId;

    @GetMapping("/public/safety/{token}")
    public String view(@PathVariable String token, Model model) {
        SafetyShareView share = safetyShareService.view(token, Instant.now());
        ZoneId zone = ZoneId.of(zoneId);

        model.addAttribute("activityName", share.activityName());
        model.addAttribute("day", DAY.format(share.startsAt().atZone(zone)));
        model.addAttribute("startTime", HOUR.format(share.startsAt().atZone(zone)));
        model.addAttribute("endTime", HOUR.format(share.endsAt().atZone(zone)));
        model.addAttribute("placeName", share.placeName());
        model.addAttribute("city", share.city());
        model.addAttribute("organizerGivenName", share.organizerGivenName());

        return "safety-share";
    }

    /**
     * Lien inconnu ou expiré — la même page dans les deux cas.
     *
     * <p>Les distinguer reviendrait à confirmer, à qui essaie des jetons, qu'un
     * rendez-vous a eu lieu.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String expired() {
        return "safety-share-expired";
    }
}
