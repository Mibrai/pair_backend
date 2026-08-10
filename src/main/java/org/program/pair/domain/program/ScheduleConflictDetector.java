package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.program.dto.ScheduleConflictDto;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applique la règle : <i>un utilisateur ne peut rejoindre un créneau que si aucune
 * de ses occurrences ne chevauche celle d'un créneau qu'il a déjà rejoint.</i>
 *
 * <p><b>Pourquoi côté serveur.</b> Le client sait détecter le conflit, mais une
 * vérification côté client n'est pas une règle : deux appareils qui s'inscrivent
 * en parallèle la contournent, et un client modifié l'ignore. Ici, elle est vraie.
 *
 * <p><b>Les deux mécanismes d'engagement comptent.</b> Un utilisateur peut être
 * pris par une inscription à un programme ({@link UserProgram}, statut
 * {@code ACTIVE}) ou par un RSVP sur un créneau ouvert
 * ({@link SlotParticipation}, statut {@code CONFIRMED} ou {@code INTERESTED}).
 * Ne regarder qu'un des deux laisserait la moitié des chevauchements passer.
 *
 * <p><b>Les récurrences sont développées des deux côtés.</b> Comparer les seuls
 * {@code startsAt} ne verrait le conflit entre « lundi 18 h chaque semaine » et
 * « lundi 18 h 30 chaque semaine » que s'ils tombent la même semaine. La règle
 * serait alors annoncée mais appliquée par hasard, ce qui est pire que pas de
 * règle du tout.
 */
@Component
@RequiredArgsConstructor
public class ScheduleConflictDetector {

    /**
     * Profondeur de comparaison. Trois mois couvrent le déphasage de n'importe
     * quelle paire de récurrences hebdomadaires ou bimensuelles réaliste ; au-delà,
     * on comparerait des séances qu'aucun des deux programmes n'a encore
     * confirmées. C'est un arbitrage explicite : deux séries qui ne se croisent
     * qu'au sixième mois ne sont pas déclarées en conflit.
     */
    private static final int HORIZON_DAYS = 90;

    /**
     * Une occurrence déjà commencée occupe encore l'utilisateur. On regarde donc
     * un peu en arrière, sinon rejoindre une séance à 18 h 30 alors qu'on est
     * engagé sur une séance de 18 h à 19 h 15 passerait, la seconde ayant déjà
     * commencé.
     */
    private static final Duration LOOKBACK = Duration.ofHours(6);

    /**
     * Durée retenue quand ni {@code endsAt} ni la durée de séance du programme ne
     * sont renseignés. C'est une convention, pas une mesure : un verdict fondé sur
     * elle reste probabiliste, et c'est la raison pour laquelle
     * {@code sessionDurationMinutes} est désormais exposé sur les DTO de créneaux —
     * pour que le client sache quand la durée est connue et quand elle est supposée.
     */
    private static final int ASSUMED_SESSION_MINUTES = 60;

    /**
     * Plafond de conflits rapportés. Le client en affiche une liste : au-delà de
     * quelques lignes, elle ne se lit plus, et un agenda saturé produirait sinon
     * des centaines d'entrées pour un même verdict — refusé.
     */
    private static final int MAX_CONFLICTS = 20;

    private final UserProgramRepository userProgramRepository;
    private final SlotParticipationRepository slotParticipationRepository;
    private final RecurrenceExpander recurrenceExpander;

    /**
     * Conflits entre les créneaux visés et les engagements déjà pris par
     * l'utilisateur.
     *
     * <p>Un créneau déjà rejoint n'entre jamais en conflit avec lui-même : c'est
     * aux appelants de refuser la double inscription avec leur propre code
     * ({@code SLOT_ALREADY_JOINED}, {@code PROGRAM_ALREADY_ENROLLED}), et rendre
     * ici un chevauchement rendrait ce refus illisible.
     *
     * @return les conflits, du plus proche au plus lointain ; vide si la voie est libre
     */
    public List<ScheduleConflictDto> detect(UUID userId, List<Schedule> targets) {
        List<Engagement> existing = existingEngagements(userId);
        if (existing.isEmpty() || targets.isEmpty()) {
            return List.of();
        }

        Instant now = Instant.now();
        Instant from = now.minus(LOOKBACK);
        Instant to = now.plus(HORIZON_DAYS, ChronoUnit.DAYS);

        // Les occurrences de chaque engagement existant sont développées une seule
        // fois, même quand plusieurs créneaux visés les traversent.
        Map<UUID, List<Interval>> existingOccurrences = new LinkedHashMap<>();
        for (Engagement engagement : existing) {
            existingOccurrences.put(engagement.schedule().getId(),
                intervalsOf(engagement.schedule(), from, to));
        }

        List<ScheduleConflictDto> conflicts = new ArrayList<>();
        for (Schedule target : targets) {
            for (Interval targetInterval : intervalsOf(target, from, to)) {
                for (Engagement engagement : existing) {
                    if (engagement.schedule().getId().equals(target.getId())) {
                        continue;
                    }
                    for (Interval busy : existingOccurrences.get(engagement.schedule().getId())) {
                        if (targetInterval.overlaps(busy)) {
                            conflicts.add(toDto(target, targetInterval, engagement, busy));
                            break; // une occurrence visée en conflit suffit à la décrire
                        }
                    }
                }
            }
        }

        conflicts.sort(java.util.Comparator.comparing(ScheduleConflictDto::occurrenceAt));
        return conflicts.size() > MAX_CONFLICTS ? conflicts.subList(0, MAX_CONFLICTS) : conflicts;
    }

