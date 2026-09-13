package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
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
 * P-BA-20 — les requêtes de {@code ScheduleRepository} dont dépendent les jobs.
 *
 * <p>La fiche demandait une tranche {@code @DataJpaTest}. Ce test garde le contexte
 * partagé d'{@code AbstractIntegrationTest} : un contexte de plus coûte des
 * minutes à la suite et évince les autres du cache, pour éprouver exactement les
 * mêmes requêtes sur le même conteneur. Les résultats sont filtrés sur les
 * créneaux du test — la base est partagée.
 */
class ScheduleRepositoryRequetesIntegrationTest extends AbstractIntegrationTest {

    @Autowired ScheduleRepository scheduleRepository;
    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void lesCreneauxTerminesEntreDeuxInstants_sontRetrouves_avecOuSansFinDeclaree() {
        Program programme = programme();
        Instant maintenant = Instant.now();
        Schedule avecFin = creneau(programme, maintenant.minus(3, ChronoUnit.HOURS),
            maintenant.minus(2, ChronoUnit.HOURS), SlotStatus.OPEN, null);
        Schedule sansFin = creneau(programme, maintenant.minus(4, ChronoUnit.HOURS), null, SlotStatus.OPEN, null);
        Schedule horsFenetre = creneau(programme, maintenant.minus(30, ChronoUnit.HOURS),
            maintenant.minus(29, ChronoUnit.HOURS), SlotStatus.OPEN, null);

        var trouves = scheduleRepository.findFinishedBetween(
            maintenant.minus(3, ChronoUnit.HOURS), maintenant,
            maintenant.minus(5, ChronoUnit.HOURS), maintenant.minus(2, ChronoUnit.HOURS))
            .stream().map(Schedule::getId).toList();

        assertThat(trouves).contains(avecFin.getId(), sansFin.getId()).doesNotContain(horsFenetre.getId());
    }

    @Test
    void leRollover_neRetrouvePasUnCreneauAnnule() {
        Program programme = programme();
        Instant hier = Instant.now().minus(1, ChronoUnit.DAYS);
        Schedule ouvert = creneau(programme, hier, hier.plus(1, ChronoUnit.HOURS), SlotStatus.OPEN, "FREQ=WEEKLY");
        Schedule annule = creneau(programme, hier, hier.plus(1, ChronoUnit.HOURS), SlotStatus.CANCELLED, "FREQ=WEEKLY");

        var trouves = scheduleRepository.findRecurringStartedBefore(Instant.now())
            .stream().map(Schedule::getId).toList();

        assertThat(trouves).contains(ouvert.getId()).doesNotContain(annule.getId());
    }

    private Schedule creneau(Program programme, Instant debut, Instant fin, SlotStatus statut, String regle) {
        return scheduleRepository.save(Schedule.builder()
            .program(programme)
            .placeName("Parc de la Tête d'Or")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("Boulevard des Belges, Lyon")
            .location(geometryFactory.createPoint(new Coordinate(4.8520, 45.7772)))
            .startsAt(debut)
            .endsAt(fin)
            .recurrenceRule(regle)
            .status(statut)
            .isOpenToPartners(true)
            .build());
    }

    private Program programme() {
        User hote = userRepository.save(User.builder()
            .email(uniqueEmail("requetes-creneaux"))
            .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
            .displayName("Hôte")
            .isActive(true)
            .build());
        UserActivity ua = userActivityRepository.save(UserActivity.builder()
            .user(hote).activity(activityRepository.findAll().get(0)).build());
        return programRepository.save(Program.builder()
            .userActivity(ua).title("Requêtes " + UUID.randomUUID())
            .status(ProgramStatus.ACTIVE).isPublic(true).build());
    }
}
