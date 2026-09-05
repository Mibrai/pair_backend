package org.program.pair.domain.program;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /slots/mine?upcoming=true} et la séance en cours.
 *
 * <p>Le filtre portait sur {@code startsAt.isAfter(now)} : un créneau quittait
 * « mes créneaux » <b>à la seconde où il démarrait</b>. Mesuré par le client le
 * 03/09 — créneau commencé depuis 45 minutes absent, créneau à +2 h présent.
 *
 * <p>C'est le moment où l'on ouvre l'application pour retrouver l'adresse, et
 * c'est précisément là qu'elle cessait de la donner. La borne se lit désormais
 * sur la fin, avec la convention de {@link SlotTiming} — déclarée, sinon deux
 * heures.
 */
class MySlotsBoundaryIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotService slotService;

    @Test
    void unCreneauEnCours_resteDansMesCreneaux() {
        User host = host();
        UUID enCours = slot(host, Instant.now().minus(Duration.ofMinutes(45)),
            Instant.now().plus(Duration.ofMinutes(15)));

        assertThat(ids(host, true))
            .as("le créneau où l'on se trouve est celui dont on a le plus besoin")
            .contains(enCours);
    }

    @Test
    void unCreneauEnCoursSansFinDeclaree_resteAussi() {
        // endsAt est facultative à la création : la convention de deux heures
        // prend le relais.
        User host = host();
        UUID enCours = slot(host, Instant.now().minus(Duration.ofMinutes(45)), null);

        assertThat(ids(host, true)).contains(enCours);
    }

    @Test
    void unCreneauTermineEnSort() {
        // La correction ne doit pas transformer « mes créneaux » en historique :
        // une fois la séance finie, le créneau en sort comme avant.
        User host = host();
        UUID fini = slot(host, Instant.now().minus(Duration.ofHours(4)),
            Instant.now().minus(Duration.ofHours(2)));

        assertThat(ids(host, true)).doesNotContain(fini);
    }

    @Test
    void unCreneauAVenirYEstToujours() {
        User host = host();
        UUID aVenir = slot(host, Instant.now().plus(Duration.ofHours(2)),
            Instant.now().plus(Duration.ofHours(3)));

        assertThat(ids(host, true)).contains(aVenir);
    }

    @Test
    void sansLeFiltre_lePasseResteVisible() {
        // upcoming=false ne doit rien perdre : c'est la vue historique.
        User host = host();
        UUID fini = slot(host, Instant.now().minus(Duration.ofHours(4)),
            Instant.now().minus(Duration.ofHours(2)));

        assertThat(ids(host, false)).contains(fini);
    }

    // ------------------------------------------------------------------

    private List<UUID> ids(User host, boolean upcoming) {
        return slotService.getMySlots(host.getId(), upcoming).stream()
            .map(SlotFeedItemDto::scheduleId)
            .toList();
    }

    private User host() {
        return userRepository.save(User.builder()
            .email("my-slots-" + UUID.randomUUID() + "@test.meetdo")
            .passwordHash("x")
            .displayName("Hôte de test")
            .isActive(true)
            .build());
    }

    private UUID slot(User host, Instant startsAt, Instant endsAt) {
        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Mes créneaux " + UUID.randomUUID())
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        return scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Lieu de test")
            .placeType(PlaceType.ONLINE)
            .startsAt(startsAt)
            .endsAt(endsAt)
            .isOpenToPartners(true)
            .status(SlotStatus.OPEN)
            .build()).getId();
    }
}
