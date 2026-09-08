package org.program.pair.domain.affiche;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.affiche.dto.AfficheDto;
import org.program.pair.domain.affiche.dto.AfficheRequests;
import org.program.pair.domain.affiche.dto.AfficheUpdateDto;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.block.BlockFilterService;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.domain.user.User;
import org.program.pair.repository.AfficheRepository;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SubscriptionRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Les affiches : publier ce qu'on a vécu <b>sans être l'hôte</b>, et ne le
 * montrer qu'à qui on a choisi.
 *
 * <p>Deux règles portent tout le reste, et rien ne les contourne :
 *
 * <ul>
 *   <li><b>le déclencheur est la présence</b> — {@code Attendance.was_present},
 *       pas l'organisation. C'est la seule différence qui compte avec
 *       {@code PATCH /api/slots/{id}/recap/visibility}, réservé à l'hôte : le
 *       simple participant, qui est le cas majoritaire, n'avait aucun endroit où
 *       publier quoi que ce soit ;</li>
 *   <li><b>l'audience est appliquée ici</b>, dans la requête de lecture, et
 *       jamais rendue à filtrer au client. Un lecteur qui n'y a pas droit reçoit
 *       une liste vide. Filtrer côté client aurait supposé de faire descendre à
 *       chaque lecteur la liste des abonnés de la personne regardée — exactement
 *       ce que l'absence délibérée de {@code GET /users/{id}/subscribers}
 *       protège.</li>
 * </ul>
 *
 * <p><b>Ce que ce service ne stocke pas</b> : ni la phrase, ni le visuel, ni la
 * moindre image. Le motif est une clé opaque et le serveur ne la lit jamais. La
 * composition reste entièrement chez le client, qui la calcule depuis ses
 * propres cartes-souvenirs.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class AfficheService {

    /**
     * Combien de temps une affiche reste « en avant » sur le profil avant de
     * descendre en galerie, compté depuis la <b>fin</b> de la séance.
     *
     * <p>Sept jours, comme la fenêtre de contribution aux cartes-souvenirs, et
     * pourtant une constante <b>distincte</b> de
     * {@code SlotRecapService.CONTRIBUTION_WINDOW}. Les deux durées répondent à
     * deux questions sans rapport — « jusqu'à quand puis-je encore contribuer ? »
     * et « jusqu'à quand cette affiche est-elle mise en avant ? » — et les
     * partager ferait bouger l'une le jour où l'on ajuste l'autre, sans qu'aucun
     * test ne le dise.
     *
     * <p>Le client demandait de quoi la calculer lui-même ; c'est plutôt la date
     * qui est rendue. Elle ne se périme pas en transit, et un compte à rebours
     * approximatif sur une décision d'affichage vaut moins qu'une date juste.
     */
    static final Duration FEATURED_WINDOW = Duration.ofDays(7);

    /** Fenêtre par défaut de {@code /affiches/updates} quand {@code since} manque. */
    static final Duration UPDATES_DEFAULT_LOOKBACK = Duration.ofDays(7);

    /**
     * Profondeur maximale de {@code /affiches/updates}.
     *
     * <p>Un {@code since} plus ancien est ramené à cette borne. L'anneau est un
     * signal de <b>fraîcheur</b> : un appel demandant six mois d'historique
     * baguerait tous les avatars de l'écran d'un coup, et ferait payer un
     * parcours complet de la table pour un résultat que personne ne veut voir.
     */
    static final Duration UPDATES_MAX_LOOKBACK = Duration.ofDays(30);

    /** Plafond de personnes rendues par un appel à {@code /affiches/updates}. */
    static final int UPDATES_LIMIT = 200;

    /**
     * La forme d'une clé de motif.
     *
     * <p>Le serveur ne lit pas le motif — mais il le <b>rend à des tiers</b>, et
     * une clé n'a besoin ni d'espaces, ni de ponctuation, ni de chevrons. C'est
     * la contrainte minimale qui empêche cette valeur de devenir un jour un
     * vecteur de contenu, sans pour autant énumérer treize motifs qui
     * appartiennent au client.
     */
    private static final Pattern MOTIF = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    private final AfficheRepository afficheRepository;
    private final AttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final BlockFilterService blockFilterService;

    // ————————————————————————— publication —————————————————————————

    /**
     * Publie — ou republie — l'affiche de l'appelant sur une séance qu'il a
     * vécue. Idempotent : le même appel deux fois laisse une seule affiche.
     *
     * <p>N'exige <b>pas</b> que la carte-souvenir de la séance existe. Le
     * contrat dit que le déclencheur est la présence, et lier la publication à
     * l'existence d'une carte la ferait dépendre de la contribution d'un tiers.
     */
    public AfficheDto publish(UUID userId, UUID scheduleId, AfficheRequests.PublishRequest request) {
        String motif = parseMotif(request == null ? null : request.motif());
        AfficheAudience audience = parseAudience(request == null ? null : request.audience());
        Instant named = request == null ? null : request.slotStartedAt();

        Schedule slot = loadSlot(scheduleId);
        Instant occurrenceStart = requirePresence(userId, scheduleId, named);

        Affiche affiche = afficheRepository
            .findByUserIdAndScheduleIdAndOccurrenceStart(userId, scheduleId, occurrenceStart)
            .orElseGet(() -> newAffiche(userId, slot, occurrenceStart));

        // Toute OUVERTURE de l'audience vaut publication, et rien d'autre : un
        // motif corrigé ne doit pas rallumer l'anneau chez tous les lecteurs, et
        // un passage de SUBSCRIBERS à EVERYONE doit l'allumer chez ceux pour qui
        // l'affiche vient d'apparaître. Voir AfficheAudience.openness().
        AfficheAudience previous = affiche.getAudience();
        if (affiche.getId() == null || audience.openness() > previous.openness()) {
            affiche.setPublishedAt(Instant.now());
        }
        affiche.setMotif(motif);
        affiche.setAudience(audience);

        return toDto(afficheRepository.save(affiche));
    }

    /**
     * Dépublie. Idempotent : rien à dépublier n'est pas une erreur — le client
     * qui rejoue un geste dans le vide n'a rien à corriger.
     *
     * <p>Ne vérifie aucune présence, à rebours de la publication : retirer ce
     * qu'on a publié soi-même ne peut pas être refusé.
     */
    public void unpublish(UUID userId, UUID scheduleId, Instant slotStartedAt) {
        Optional<Affiche> target = slotStartedAt != null
            ? afficheRepository.findByUserIdAndScheduleIdAndOccurrenceStart(userId, scheduleId, slotStartedAt)
            : afficheRepository.findFirstByUserIdAndScheduleIdOrderByOccurrenceStartDesc(userId, scheduleId);

        target.ifPresent(afficheRepository::delete);
    }

    // ————————————————————————— lecture —————————————————————————

    /**
     * Les affiches de quelqu'un que j'ai le droit de voir.
     *
     * <p>Rend une liste <b>vide</b> plutôt qu'un 403 quand rien n'est visible :
     * dire « interdit » révèle qu'il y a quelque chose là où le demandeur n'a
     * rien à savoir, et c'est déjà la règle des autres lectures de profil.
     */
    @Transactional(readOnly = true)
    public List<AfficheDto> forUser(UUID targetUserId, UUID viewerId) {
        // Chez soi, tout se voit — y compris les affiches réglées sur NOBODY,
        // qui n'ont jamais d'autre lecteur que leur auteur.
        if (targetUserId.equals(viewerId)) {
            return afficheRepository.findByUserIdOrderByPublishedAtDesc(targetUserId).stream()
                .map(this::toDto)
                .toList();
        }

        Set<AfficheAudience> allowed = audiencesVisibleTo(targetUserId, viewerId);
        if (allowed.isEmpty()) {
            return List.of();
        }
        return afficheRepository
            .findByUserIdAndAudienceInOrderByPublishedAtDesc(targetUserId, allowed).stream()
            .map(this::toDto)
            .toList();
    }

    /**
     * Qui a publié depuis {@code since}, parmi ceux dont j'ai le droit de voir
     * les affiches — l'anneau sur l'avatar.
     *
     * <p>Un identifiant, une date, et de quoi dessiner un visage — voir
     * {@link AfficheUpdateDto} pour ce que les deux derniers champs paient et ce
     * qu'ils n'exposent pas. L'état « vue » reste sur l'appareil : aucun accusé de
     * lecture par affiche et par lecteur n'est tenu ici, et c'est le client qui
     * l'a demandé ainsi.
     */
    @Transactional(readOnly = true)
    public List<AfficheUpdateDto> updatesSince(UUID viewerId, Instant since) {
        Instant now = Instant.now();
        Instant floor = now.minus(UPDATES_MAX_LOOKBACK);
        Instant from = since == null ? now.minus(UPDATES_DEFAULT_LOOKBACK) : since;
        if (from.isBefore(floor)) {
            from = floor;
        }

        return afficheRepository.findUpdatesSince(viewerId, from, UPDATES_LIMIT).stream()
            .map(row -> new AfficheUpdateDto(
                (UUID) row[0],
                toInstant(row[1]),
                (String) row[2],
                // Nullable en base, et rendu nul tel quel : le client a déjà son
                // repli d'avatar, le même que sur toutes les autres listes.
                (String) row[3]))
            .toList();
    }

    // ————————————————————————— garde-fous —————————————————————————

    private Schedule loadSlot(UUID scheduleId) {
        return scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));
    }

    /**
     * La séance dont on parle, à condition d'y avoir été.
     *
     * <p>Nommée par le client quand le créneau est récurrent et qu'il y est venu
     * plusieurs fois ; sinon c'est sa présence confirmée la plus récente sur ce
     * créneau. Dans les deux cas la présence est vérifiée : c'est elle, et elle
     * seule, qui donne le droit de publier.
     *
     * <p>Le refus est le même dans les deux cas — {@code AFFICHE_NOT_ATTENDEE} —
     * et il ne distingue pas « vous n'étiez pas là » de « pas à cette séance-là » :
     * la seconde formulation apprendrait à un tiers quelles séances ont eu lieu.
     */
    private Instant requirePresence(UUID userId, UUID scheduleId, Instant named) {
        if (named != null) {
            if (!attendanceRepository.existsByScheduleIdAndUserIdAndAttendedAtAndWasPresentTrue(
                    scheduleId, userId, named)) {
                throw notAttendee();
            }
            return named;
        }
        return attendanceRepository
            .findFirstByScheduleIdAndUserIdAndWasPresentTrueOrderByAttendedAtDesc(scheduleId, userId)
            .map(Attendance::getAttendedAt)
            .orElseThrow(AfficheService::notAttendee);
    }

    private static ForbiddenException notAttendee() {
        return new ForbiddenException(ErrorCode.AFFICHE_NOT_ATTENDEE,
            "Seules les personnes présentes à cette séance peuvent en publier une affiche.");
    }

    /**
     * Quelles audiences ce lecteur a le droit de lire chez cette personne.
     *
     * <p>Ensemble vide dès qu'il n'a droit à rien : compte désactivé, blocage
     * dans un sens ou dans l'autre. {@code NOBODY} n'y figure jamais.
     */
    private Set<AfficheAudience> audiencesVisibleTo(UUID targetUserId, UUID viewerId) {
        User target = userRepository.findById(targetUserId).orElse(null);
        if (target == null || !Boolean.TRUE.equals(target.getIsActive())) {
            return Set.of();
        }
        if (blockFilterService.blocked(viewerId, targetUserId)) {
            return Set.of();
        }
        return subscriptionRepository.existsBySubscriberIdAndTargetAuthorId(viewerId, targetUserId)
            ? EnumSet.of(AfficheAudience.EVERYONE, AfficheAudience.SUBSCRIBERS)
            : EnumSet.of(AfficheAudience.EVERYONE);
    }

    /** Clé de motif : obligatoire, courte, et sans autre caractère qu'une clé. */
    private static String parseMotif(String raw) {
        String motif = raw == null ? "" : raw.strip();
        if (!MOTIF.matcher(motif).matches()) {
            throw new BusinessException(ErrorCode.AFFICHE_INVALID_MOTIF,
                "Motif invalide : une clé de 40 caractères au plus, sans espace ni ponctuation.");
        }
        return motif;
    }

    /**
     * Audience reçue. Absente vaut {@code NOBODY} — le défaut du contrat, et le
     * sens dans lequel une erreur doit pencher. Une valeur inconnue, elle, est
     * refusée : la ramener au défaut ferait croire à une publication muette.
     */
    private static AfficheAudience parseAudience(String raw) {
        if (raw == null || raw.isBlank()) {
            return AfficheAudience.NOBODY;
        }
        return AfficheAudience.parse(raw).orElseThrow(() -> new BusinessException(
            ErrorCode.AFFICHE_INVALID_AUDIENCE, "Audience inconnue : " + raw));
    }

    private Affiche newAffiche(UUID userId, Schedule slot, Instant occurrenceStart) {
        Affiche affiche = new Affiche();
        affiche.setUser(userRepository.getReferenceById(userId));
        affiche.setSchedule(slot);
        affiche.setOccurrenceStart(occurrenceStart);
        // Figée ici, comme sur la carte-souvenir : allonger le créneau des mois
        // plus tard ne doit pas remonter une affiche sur un profil.
        affiche.setOccurrenceEnd(SlotTiming.occurrenceEndOf(slot, occurrenceStart));
        affiche.setAudience(AfficheAudience.NOBODY);
        return affiche;
    }

    // ————————————————————————— rendu —————————————————————————

    /**
     * L'affiche telle qu'elle se lit.
     *
     * <p><b>{@code activityName} et {@code categoryColorRamp} sont lus par la
     * même chaîne que {@code SlotRecapService.toDto}</b> — {@code Schedule →
     * Program → UserActivity → Activity → Category} — et volontairement par la
     * même. Deux chemins vers la même valeur, ce sont deux chemins qui divergent
     * le jour où l'un des replis change, et personne ne remarquerait que
     * l'affiche et la carte-souvenir d'une même séance annoncent deux activités.
     *
     * <p>Les gardes-null en cascade sont ceux de la carte-souvenir, au geste
     * près. Les cinq clés étrangères de la chaîne sont {@code NOT NULL}, donc
     * aucune branche nulle n'est atteignable aujourd'hui — mais un rendu qui
     * lèverait une {@code NullPointerException} le jour où l'une d'elles
     * s'assouplit ferait échouer la galerie entière pour une teinte manquante.
     */
    private AfficheDto toDto(Affiche affiche) {
        Schedule slot = affiche.getSchedule();
        Program program = slot.getProgram();
        UserActivity userActivity = program != null ? program.getUserActivity() : null;
        Activity activity = userActivity != null ? userActivity.getActivity() : null;
        Category category = activity != null ? activity.getCategory() : null;

        return new AfficheDto(
            slot.getId(),
            affiche.getOccurrenceStart(),
            activity != null ? activity.getName() : null,
            category != null ? category.getColorRamp() : null,
            affiche.getMotif(),
            affiche.getPublishedAt(),
            affiche.getAudience().name(),
            affiche.getOccurrenceEnd().plus(FEATURED_WINDOW)
        );
    }

    /**
     * L'instant d'une agrégation SQL native.
     *
     * <p>{@code MAX(published_at)} n'a pas de type applicatif déclaré : selon le
     * pilote et la version d'Hibernate, il revient en {@link Timestamp} ou en
     * {@link OffsetDateTime}. Les deux se lisent ici plutôt que dans une seule
     * branche qui casserait à la montée de version — et le prix d'une erreur
     * serait une date d'anneau fausse, ce qui ne se voit pas.
     */
    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        throw new IllegalStateException("Type d'horodatage inattendu : " + value.getClass());
    }
}