    /**
     * Engagements en cours de l'utilisateur, tous mécanismes confondus.
     *
     * <p>Un même créneau peut être atteint par les deux chemins ; il n'est retenu
     * qu'une fois, l'inscription à un programme primant — c'est celle qui porte le
     * {@code userProgramId} dont le client a besoin pour la quitter.
     */
    private List<Engagement> existingEngagements(UUID userId) {
        Map<UUID, Engagement> byScheduleId = new LinkedHashMap<>();

        userProgramRepository.findByUserIdAndStatus(userId, UserProgramStatus.ACTIVE).stream()
            .filter(up -> up.getSchedule() != null)
            .forEach(up -> byScheduleId.putIfAbsent(up.getSchedule().getId(),
                new Engagement(up.getSchedule(), "PROGRAM", up.getId())));

        slotParticipationRepository
            .findByUserIdAndStatusIn(userId,
                List.of(ParticipationStatus.INTERESTED, ParticipationStatus.CONFIRMED))
            .stream()
            .filter(sp -> sp.getSchedule() != null)
            .forEach(sp -> byScheduleId.putIfAbsent(sp.getSchedule().getId(),
                new Engagement(sp.getSchedule(), "SLOT", null)));

        return List.copyOf(byScheduleId.values());
    }

    /** Occurrences du créneau dans la fenêtre, converties en intervalles occupés. */
    private List<Interval> intervalsOf(Schedule schedule, Instant from, Instant to) {
        Duration duration = durationOf(schedule);
        return recurrenceExpander
            .occurrencesBetween(schedule.getStartsAt(), schedule.getRecurrenceRule(), from, to)
            .stream()
            .map(start -> new Interval(start, start.plus(duration)))
            .toList();
    }

    /**
     * Durée d'une séance : mesurée quand on peut, déclarée sinon, supposée en
     * dernier recours. Un {@code endsAt} antérieur ou égal à {@code startsAt} est
     * traité comme absent — une durée nulle ou négative ne chevaucherait rien et
     * ferait passer le conflit inaperçu.
     */
    private Duration durationOf(Schedule schedule) {
        Instant startsAt = schedule.getStartsAt();
        Instant endsAt = schedule.getEndsAt();
        if (startsAt != null && endsAt != null && endsAt.isAfter(startsAt)) {
            return Duration.between(startsAt, endsAt);
        }

        Program program = schedule.getProgram();
        Integer declared = program != null ? program.getSessionDurationMinutes() : null;
        if (declared != null && declared > 0) {
            return Duration.ofMinutes(declared);
        }
        return Duration.ofMinutes(ASSUMED_SESSION_MINUTES);
    }

    private ScheduleConflictDto toDto(Schedule target, Interval targetInterval,
                                      Engagement engagement, Interval busy) {
        Schedule conflicting = engagement.schedule();
        Program conflictingProgram = conflicting.getProgram();

        return new ScheduleConflictDto(
            target.getId(),
            targetInterval.start(),
            conflicting.getId(),
            conflictingProgram != null ? conflictingProgram.getId() : null,
            conflictingProgram != null ? conflictingProgram.getTitle() : null,
            busy.start(),
            busy.end(),
            engagement.type(),
            engagement.userProgramId()
        );
    }

    /** Un engagement pris, et la route par laquelle on s'en défait. */
    private record Engagement(Schedule schedule, String type, UUID userProgramId) {}

    /** Intervalle semi-ouvert {@code [start, end)}. */
    private record Interval(Instant start, Instant end) {

        /**
         * Deux séances qui se touchent ne se chevauchent pas : finir à 19 h et
         * commencer à 19 h est un enchaînement, pas un conflit. D'où les bornes
         * strictes.
         */
        boolean overlaps(Interval other) {
            return start.isBefore(other.end()) && other.start().isBefore(end);
        }
    }
}
