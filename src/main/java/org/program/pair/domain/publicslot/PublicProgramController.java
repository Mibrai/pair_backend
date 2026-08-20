package org.program.pair.domain.publicslot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.media.StorageService;
import org.program.pair.domain.program.Program;
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
 * Les adresses publiques d'un programme : la page, son JSON, ses images.
 *
 * <p>Jumelles de {@link PublicSlotController}, jeton pour jeton. Un jeton
 * inconnu, un programme retiré du partage, redevenu privé ou archivé rendent tous
 * le même {@code 404} — jamais un {@code 403}, qui confirmerait qu'il y avait
 * quelque chose à voir.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class PublicProgramController {

    private static final Set<String> SUPPORTED = Set.of("fr", "en", "de");

    private final PublicProgramService publicProgramService;
    private final StorageService storageService;
    private final Messages messages;

    @Value("${pair.public.base-url:https://lien.meetdo.fun}")
    private String publicBaseUrl;

    @Value("${pair.mobile.scheme:meetdo}")
    private String mobileScheme;

    /** Le programme en JSON, pour un client qui compose sa propre présentation. */
    @GetMapping("/public/programs/{token}")
    @ResponseBody
    public PublicProgramView json(@PathVariable String token) {
        return publicProgramService.view(token, Instant.now());
    }

    /**
     * L'adresse courte, celle qu'on partage réellement.
     *
     * <p>Elle rend la page directement plutôt que de rediriger : les robots
     * d'aperçu suivent les redirections inégalement, et les liens universels iOS
     * s'y perdent.
     */
    @GetMapping("/p/{token}")
    public String shortLink(@PathVariable String token, Model model,
                            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        publicProgramService.countView(token, userAgent);
        return page(token, model);
    }

    @GetMapping("/public/programs/{token}/page")
    public String page(@PathVariable String token, Model model) {
        PublicProgramView program = publicProgramService.view(token, Instant.now());
        Locale locale = pageLocale();

        model.addAttribute("program", program);
        model.addAttribute("lang", locale.getLanguage());
        model.addAttribute("t", labels(locale, program));

        String when = null;
        if (program.nextSessionAt() != null) {
            DateTimeFormatter day = DateTimeFormatter
                .ofPattern(messages.getIn(locale, "public.slot.datePattern"), locale);
            DateTimeFormatter hour = DateTimeFormatter
                .ofPattern(messages.getIn(locale, "public.slot.timePattern"), locale);
            ZoneId zone = ZoneId.of("Europe/Paris");
            when = day.format(program.nextSessionAt().atZone(zone))
                + ", " + hour.format(program.nextSessionAt().atZone(zone));
        }
        model.addAttribute("when", when);

        model.addAttribute("ogDescription", ogDescription(program, when, locale));
        model.addAttribute("ogUrl", publicBaseUrl + "/p/" + token);
        // Jamais nul : à défaut d'image, la vignette dessinée. Un aperçu sans
        // visuel s'affiche comme deux lignes grises dans une messagerie.
        model.addAttribute("ogImage", publicBaseUrl + "/public/programs/" + token
            + (program.hasImage() ? "/image" : "/cover.png"));
        model.addAttribute("appLink", mobileScheme + "://programs/" + token);

        return "public-program";
    }

    /** L'image du programme, servie sans jeton d'accès — un robot n'en a pas. */
    @GetMapping("/public/programs/{token}/image")
    @ResponseBody
    public ResponseEntity<InputStreamResource> image(@PathVariable String token) {
        Program program = publicProgramService.resolve(token, Instant.now());
        String imageUrl = program.getImageUrl();
        if (imageUrl == null || imageUrl.isBlank()) {
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
            log.warn("Image de partage illisible pour le programme {} : {}", token, e.getMessage());
            throw new ResourceNotFoundException("Image introuvable.");
        }
    }

    /** La vignette dessinée, quand le programme n'a pas d'image à lui. */
    @GetMapping(value = "/public/programs/{token}/cover.png", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> cover(@PathVariable String token) {
        PublicProgramView program = publicProgramService.view(token, Instant.now());

        String subtitle = program.activityName() == null ? "" : program.activityName();
        if (program.city() != null && !program.city().isBlank()) {
            subtitle += " · " + program.city();
        }

        try {
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(PublicSlotCover.render(
                    program.categoryColorRamp(), program.title(), subtitle));
        } catch (IOException e) {
            log.warn("Vignette illisible pour le programme {} : {}", token, e.getMessage());
            throw new ResourceNotFoundException("Image introuvable.");
        }
    }

    /**
     * La description de l'aperçu, composée ici et non dans le gabarit.
     *
     * <p>C'est elle que les messageries affichent sous le titre. Elle dit ce
     * qu'on peut dire d'un programme : l'activité, quand a lieu la prochaine
     * séance s'il y en a une, et combien de personnes y sont déjà.
     */
    private String ogDescription(PublicProgramView program, String when, Locale locale) {
        StringBuilder description = new StringBuilder();
        if (program.activityName() != null) {
            description.append(program.activityName());
        }
        if (when != null) {
            if (description.length() > 0) {
                description.append(" · ");
            }
            description.append(Character.toUpperCase(when.charAt(0))).append(when.substring(1));
        }
        if (program.city() != null && !program.city().isBlank()) {
            description.append(" · ").append(program.city());
        }
        description.append(" · ")
            .append(messages.getIn(locale, "public.program.enrolled", program.enrolledCount()));
        return description.toString();
    }

    /**
     * La langue de la page.
     *
     * <p>Un programme ne déclare pas de langue, contrairement à un créneau : on
     * s'en remet donc à l'{@code Accept-Language} de la requête, puis au français.
     */
    private Locale pageLocale() {
        String requested = LocaleContextHolder.getLocale().getLanguage();
        return SUPPORTED.contains(requested) ? Locale.forLanguageTag(requested) : Locale.FRENCH;
    }

    private Map<String, String> labels(Locale locale, PublicProgramView program) {
        Map<String, String> t = new LinkedHashMap<>();
        for (String key : List.of("cta", "hint", "about", "when", "where", "who")) {
            t.put(key, messages.getIn(locale, "public.slot." + key));
        }
        t.put("enrolled", messages.getIn(locale, "public.program.enrolled", program.enrolledCount()));
        t.put("sessions", messages.getIn(locale, "public.program.sessions", program.sessionCount()));
        if (program.organizerGivenName() != null) {
            t.put("host", messages.getIn(locale, "public.slot.host", program.organizerGivenName()));
        }
        return t;
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
