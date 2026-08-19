package org.program.pair.domain.publicslot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.media.StorageService;
import org.program.pair.domain.program.Schedule;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Les trois adresses publiques d'un créneau : la page, son JSON, son image.
 *
 * <p>Toutes trois sont ouvertes, et c'est le jeton qui fait l'autorisation. Un
 * jeton inconnu, un créneau retiré du partage, un programme redevenu privé ou
 * une séance passée depuis plus d'un jour rendent tous le même 404 — jamais un
 * 403, qui confirmerait qu'il y avait quelque chose à voir.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class PublicSlotController {

    private static final DateTimeFormatter DAY =
        DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);
    private static final DateTimeFormatter HOUR =
        DateTimeFormatter.ofPattern("H'h'mm", Locale.FRENCH);

    private final PublicSlotService publicSlotService;
    private final StorageService storageService;

    @Value("${pair.public.base-url:https://meetdo.fun}")
    private String publicBaseUrl;

    /**
     * Le schéma d'URI propre à l'application, celui que le bouton utilise.
     *
     * <p>Séparé de {@code publicBaseUrl} parce qu'il désigne autre chose :
     * l'application installée sur l'appareil, et non ce serveur.
     */
    @Value("${pair.mobile.scheme:meetdo}")
    private String mobileScheme;

    /** Le créneau en JSON, pour un client qui compose sa propre présentation. */
    @GetMapping("/public/slots/{token}")
    @ResponseBody
    public PublicSlotView json(@PathVariable String token) {
        return publicSlotService.view(token, Instant.now());
    }

    /**
     * L'adresse courte, celle qu'on partage réellement.
     *
     * <p>Elle rend la page directement plutôt que de rediriger vers elle. Une
     * redirection fonctionnerait pour un navigateur, mais les robots d'aperçu la
     * suivent inégalement et les liens universels iOS s'y perdent : le chemin le
     * plus court est celui qui n'a pas d'étape.
     */
    @GetMapping("/s/{token}")
    public String shortLink(@PathVariable String token, Model model) {
        return page(token, model);
    }

    @GetMapping("/public/slots/{token}/page")
    public String page(@PathVariable String token, Model model) {
        PublicSlotView slot = publicSlotService.view(token, Instant.now());
        ZoneId zone = ZoneId.of("Europe/Paris");

        String day = DAY.format(slot.startsAt().atZone(zone));
        String hour = HOUR.format(slot.startsAt().atZone(zone));

        model.addAttribute("slot", slot);
        model.addAttribute("day", day);
        model.addAttribute("startTime", hour);
        model.addAttribute("endTime", HOUR.format(slot.endsAt().atZone(zone)));

        // La description de l'aperçu est composée ici, pas dans le gabarit :
        // c'est elle que les messageries affichent sous le titre, et elle doit
        // dire concrètement quoi, quand, où, avec combien de monde — une phrase
        // vague ne convertit pas.
        model.addAttribute("ogDescription", ogDescription(slot, day, hour));
        model.addAttribute("ogUrl", publicBaseUrl + "/s/" + token);
        model.addAttribute("ogImage", slot.hasImage()
            ? publicBaseUrl + "/public/slots/" + token + "/image"
            : null);
        // Le bouton pointait vers l'adresse de cette même page. C'était sans effet
        // dans les deux cas de figure : sans application installée il la
        // rechargeait, et avec — une fois les liens universels actifs — iOS
        // n'intercepte justement pas un lien vers le domaine où l'on se trouve
        // déjà. Le schéma propre à l'application, lui, ouvre l'application
        // aujourd'hui, sans rien attendre des fichiers d'association ni des
        // entitlements qui leur manquent encore.
        model.addAttribute("appLink", mobileScheme + "://slot/" + token);

        return "public-slot";
    }

    /**
     * L'image de l'aperçu.
     *
     * <p>Route dédiée, et non la route média authentifiée : celle-ci exige un
     * jeton d'accès, et un robot d'aperçu n'en a pas — il recevrait un 401 et
     * l'aperçu partirait sans visuel, ce qui est précisément ce que ce lot
     * cherche à éviter. L'exposition reste bornée au créneau que le jeton
     * désigne, et aux mêmes conditions de visibilité que la page.
     */
    @GetMapping("/public/slots/{token}/image")
    @ResponseBody
    public ResponseEntity<InputStreamResource> image(@PathVariable String token) {
        Schedule slot = publicSlotService.resolve(token, Instant.now());
        String imageUrl = publicSlotService.imageOf(slot);
        if (imageUrl == null) {
            throw new ResourceNotFoundException("Image introuvable.");
        }

        String filename = imageUrl.replaceFirst("^.*/api/media/files/", "");
        try {
            InputStream stream = storageService.loadAsResource(filename);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(contentTypeOf(filename))
                .body(new InputStreamResource(stream));
        } catch (IOException e) {
            log.warn("Image de partage illisible pour le jeton {} : {}", token, e.getMessage());
            throw new ResourceNotFoundException("Image introuvable.");
        }
    }

    /**
     * « Samedi 14 juin, 9h00 · Yoga · Studio Lumière, Strasbourg · 3 inscrits »
     *
     * <p>La ville est omise quand elle est absente plutôt que remplacée : rien
     * ne la devine dans ce système, et une ville inventée serait pire qu'une
     * ville manquante.
     */
    private String ogDescription(PublicSlotView slot, String day, String hour) {
        StringBuilder description = new StringBuilder()
            .append(Character.toUpperCase(day.charAt(0))).append(day.substring(1))
            .append(", ").append(hour)
            .append(" · ").append(slot.activityName())
            .append(" · ").append(slot.placeName());

        if (slot.city() != null && !slot.city().isBlank()) {
            description.append(", ").append(slot.city());
        }

        int count = slot.participantCount() == null ? 0 : slot.participantCount();
        description.append(" · ").append(count)
            .append(count > 1 ? " inscrits" : " inscrit");

        return description.toString();
    }

    private MediaType contentTypeOf(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.valueOf("image/webp");
        return MediaType.IMAGE_JPEG;
    }

    /** Même page pour toutes les raisons de ne pas afficher — voir la classe. */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String unavailable() {
        return "public-slot-unavailable";
    }
}
