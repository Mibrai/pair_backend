package org.program.pair.domain.notification;

import lombok.extern.slf4j.Slf4j;
import org.program.pair.shared.i18n.Messages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compose le texte des pushes <b>Android</b>, selon la formule du template
 * client (T5).
 *
 * <p><b>Pourquoi Android seul.</b> Depuis le 15/08, iOS réécrit la bannière sur
 * l'appareil : l'extension de service recompose {@code title}/{@code subtitle}/
 * {@code body} à partir des champs bruts de {@code data}, dans le fuseau du
 * téléphone, avec le rebours recalculé à l'affichage. Le texte du serveur y est
 * devenu un repli — nécessaire, mais plus lu dans le cas nominal. Android n'a
 * aucune extension équivalente : <b>ce qui est composé ici est tout ce que
 * l'utilisateur verra</b>. D'où la priorité donnée par le client, et d'où ce
 * composeur qui ne touche pas au chemin iOS.
 *
 * <p><b>Ce qui n'est pas couvert reste au texte traduit d'origine.</b> Les
 * méthodes rendent {@code null} pour un type hors du template — un badge gagné,
 * un nouvel abonné —, et {@link PushNotificationService} retombe alors sur
 * {@code buildTitle}/{@code buildBody}. Le changement est ainsi strictement
 * additif : aucune notification ne perd son texte parce qu'elle n'est pas dans
 * la maquette.
 *
 * <p><b>Le fuseau est une approximation assumée.</b> Composer « 19:00 » exige un
 * fuseau, et nous n'en avons aucun : {@code device_tokens} porte la langue de
 * l'appareil, pas son fuseau, et rien dans le créneau ne dit celui de son lieu.
 * On formate donc dans le fuseau de référence de l'application
 * ({@code pair.push.zone}, défaut {@code Europe/Paris}) — exact pour les deux
 * marchés servis, la France et l'Allemagne partageant le même décalage, faux
 * d'une heure pour un appareil réglé à Londres. iOS n'a pas ce défaut puisqu'il
 * reformate sur place. Le corriger demanderait au client d'envoyer son fuseau à
 * l'enregistrement du jeton ; ce n'est pas demandé à ce jour.
 */
@Component
@Slf4j
public class AndroidPushText {

    /** Corps « rappel » : le rebours d'abord, c'est l'information urgente. */
    private static final Set<NotificationType> REMINDER_SHAPE =
        EnumSet.of(NotificationType.PROGRAM_REMINDER);

    /** Corps « programme » : la date d'abord, le rebours ensuite. */
    private static final Set<NotificationType> PROGRAM_SHAPE = EnumSet.of(
        NotificationType.AUTHOR_NEW_PROGRAM,
        NotificationType.ACTIVITY_NEW_PROGRAM,
        NotificationType.NEARBY_PROGRAM);

    /** Corps « message » : la bulle d'abord, le contexte de séance ensuite. */
    private static final Set<NotificationType> MESSAGE_SHAPE = EnumSet.of(
        NotificationType.NEW_MESSAGE,
        NotificationType.PROGRAM_BROADCAST);

    /**
     * Types qui portent {@code sessionAt} sans qu'il faille décompter vers lui.
     *
     * <p>Règle du client, et elle ne va pas de soi : ces charges portent
     * {@code sessionAt} <b>comme les autres</b>. Décompter vers une séance
     * annulée, ou vers une séance passée dont on demande confirmation de
     * présence, affiche un temps restant qui n'a aucun sens.
     *
     * <p>Aucun de ces types n'a de forme de corps ici aujourd'hui, donc la règle
     * n'est enfreinte nulle part — elle est écrite pour le jour où l'un d'eux
     * entrera dans le template.
     */
    private static final Set<NotificationType> NEVER_COUNTS_DOWN = EnumSet.of(
        NotificationType.SLOT_CANCELLED,
        NotificationType.PROGRAM_CANCELLED,
        NotificationType.ATTENDANCE_PROMPT);

    /** Séparateur de segments sur une même ligne, celui de la maquette. */
    private static final String SEGMENT = " · ";

    private final Messages messages;
    private final ZoneId zone;
    private final Clock clock;

    @Autowired
    public AndroidPushText(Messages messages,
                           @Value("${pair.push.zone:Europe/Paris}") String zoneId) {
        this(messages, ZoneId.of(zoneId), Clock.systemUTC());
    }

    /** Pour les tests : fuseau et horloge imposés, donc un texte reproductible. */
    AndroidPushText(Messages messages, ZoneId zone, Clock clock) {
        this.messages = messages;
        this.zone = zone;
        this.clock = clock;
    }

    /**
     * Titre : {@code {activityName} · {programTitle}}.
     *
     * <p>Rend {@code null} quand la charge ne porte ni l'un ni l'autre — le cas
     * d'un message direct, qui n'a pas de programme et dont le titre traduit
     * (« Nouveau message de Sophie ») reste le bon.
     *
     * <p><b>Jamais tronqué ici.</b> Les deux plateformes coupent à la largeur
     * réelle de l'écran ; couper à l'avance perdrait des caractères qui tenaient.
     */
    String title(Locale locale, Map<String, Object> payload) {
        String composed = joinSegments(str(payload, "activityName"), str(payload, "programTitle"));
        return composed.isEmpty() ? null : composed;
    }

