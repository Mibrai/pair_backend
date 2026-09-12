package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotParticipation;
import org.program.pair.domain.program.SlotService;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P-BL-14 étape 4 — {@code joinWaitlist} ignorait statut et capacité.
 *
 * <p>La route vérifiait le blocage, le propre créneau, l'ouverture aux
 * partenaires et le début de la séance. Ni {@code CANCELLED} — on pouvait
 * attendre une place sur une séance annulée — ni « réellement complet » — on
 * pouvait attendre derrière un créneau vide, et y rester jusqu'à ce qu'un
 * désistement déclenche une promotion qui n'avait jamais eu lieu d'être
 * attendue.
 */
class SlotWaitlistGuardsIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotParticipationRepository participationRepository;
    @Autowired SlotService slotService;

    @Test
    void entrerEnFileSurUnCreneauAnnule_doitEtreRefuse() {
        Fixture f = creneauComplet(SlotStatus.CANCELLED);

        assertThatThrownBy(() -> slotService.joinWaitlist(f.candidateId, f.slotId))
            .as("introuvable, et non « n'accepte plus de participants » : "
                + "il n'y a rien à attendre d'une séance qui n'aura pas lieu")
            .isInstanceOf(ResourceNotFoundException.class);

        assertThat(participationRepository.findByScheduleIdAndUserId(f.slotId, f.candidateId))
            .isEmpty();
    }

    @Test
    void entrerEnFileSurUnCreneauLibre_doitRenvoyerVersRejoindre() {
        // Deux places, une prise : il reste de la place, donc la file n'a pas
        // d'objet. Le refus est un renvoi — SLOT_NOT_FULL dit d'appeler join.
        Fixture f = creneauAvecUnePlaceLibre();

        assertThatThrownBy(() -> slotService.joinWaitlist(f.candidateId, f.slotId))
            .isInstanceOf(ValidationException.class)
            .extracting(e -> ((ValidationException) e).getErrorCode())
            .isEqualTo(ErrorCode.SLOT_NOT_FULL);
    }

    @Test
    void entrerEnFileSurUnCreneauSansCapacite_doitRenvoyerVersRejoindre() {
        // Un créneau sans maxParticipants n'est jamais complet : sa file n'a
        // aucun sens, et l'y laisser entrer serait promettre une place qu'aucun
        // désistement ne libérera.
        Fixture f = creneauSansCapacite();

        assertThatThrownBy(() -> slotService.joinWaitlist(f.candidateId, f.slotId))
            .isInstanceOf(ValidationException.class)
            .extracting(e -> ((ValidationException) e).getErrorCode())
            .isEqualTo(ErrorCode.SLOT_NOT_FULL);
    }

    @Test
    void entrerEnFileSurUnCreneauComplet_doitToujoursMarcher() {
        // Non-régression : c'est le cas pour lequel la route existe.
        Fixture f = creneauComplet(SlotStatus.FULL);

        SlotFeedItemDto vu = slotService.joinWaitlist(f.candidateId, f.slotId);

        assertThat(vu.myParticipationStatus()).isEqualTo("WAITLISTED");
        assertThat(vu.myWaitlistPosition()).isEqualTo(1);
    }

    @Test
    void entrerEnFileSurUneSeanceCommencee_doitEtreRefuse() {
        // Inchangé, et vérifié ici parce que le contrôle a déménagé dans la garde
        // partagée : la frontière du « trop tard » reste le début de la séance.
        Fixture f = creneauCommenceEtComplet();

        assertThatThrownBy(() -> slotService.joinWaitlist(f.candidateId, f.slotId))
            .isInstanceOf(ValidationException.class)
            .extracting(e -> ((ValidationException) e).getErrorCode())
            .isEqualTo(ErrorCode.SLOT_ALREADY_STARTED);
    }

    // — fixtures —

    private record Fixture(UUID slotId, UUID candidateId) {}

    private Fixture creneauComplet(SlotStatus status) {
        return creneau(status, 1, 1, Instant.now().plus(Duration.ofDays(2)));
    }

    private Fixture creneauAvecUnePlaceLibre() {
        return creneau(SlotStatus.OPEN, 2, 1, Instant.now().plus(Duration.ofDays(2)));
    }

    private Fixture creneauSansCapacite() {
        return creneau(SlotStatus.OPEN, null, 0, Instant.now().plus(Duration.ofDays(2)));
    }

    private Fixture creneauCommenceEtComplet() {
        return creneau(SlotStatus.OPEN, 1, 1, Instant.now().minus(Duration.ofMinutes(30)));
    }

    /**
     * Un créneau de {@code maxParticipants} places dont {@code occupants} sont
     * prises, plus un candidat qui n'a encore rien.
     */
    private Fixture creneau(SlotStatus status, Integer maxParticipants, int occupants, Instant startsAt) {
        User host = user("file-hote");

        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("File d'attente " + UUID.randomUUID())
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Schedule slot = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Séance en ligne")
            .placeType(PlaceType.ONLINE)
            .startsAt(startsAt)
            .endsAt(startsAt.plus(Duration.ofHours(1)))
            .maxParticipants(maxParticipants)
            .isOpenToPartners(true)
            .status(status)
            .cancelledAt(status == SlotStatus.CANCELLED ? Instant.now() : null)
            .build());

        for (int i = 0; i < occupants; i++) {
            SlotParticipation occupant = new SlotParticipation();
            occupant.setSchedule(slot);
            occupant.setUser(user("file-occupant"));
            occupant.setStatus(ParticipationStatus.CONFIRMED);
            participationRepository.save(occupant);
        }

        return new Fixture(slot.getId(), user("file-candidat").getId());
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
