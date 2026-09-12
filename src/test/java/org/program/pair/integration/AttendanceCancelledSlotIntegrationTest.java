package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.AttendanceService;
import org.program.pair.domain.attendance.dto.AttendanceDto;
import org.program.pair.domain.attendance.dto.PendingAttendanceDto;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotParticipation;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P-BL-21 — on ne confirme pas sa présence à une séance annulée.
 *
 * <p>{@code AttendanceService.confirm} vérifiait « terminée », « inscrit » et
 * « pas déjà répondu pour cette occurrence », jamais le statut. Une séance qui
 * n'a pas eu lieu produisait donc des présences {@code CONFIRMED}, et avec elles
 * un compteur de pratique, des badges et une « présence partagée » adossés à un
 * moment que personne n'a vécu.
 */
class AttendanceCancelledSlotIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotParticipationRepository participationRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired AttendanceService attendanceService;

    @Test
    void confirmerSaPresenceAUneSeanceAnnulee_doitEtreRefuse() {
        Fixture f = seanceTerminee(SlotStatus.CANCELLED);

        assertThatThrownBy(() -> attendanceService.confirm(f.participantId, f.slotId, true))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(ErrorCode.SLOT_CANCELLED_NO_ATTENDANCE);

        assertThat(attendanceRepository.existsByScheduleIdAndUserId(f.slotId, f.participantId))
            .as("aucune ligne de présence ne doit rester derrière le refus")
            .isFalse();
    }

    @Test
    void confirmerSaPresenceAUneSeanceTerminee_doitToujoursMarcher() {
        // Non-régression : le refus ne doit porter que sur l'annulation.
        Fixture f = seanceTerminee(SlotStatus.PAST);

        AttendanceDto dto = attendanceService.confirm(f.participantId, f.slotId, false);

        assertThat(dto.wasPresent()).isFalse();
        assertThat(dto.scheduleId()).isEqualTo(f.slotId);
    }

    @Test
    void uneSeanceAnnulee_neDoitPlusEtreProposeeAConfirmer() {
        // Le contrat de getPending dit que rien de ce qu'elle rend ne peut se
        // voir refuser à l'écriture. Sans ce filtre, l'affirmation devenait
        // fausse le jour où confirm s'est mis à refuser les annulées.
        Fixture annulee = seanceTerminee(SlotStatus.CANCELLED);

        assertThat(attendanceService.getPending(annulee.participantId))
            .extracting(PendingAttendanceDto::scheduleId)
            .doesNotContain(annulee.slotId);
    }

    @Test
    void uneSeanceTerminee_doitResterProposeeAConfirmer() {
        Fixture terminee = seanceTerminee(SlotStatus.PAST);

        assertThat(attendanceService.getPending(terminee.participantId))
            .extracting(PendingAttendanceDto::scheduleId)
            .contains(terminee.slotId);
    }

    // — fixture —

    private record Fixture(UUID slotId, UUID participantId) {}

    /**
     * Une séance terminée il y a deux heures, avec un inscrit confirmé.
     *
     * <p>Tout est créé par le test : aucun identifiant fixe, aucune ligne
     * partagée avec une autre méthode — la base ne se vide pas entre deux.
     */
    private Fixture seanceTerminee(SlotStatus status) {
        User host = user("annulation-hote");
        User participant = user("annulation-inscrit");

        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Séance annulée " + UUID.randomUUID())
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Schedule slot = scheduleRepository.save(Schedule.builder()
            .program(program)
            // ONLINE : la contrainte chk_schedule_location_unless_online (V61)
            // exige une position pour tout lieu physique, et ce test ne parle
            // pas de lieu.
            .placeName("Séance en ligne")
            .placeType(PlaceType.ONLINE)
            .startsAt(Instant.now().minus(Duration.ofHours(3)))
            .endsAt(Instant.now().minus(Duration.ofHours(2)))
            .isOpenToPartners(true)
            .status(status)
            .cancelledAt(status == SlotStatus.CANCELLED ? Instant.now().minus(Duration.ofHours(5)) : null)
            .cancellationReason(status == SlotStatus.CANCELLED ? "Salle indisponible" : null)
            .build());

        SlotParticipation participation = new SlotParticipation();
        participation.setSchedule(slot);
        participation.setUser(participant);
        participation.setStatus(ParticipationStatus.CONFIRMED);
        participationRepository.save(participation);

        return new Fixture(slot.getId(), participant.getId());
    }

    private User user(String prefix) {
        return userRepository.save(User.builder()
            .email(uniqueEmail(prefix))
            .passwordHash("x")
            .displayName("Compte de test")
            .isActive(true)
            .build());
    }
}