    /**
     * Corps, sur deux lignes au plus. Rend {@code null} pour un type hors du
     * template, ou quand il ne reste rien à écrire.
     *
     * <p><b>Le lieu est en dernier</b> — c'est la ligne la plus longue et la
     * moins urgente, celle qui doit sauter quand la bannière se replie sur une
     * ligne.
     */
    String body(Locale locale, NotificationType type, Map<String, Object> payload) {
        String composed;
        if (REMINDER_SHAPE.contains(type)) {
            composed = joinLines(
                joinSegments(countdown(locale, type, payload), when(locale, payload),
                    byAuthor(locale, payload)),
                str(payload, "placeName"));
        } else if (PROGRAM_SHAPE.contains(type)) {
            composed = joinLines(
                joinSegments(when(locale, payload), countdown(locale, type, payload),
                    byAuthor(locale, payload)),
                str(payload, "placeName"));
        } else if (MESSAGE_SHAPE.contains(type)) {
            composed = joinLines(
                bubble(payload),
                joinSegments(when(locale, payload), str(payload, "placeName")));
        } else {
            return null;
        }
        return composed.isEmpty() ? null : composed;
    }

    /** « Sophie Martin : On se retrouve devant le court 3 ? » */
    private static String bubble(Map<String, Object> payload) {
        String text = str(payload, "messageBody");
        if (text.isEmpty()) {
            return "";
        }
        String author = str(payload, "messageAuthorName");
        return author.isEmpty() ? text : author + " : " + text;
    }

    /**
     * « Aujourd'hui 19:00 – 20:00 ». La plage n'apparaît que si le créneau
     * déclare une fin : {@code endsAt} est facultative en base, et « 19:00 »
     * seul est le rendu voulu quand elle manque.
     */
    private String when(Locale locale, Map<String, Object> payload) {
        Instant startsAt = instant(payload, "sessionAt");
        if (startsAt == null) {
            return "";
        }
        ZonedDateTime start = startsAt.atZone(zone);
        String time = start.format(pattern(locale, "push.tpl.timePattern"));

        Instant endsAt = instant(payload, "endsAt");
        if (endsAt != null && endsAt.isAfter(startsAt)) {
            time = messages.getIn(locale, "push.tpl.timeRange",
                time, endsAt.atZone(zone).format(pattern(locale, "push.tpl.timePattern")));
        }
        return day(locale, start) + " " + time;
    }

    /**
     * « Aujourd'hui », « Demain », sinon la date écrite dans la langue de
     * l'appareil — jamais un motif littéral, le motif lui-même est une
     * traduction.
     */
    private String day(Locale locale, ZonedDateTime moment) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate date = moment.toLocalDate();
        if (date.equals(today)) {
            return messages.getIn(locale, "push.tpl.today");
        }
        if (date.equals(today.plusDays(1))) {
            return messages.getIn(locale, "push.tpl.tomorrow");
        }
        return moment.format(pattern(locale, "push.tpl.datePattern"));
    }

    /**
     * « dans 45 min », « dans 2 h » — et rien du tout si la séance a commencé,
     * ou si le type est de ceux vers lesquels on ne décompte pas.
     *
     * <p>Le texte d'une push est figé à l'émission : ce rebours vieillit dans le
     * centre de notifications, et aucun code Android ne repassera le corriger.
     * Le client l'a tranché ainsi (réponse du 15/08, question 1) parce que la
     * formule pose l'heure absolue juste après — le segment suivant corrige
     * celui-ci, et c'est l'heure absolue qui fait foi.
     */
    private String countdown(Locale locale, NotificationType type, Map<String, Object> payload) {
        if (NEVER_COUNTS_DOWN.contains(type)) {
            return "";
        }
        Instant startsAt = instant(payload, "sessionAt");
        if (startsAt == null) {
            return "";
        }
        Duration left = Duration.between(clock.instant(), startsAt);
        if (left.isNegative() || left.isZero()) {
            return "";
        }
        long minutes = left.toMinutes();
        // « h » et « min » s'écrivent de la même façon dans les trois langues
        // servies : la valeur reste hors des fichiers de traduction tant que
        // c'est vrai. Seule l'enveloppe (« dans … ») en sort.
        String amount = minutes >= 60 ? (minutes / 60) + " h" : minutes + " min";
        return messages.getIn(locale, "push.tpl.countdown", amount);
    }

    /** « par Lena Müller », vide si la charge ne nomme pas d'auteur. */
    private String byAuthor(Locale locale, Map<String, Object> payload) {
        String author = str(payload, "authorName");
        return author.isEmpty() ? "" : messages.getIn(locale, "push.tpl.by", author);
    }

    private DateTimeFormatter pattern(Locale locale, String key) {
        return DateTimeFormatter.ofPattern(messages.getIn(locale, key), locale);
    }

    /** Segments d'une ligne, les vides sautés — un séparateur orphelin est une faute d'affichage. */
    private static String joinSegments(String... segments) {
        return join(SEGMENT, segments);
    }

    /** Lignes d'un corps, les vides sautées — sinon le corps ouvre sur un retour à la ligne. */
    private static String joinLines(String... lines) {
        return join("\n", lines);
    }

    private static String join(String separator, String... parts) {
        List<String> kept = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                kept.add(part);
            }
        }
        return String.join(separator, kept);
    }

    /** Valeur de la charge en chaîne, jamais nulle — « null » imprimé est une régression classique. */
    private static String str(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return "null".equals(text) ? "" : text.strip();
    }

    /**
     * Instant ISO 8601 de la charge, {@code null} si absent ou illisible. Une
     * date qu'on ne sait pas lire ne doit pas faire échouer la composition : la
     * notification part alors sans son segment de date, pas du tout.
     */
    private static Instant instant(Map<String, Object> payload, String key) {
        String text = str(payload, key);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            log.warn("Push payload carries an unreadable '{}': {}", key, text);
            return null;
        }
    }
}
