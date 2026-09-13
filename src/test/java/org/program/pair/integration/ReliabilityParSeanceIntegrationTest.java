package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.attendance.PracticeStatsService;
import org.program.pair.domain.attendance.ReliabilitySignal;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BL-16 — le signal de fiabilité compte des séances des deux côtés de la division.
 *
 * <p>Le numérateur comptait une présence par séance, le dénominateur une
 * inscription par créneau : une série hebdomadaire suivie vingt fois pesait 20
 * en haut et 1 en bas. Décor à Clermont-Ferrand, écrit par les dépôts : le
 * calcul est ce qui est éprouvé, pas le chemin d'inscription.
 */
class ReliabilityParSeanceIntegrationTest extends AbstractIntegrationTest {

    @Autowired PracticeStatsService practiceStatsService;
    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired AttendanceRepository attendanceRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void vingtVenuesSurUneSerieEtQuatreAbsencesAilleurs_doiventCompterParSeance() {
        User personne = utilisateur();
        Schedule serie = creneau(organisateur(), "FREQ=WEEKLY");
        for (int semaine = 0; semaine < 20; semaine++) {
            reponse(personne, serie, Instant.now().minus(7L * semaine + 1, ChronoUnit.DAYS), true);
        }
        Schedule ailleurs = creneau(organisateur(), null);
        for (int i = 0; i < 4; i++) {
            reponse(personne, ailleurs, Instant.now().minus(200L + i, ChronoUnit.DAYS), false);
        }

        practiceStatsService.recalculateFor(personne.getId());

        User relu = userRepository.findById(personne.getId()).orElseThrow();
        assertThat(relu.getJoinedSlotsCount()).as("24 séances répondues").isEqualTo(24);
        assertThat(relu.getAttendanceCount()).isEqualTo(20);
        assertThat(ReliabilitySignal.of(relu.getJoinedSlotsCount(), relu.getAttendanceCount()))
            .as("20 sur 24, au-dessus des quatre sur cinq").isEqualTo("USUALLY_SHOWS_UP");
    }

    @Test
    void leNumerateur_neDoitJamaisDepasserLeDenominateur() {
        User personne = utilisateur();
        Schedule serie = creneau(organisateur(), "FREQ=WEEKLY");
        for (int semaine = 0; semaine < 6; semaine++) {
            reponse(personne, serie, Instant.now().minus(7L * semaine + 1, ChronoUnit.DAYS), true);
        }

        practiceStatsService.recalculateFor(personne.getId());

        User relu = userRepository.findById(personne.getId()).orElseThrow();
        assertThat(relu.getAttendanceCount()).isLessThanOrEqualTo(relu.getJoinedSlotsCount());
    }

    @Test
    void uneReponseSansInscriptionAuCreneau_compteAuDenominateurCommeAuNumerateur() {
        // Le cas des inscrits par programme : leurs présences comptaient en haut,
        // leurs inscriptions (user_programs) n'entraient jamais en bas.
        User personne = utilisateur();
        Schedule creneau = creneau(organisateur(), null);
        reponse(personne, creneau, Instant.now().minus(2, ChronoUnit.DAYS), true);

        practiceStatsService.recalculateFor(personne.getId());

        User relu = userRepository.findById(personne.getId()).orElseThrow();
        assertThat(relu.getJoinedSlotsCount()).isEqualTo(1);
        assertThat(relu.getAttendanceCount()).isEqualTo(1);
    }

    // — décor —

    private void reponse(User personne, Schedule creneau, Instant debut, boolean present) {
        attendanceRepository.save(Attendance.builder()
            .schedule(creneau)
            .user(personne)
            .wasPresent(present)
            .attendedAt(debut)
            .build());
    }

    private Schedule creneau(User hote, String regle) {
        UserActivity ua = userActivityRepository.save(UserActivity.builder()
            .user(hote).activity(activityRepository.findAll().get(0)).build());
        Program programme = programRepository.save(Program.builder()
            .userActivity(ua).title("Fiabilité " + UUID.randomUUID())
            .status(ProgramStatus.ACTIVE).isPublic(true).build());
        Instant debut = Instant.now().minus(1, ChronoUnit.DAYS);
        return scheduleRepository.save(Schedule.builder()
            .program(programme)
            .placeName("Jardin Lecoq")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("Boulevard François Mitterrand, Clermont-Ferrand")
            .location(geometryFactory.createPoint(new Coordinate(3.0863, 45.7722)))
            .startsAt(debut)
            .endsAt(debut.plus(1, ChronoUnit.HOURS))
            .recurrenceRule(regle)
            .status(SlotStatus.PAST)
            .isOpenToPartners(true)
            .build());
    }

    private User organisateur() {
        return utilisateur();
    }

    private User utilisateur() {
        return userRepository.save(User.builder()
            .email(uniqueEmail("fiabilite"))
            .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
            .displayName("Pratiquant")
            .isActive(true)
            .build());
    }
}
