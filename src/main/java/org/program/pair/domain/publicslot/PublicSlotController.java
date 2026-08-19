package org.program.pair.domain.publicslot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.media.StorageService;
import org.program.pair.domain.program.Schedule;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.i18n.Messages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /**
     * Les trois langues que le produit sert. Une page dont la langue annoncée
     * n'en fait pas partie retombe sur le français plutôt que d'être servie dans
     * une langue dont aucun libellé n'existe.
     */
    private static final Set<String> SUPPORTED = Set.of("fr", "en", "de");

    private final PublicSlotService publicSlotService;
    private final StorageService storageService;
    private final Messages messages;

    @Value("${pair.public.base-url:https://lien.meetdo.fun}")
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
    public String shortLink(@PathVariable String token, Model model,
                            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        // L'adresse réellement partagée, et la seule qui compte une ouverture :
        // le JSON sert des clients programmatiques, et /public/slots/{token}/page
        // n'est jamais collée nulle part.
        publicSlotService.countView(token, userAgent);
        return page(token, model);
    }

    @GetMapping("/public/slots/{token}/page")
    public String page(@PathVariable String token, Model model) {
        PublicSlotView slot = publicSlotService.view(token, Instant.now());
        ZoneId zone = ZoneId.of("Europe/Paris");
        Locale locale = pageLocale(slot);

        DateTimeFormatter dayFormat = DateTimeFormatter
            .ofPattern(messages.getIn(locale, "public.slot.datePattern"), locale);
        DateTimeFormatter hourFormat = DateTimeFormatter
            .ofPattern(messages.getIn(locale, "public.slot.timePattern"), locale);

        String day = dayFormat.format(slot.startsAt().atZone(zone));
        String hour = hourFormat.format(slot.startsAt().atZone(zone));

        model.addAttribute("slot", slot);
        model.addAttribute("lang", locale.getLanguage());
        model.addAttribute("day", day);
        model.addAttribute("startTime", hour);
        model.addAttribute("endTime", hourFormat.format(slot.endsAt().atZone(zone)));

        // Libellés résolus ici plutôt que par #{...} dans le gabarit : la langue
        // de cette page n'est pas celle de la requête. Thymeleaf résoudrait ses
        // clés d'après l'en-tête Accept-Language, ce qui donnerait une page dont
        // le texte et la date ne parlent pas la même langue.
        model.addAttribute("t", labels(locale, slot));

        // La description de l'aperçu est composée ici, pas dans le gabarit :
        // c'est elle que les messageries affichent sous le titre, et elle doit
        // dire concrètement quoi, quand, où, avec combien de monde — une phrase
        // vague ne convertit pas.
        model.addAttribute("ogDescription", ogDescription(slot, day, hour, locale));
        model.addAttribute("ogUrl", publicBaseUrl + "/s/" + token);
        model.addAttribute("calendarUrl", publicBaseUrl + "/s/" + token + "/calendar.ics");

        // Jamais nul, et c'est tout l'objet de la vignette dessinée : un aperçu
        // sans visuel s'affiche comme deux lignes grises, à peu près
        // indiscernables d'un lien mort.
        model.addAttribute("ogImage", publicBaseUrl + "/public/slots/" + token
            + (slot.hasImage() ? "/image" : "/cover.png"));
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
    private String ogDescription(PublicSlotView slot, String day, String hour, Locale locale) {
        StringBuilder description = new StringBuilder()
            .append(Character.toUpperCase(day.charAt(0))).append(day.substring(1))
            .append(", ").append(hour)
            .append(" · ").append(slot.activityName())
            .append(" · ").append(slot.placeName());

        if (slot.city() != null && !slot.city().isBlank()) {
            description.append(", ").append(slot.city());
        }

        // Le décompte passe par un choix de catalogue et non par un ternaire :
        // « 0 inscrit » se dit autrement que « 1 inscrit », et l'allemand ne
        // découpe pas ses cas comme le français.
        int count = slot.participantCount() == null ? 0 : slot.participantCount();
        description.append(" · ").append(messages.getIn(locale, "public.slot.joined", count));

        return description.toString();
    }

    /**
     * La langue de la page.
     *
     * <p>Celle de la séance d'abord : c'est la langue dans laquelle elle se
     * tiendra, donc celle du lecteur visé — mieux que l'{@code Accept-Language}
     * d'un appareil qui n'appartient peut-être pas à quelqu'un du coin. À défaut,
     * l'en-tête de la requête, que {@code LocaleContextHolder} a déjà résolu. À
     * défaut encore, le français.
     */
    private Locale pageLocale(PublicSlotView slot) {
        String declared = slot.primaryLanguage();
        if (declared != null && SUPPORTED.contains(declared.toLowerCase(Locale.ROOT))) {
            return Locale.forLanguageTag(declared.toLowerCase(Locale.ROOT));
        }
        String requested = LocaleContextHolder.getLocale().getLanguage();
        return SUPPORTED.contains(requested) ? Locale.forLanguageTag(requested) : Locale.FRENCH;
    }

    /** Les libellés de la page, dans sa langue à elle. */
    private Map<String, String> labels(Locale locale, PublicSlotView slot) {
        Map<String, String> t = new LinkedHashMap<>();
        for (String key : List.of("cta", "hint", "calendar", "about", "when", "where", "who")) {
            t.put(key, messages.getIn(locale, "public.slot." + key));
        }
        int count = slot.participantCount() == null ? 0 : slot.participantCount();
        t.put("joined", messages.getIn(locale, "public.slot.joined", count));
        if (slot.organizerGivenName() != null) {
            t.put("host", messages.getIn(locale, "public.slot.host", slot.organizerGivenName()));
        }
        return t;
    }

    /**
     * La vignette dessinée, servie quand le créneau n'a pas d'image à lui.
     *
     * <p>Route publique aux mêmes conditions que la page : un robot d'aperçu
     * n'a pas de jeton, et une image refusée vaut une image absente.
     */
    @GetMapping(value = "/public/slots/{token}/cover.png", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> cover(@PathVariable String token) {
        PublicSlotView slot = publicSlotService.view(token, Instant.now());
        Locale locale = pageLocale(slot);
        ZoneId zone = ZoneId.of("Europe/Paris");

        String subtitle = DateTimeFormatter
            .ofPattern(messages.getIn(locale, "public.slot.datePattern"), locale)
            .format(slot.startsAt().atZone(zone))
            + " · " + slot.activityName()
            + (slot.city() == null || slot.city().isBlank() ? "" : " · " + slot.city());

        try {
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(PublicSlotCover.render(
                    slot.categoryColorRamp(), slot.programTitle(), subtitle));
        } catch (IOException e) {
            log.warn("Vignette illisible pour le jeton {} : {}", token, e.getMessage());
            throw new ResourceNotFoundException("Image introuvable.");
        }
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
