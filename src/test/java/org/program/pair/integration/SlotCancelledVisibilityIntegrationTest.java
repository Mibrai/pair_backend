package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
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
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BL-08 — un créneau annulé le dit, dans « Mes créneaux » comme sur sa fiche.
 *
 * <p>{@code SlotFeedItemDto} ne portait ni {@code status}, ni {@code cancelledAt},
 * ni {@code cancellationReason} : un créneau annulé était strictement
 * indiscernable d'un créneau normal, {@code startsAt} et {@code endsAt}
 * continuant d'annoncer une séance qui n'aurait pas lieu. L'app était déjà prête
 * à lire les trois champs.
 *
 * <p>Le volet lieu est le seul <b>retrait</b> du lot : un créneau annulé ne rend
 * plus ni coordonnées ni adresse exacte, parce qu'il n'y a plus de raison d'y
 * aller. {@code placeName} reste, et avec lui de quoi reconnaître la séance dont
 * on parle.
 */
class SlotCancelledVisibilityIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotParticipationRepository participationRepository;
    @Autowired SlotService slotService;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void unCreneauAnnule_doitSeLireCommeAnnuleDansMesCreneaux() {
        Fixture f = creneauRejoint(SlotStatus.CANCELLED);

        SlotFeedItemDto vu = mesCreneaux(f.participantId, f.slotId);

        assertThat(vu).as("un créneau annulé reste dans Mes créneaux : "
            + "on doit pouvoir ouvrir ce qu'annonce la notification").isNotNull();
        assertThat(vu.status()).isEqualTo("CANCELLED");
        assertThat(vu.cancelledAt()).isNotNull();
        assertThat(vu.cancellationReason()).isEqualTo("Orage annoncé");
    }

    @Test
    void unCreneauNormal_doitPorterSonStatut_etAucuneAnnulation() {
        Fixture f = creneauRejoint(SlotStatus.OPEN);

        SlotFeedItemDto vu = mesCreneaux(f.participantId, f.slotId);

        assertThat(vu).isNotNull();
        assertThat(vu.status()).isEqualTo("OPEN");
        assertThat(vu.cancelledAt()).isNull();
        assertThat(vu.cancellationReason()).isNull();
    }

    @Test
    void laFicheDUnCreneauAnnule_doitPorterLeMotif() {
        Fixture f = creneauRejoint(SlotStatus.CANCELLED);

        SlotFeedItemDto fiche = slotService.getSlot(f.slotId, f.participantId);

        assertThat(fiche.status()).isEqualTo("CANCELLED");
        assertThat(fiche.cancellationReason()).isEqualTo("Orage annoncé");
        assertThat(fiche.cancelledAt()).isNotNull();
    }

    @Test
    void unCreneauAnnule_neDoitPlusDonnerLAdresseExacte_aUnInscrit() {
        // Le lieu est PUBLIC : l'inscrit — et n'importe qui — y aurait droit sans
        // l'annulation. C'est donc bien l'annulation qui retire l'adresse, et pas
        // une règle de visibilité déjà en place.
        Fixture f = creneauRejoint(SlotStatus.CANCELLED);

        SlotFeedItemDto fiche = slotService.getSlot(f.slotId, f.participantId);

        assertThat(fiche.lat()).isNull();
        assertThat(fiche.lng()).isNull();
        assertThat(fiche.displayAddress()).isNull();
        assertThat(fiche.placeName())
            .as("le nom du lieu reste : il sert à reconnaître la séance dont on parle")
            .isEqualTo("Parc de l'Orangerie");
    }

    @Test
    void unCreneauNonAnnuleAuLieuPublic_doitToujoursDonnerSonAdresse() {
        // Non-régression du retrait ci-dessus : la règle ne doit pas déborder.
        Fixture f = creneauRejoint(SlotStatus.OPEN);

        SlotFeedItemDto fiche = slotService.getSlot(f.slotId, f.participantId);

        assertThat(fiche.lat()).isNotNull();
        assertThat(fiche.lng()).isNotNull();
        assertThat(fiche.displayAddress()).isEqualTo("1 avenue de l'Europe");
    }

    // — fixture —

    private record Fixture(UUID slotId, UUID participantId) {}

    private SlotFeedItemDto mesCreneaux(UUID userId, UUID slotId) {
        return slotService.getMySlots(userId, true).stream()
            .filter(s -> s.scheduleId().equals(slotId))
            .findFirst()
            .orElse(null);
    }

    /** Un créneau à venir, au lieu public, rejoint par un second compte. */
    private Fixture creneauRejoint(SlotStatus status) {
        User host = user("annonce-hote");
        User participant = user("annonce-inscrit");

        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Créneau annoncé " + UUID.randomUUID())
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Schedule slot = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Parc de l'Orangerie")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 avenue de l'Europe")
            .location(geometryFactory.createPoint(new Coordinate(7.7521, 48.5734)))
            .startsAt(Instant.now().plus(Duration.ofDays(2)))
            .endsAt(Instant.now().plus(Duration.ofDays(2)).plus(Duration.ofHours(1)))
            .isOpenToPartners(true)
            .status(status)
            .cancelledAt(status == SlotStatus.CANCELLED ? Instant.now() : null)
            .cancellationReason(status == SlotStatus.CANCELLED ? "Orage annoncé" : null)
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
