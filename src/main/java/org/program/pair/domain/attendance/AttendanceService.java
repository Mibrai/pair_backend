package org.program.pair.domain.attendance;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.attendance.dto.AttendanceDto;
import org.program.pair.domain.attendance.dto.PendingAttendanceDto;
import org.program.pair.domain.badge.BadgeService;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotOccurrence;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.domain.program.UserProgramStatus;
import org.program.pair.domain.recap.SlotRecapService;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.program.pair.domain.user.UserService;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final SlotParticipationRepository participationRepository;
    private final UserProgramRepository userProgramRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final PracticeStatsService practiceStatsService;
    private final BadgeService badgeService;
    private final SlotRecapService recapService;

    /**
     * Confirmation en un tap. Uniquement possible si l'utilisateur était hôte
     * ou participant confirmé (slot ou programme), et uniquement APRÈS la fin
     * de la séance.
     *
     * <p><b>De quelle séance parle-t-on ?</b> De la dernière terminée, pas de
     * celle que porte la ligne. Sur un créneau récurrent, la comparaison
     * directe à {@code startsAt} ne laissait qu'une fenêtre de dix minutes —
     * le temps que le rollover repousse la ligne dans le futur — après quoi
     * confirmer sa présence redevenait impossible et {@code attendedAt}
     * enregistrait la date de la séance <i>suivante</i>. Voir
     * {@link SlotTiming#lastEndedOccurrence}.
     */
    public AttendanceDto confirm(UUID userId, UUID scheduleId, boolean wasPresent) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        SlotOccurrence occurrence = SlotTiming.lastEndedOccurrence(slot, Instant.now());

        if (occurrence == null) {
            throw new ValidationException("Ce créneau n'est pas encore terminé.");
        }

        boolean isHost = slot.getProgram().getUserActivity().getUser().getId().equals(userId);
        boolean isSlotParticipant = participationRepository
            .existsByScheduleIdAndUserIdAndStatus(scheduleId, userId, ParticipationStatus.CONFIRMED);
        boolean isProgramParticipant = userProgramRepository
            .findByUserIdAndStatus(userId, UserProgramStatus.ACTIVE).stream()
            .anyMatch(up -> up.getSchedule() != null && up.getSchedule().getId().equals(scheduleId));

        if (!isHost && !isSlotParticipant && !isProgramParticipant) {
            throw new ForbiddenException("Vous n'étiez pas inscrit à ce créneau.");
        }
        // Déjà confirmée POUR CETTE SÉANCE : sur une série hebdomadaire, la
        // vérification au grain de la ligne refusait la deuxième semaine au
        // motif que la première avait été confirmée.
        if (attendanceRepository.existsByScheduleIdAndUserIdAndAttendedAt(
                scheduleId, userId, occurrence.startsAt())) {
            throw new BusinessException("Présence déjà confirmée.");
        }

        Attendance attendance = new Attendance();
        attendance.setSchedule(slot);
        attendance.setUser(userRepository.getReferenceById(userId));
        attendance.setWasPresent(wasPresent);
        attendance.setAttendedAt(occurrence.startsAt());
        attendance.setConfirmedAt(Instant.now());
        attendanceRepository.save(attendance);

        if (wasPresent) {
            practiceStatsService.recalculateFor(userId);
            badgeService.evaluateBadges(userId);
            // La carte-souvenir compte les présents : sans ce réalignement,
            // quelqu'un qui confirme après la dernière contribution ne serait
            // jamais compté. Sans effet quand la séance n'a pas de carte.
            recapService.refreshAttendeeCount(scheduleId, occurrence.startsAt());
        }

        return toDto(attendance);
    }

    /**
     * Personnes recommandables suite à ce créneau : celles qui ont AUSSI
     * confirmé leur présence (double confirmation), preuve d'interaction réelle.
     */
    @Transactional(readOnly = true)
    public List<UserPublicDto> getRecommendableCoParticipants(UUID userId, UUID scheduleId) {
        if (!attendanceRepository.existsByScheduleIdAndUserIdAndWasPresentTrue(scheduleId, userId)) {
            return List.of();
        }
        return attendanceRepository.findPresentCoParticipants(scheduleId, userId).stream()
            .map(u -> userService.getPublicProfile(u.getId(), userId))
            .toList();
    }

    /**
     * Créneaux terminés en attente de confirmation de ma part.
     *
     * <p><b>Le filtre, en toutes lettres — il est le même que celui de
     * {@link #confirm}.</b> Une séance est proposée si et seulement si :
     *
     * <ol>
     *   <li>je l'héberge, <b>ou</b> j'y ai une participation de créneau
     *       {@code CONFIRMED}, <b>ou</b> j'ai une inscription de programme
     *       {@code ACTIVE} rattachée à ce créneau — les trois mêmes sources que
     *       {@code confirm} interroge avant de rendre son {@code 403} ;</li>
     *   <li>une occurrence est terminée ({@link SlotTiming#lastEndedOccurrence}) ;</li>
     *   <li>je n'ai pas déjà répondu <b>pour cette occurrence-là</b>.</li>
     * </ol>
     *
     * <p>C'est ce qui autorise l'app à consommer la liste telle quelle : rien
     * d'ici ne peut se voir refuser à l'écriture, et un filtre côté client
     * parierait sur une règle qui vit dans ce fichier.
     *
     * <p><b>Un créneau récurrent repose la question à chaque occurrence.</b> Ce
     * n'est pas un défaut : une participation à un créneau récurrent est un
     * engagement qui tient d'une semaine sur l'autre — c'est la même lecture qui
     * gouverne le compteur de places (voir {@code ParticipantCounter}). Qui ne
     * veut plus qu'on la lui pose quitte le créneau. Le champ {@code role} est là
     * pour que la question posée à un hôte sur sa propre séance ne ressemble pas
     * à une erreur.
     */
    @Transactional(readOnly = true)
    public List<PendingAttendanceDto> getPending(UUID userId) {
        List<Schedule> hosted = scheduleRepository.findHostedSchedules(userId);

        List<Schedule> slotJoined = participationRepository
            .findByUserIdAndStatus(userId, ParticipationStatus.CONFIRMED).stream()
            .map(p -> p.getSchedule())
            .toList();

        List<Schedule> programJoined = userProgramRepository
            .findByUserIdAndStatus(userId, UserProgramStatus.ACTIVE).stream()
            .map(up -> up.getSchedule())
            .filter(java.util.Objects::nonNull)
            .toList();

        // Les dates rendues sont celles de la séance à confirmer, pas celles
        // que porte la ligne : sur un créneau récurrent déjà avancé par le
        // rollover, la seconde annonçait une séance à venir là où on demande
        // de confirmer une séance passée.
        record Pending(Schedule slot, SlotOccurrence occurrence) {}

        // À quel titre chaque séance est proposée. Les hébergées sont connues
        // avant la fusion ; tout le reste est une inscription. Les deux ensembles
        // sont disjoints par construction — on ne peut ni rejoindre son propre
        // créneau (SLOT_OWN_SLOT) ni s'inscrire à son propre programme
        // (PROGRAM_OWN_PROGRAM).
        Set<UUID> hostedIds = hosted.stream().map(Schedule::getId).collect(Collectors.toSet());

        Instant now = Instant.now();
        return java.util.stream.Stream.of(hosted, slotJoined, programJoined)
            .flatMap(List::stream)
            .distinct()
            .map(s -> new Pending(s, SlotTiming.lastEndedOccurrence(s, now)))
            .filter(p -> p.occurrence() != null)
            .filter(p -> !attendanceRepository.existsByScheduleIdAndUserIdAndAttendedAt(
                p.slot().getId(), userId, p.occurrence().startsAt()))
            .map(p -> new PendingAttendanceDto(
                p.slot().getId(),
                p.slot().getProgram().getTitle(),
                p.slot().getPlaceName(),
                p.occurrence().startsAt(),
                // La fin reste nulle quand elle n'a jamais été déclarée : la
                // convention des deux heures sert à calculer, pas à afficher
                // une heure que personne n'a annoncée.
                p.slot().getEndsAt() != null ? p.occurrence().endsAt() : null,
                hostedIds.contains(p.slot().getId()) ? "HOST" : "PARTICIPANT"
            ))
            .toList();
    }

    private AttendanceDto toDto(Attendance a) {
        return new AttendanceDto(
            a.getId(),
            a.getSchedule().getId(),
            a.getWasPresent(),
            a.getAttendedAt(),
            a.getConfirmedAt()
        );
    }
}
