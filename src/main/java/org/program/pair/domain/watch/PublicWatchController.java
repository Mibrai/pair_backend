package org.program.pair.domain.watch;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * La page qu'un proche ouvre, sans compte, pour savoir où en est quelqu'un.
 *
 * <p><b>Rendu côté serveur, et robuste au pire contexte.</b> Elle s'ouvre depuis
 * un SMS à 2 h du matin, dans un navigateur intégré, un client mail d'entreprise,
 * parfois un vieux téléphone : un rendu qui dépendrait d'un sondage JS pourrait
 * afficher une page vide au pire moment. D'où {@code @Controller} et un gabarit
 * Thymeleaf, avec {@code <meta refresh>} comme plancher — un rafraîchissement qui
 * tient sans JavaScript.
 *
 * <p><b>ETag et Cache-Control.</b> Dix contacts qui laissent l'onglet ouvert toute
 * la nuit ne doivent pas faire un déni de service accidentel : la page porte un
 * {@code ETag} calculé sur l'état et l'heure de mise à jour, répond {@code 304} à
 * un {@code If-None-Match} qui correspond, et s'annonce cachable vingt secondes.
 *
 * <p><b>Aucun bouton ne clôture.</b> Deux boutons d'accusé seulement — « j'ai vu »,
 * « je l'ai eue au téléphone » — qui remontent dans l'app. La page étant publique
 * et non authentifiée, un bouton de clôture clôturerait pour quiconque a le lien.
 */
@Controller
@RequiredArgsConstructor
public class PublicWatchController {

    private static final DateTimeFormatter HEURE =
        DateTimeFormatter.ofPattern("H'h'mm", Locale.FRENCH);

    private final PublicWatchService publicWatchService;

    @org.springframework.beans.factory.annotation.Value("${pair.recurrence.zone:Europe/Paris}")
    private String zoneId;

    @GetMapping("/public/watch/{token}")
    public String view(@PathVariable String token,
                       @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
                       Model model, HttpServletResponse response) {
        Instant now = Instant.now();
        PublicWatchView v = publicWatchService.view(token, now);

        String etag = etag(v);
        response.setHeader("Cache-Control", "max-age=20");
        response.setHeader("ETag", etag);
        if (etag.equals(ifNoneMatch)) {
            response.setStatus(HttpStatus.NOT_MODIFIED.value());
            return null; // 304 : rien à rendre, le client garde sa copie.
        }

        ZoneId zone = ZoneId.of(zoneId);
        model.addAttribute("token", token);
        model.addAttribute("statut", v.status().libelle());
        model.addAttribute("statutCode", v.status().name());
        model.addAttribute("prenom", v.personGivenName());
        model.addAttribute("activite", v.activityName());
        model.addAttribute("lieu", v.placeName());
        model.addAttribute("ville", v.city());
        model.addAttribute("debut", heure(v.startsAt(), zone));
        model.addAttribute("fin", heure(v.endsAt(), zone));
        model.addAttribute("echeance", heure(v.deadlineAt(), zone));
        model.addAttribute("majMinutes", minutesDepuis(v.lastUpdateAt(), now));
        model.addAttribute("terminal", v.terminal());
        return "watch-status";
    }

    @PostMapping("/public/watch/{token}/seen")
    public String seen(@PathVariable String token) {
        publicWatchService.acknowledge(token, WatchEventType.GUARDIAN_ACK_SEEN, Instant.now());
        return "redirect:/public/watch/" + token;
    }

    @PostMapping("/public/watch/{token}/called")
    public String called(@PathVariable String token) {
        publicWatchService.acknowledge(token, WatchEventType.GUARDIAN_ACK_CALLED, Instant.now());
        return "redirect:/public/watch/" + token;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String expired() {
        return "watch-status-expired";
    }

    /** Empreinte courte de ce qui change à l'écran : l'état et l'heure de mise à jour. */
    private static String etag(PublicWatchView v) {
        long h = v.status().name().hashCode() * 31L
            + (v.lastUpdateAt() == null ? 0 : v.lastUpdateAt().getEpochSecond());
        return "\"" + Long.toHexString(h) + "\"";
    }

    private static String heure(Instant instant, ZoneId zone) {
        return instant == null ? null : HEURE.format(instant.atZone(zone));
    }

    private static long minutesDepuis(Instant instant, Instant now) {
        if (instant == null) {
            return 0;
        }
        return java.time.Duration.between(instant, now).toMinutes();
    }
}
