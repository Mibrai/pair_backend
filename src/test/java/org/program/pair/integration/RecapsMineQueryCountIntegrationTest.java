package org.program.pair.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.recap.RecapVisibility;
import org.program.pair.domain.recap.SlotRecap;
import org.program.pair.domain.recap.SlotRecapService;
import org.program.pair.domain.recap.dto.SlotRecapDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotRecapRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Combien de requêtes SQL coûte <b>une carte</b> de {@code GET /recaps/mine} ?
 *
 * <p>Le client mesure 60 à 63 s pour 35 cartes en production, contre 0,88 s pour
 * une réponse à zéro carte : le coût est donc par carte, et il vaut ~1,7 s.
 * Ce n'est pas du calcul — la base est à San Francisco et le service en Europe,
 * si bien qu'un aller-retour JDBC coûte environ 200 ms. <b>1,7 s par carte, ce
 * sont donc huit à neuf allers-retours par carte</b>, et c'est ce nombre-là
 * qu'il faut compter avant de toucher à quoi que ce soit.
 *
 * <p>Une durée mesurée ici ne voudrait rien dire : Testcontainers tourne en
 * local, où l'aller-retour est à 0,1 ms. Le <b>nombre</b> de requêtes, lui, est
 * le même qu'en production — c'est la seule grandeur qui se transporte.
 *
 * <p>La mesure passe par le journal {@code org.hibernate.SQL} plutôt que par les
 * statistiques Hibernate : celles-ci donnent un total, alors qu'on veut savoir
 * <b>lesquelles</b> se répètent. C'est le regroupement par texte qui nomme le
 * coupable, pas le total.
 */
class RecapsMineQueryCountIntegrationTest extends AbstractIntegrationTest {

    @Autowired SlotRecapService recapService;
    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotRecapRepository recapRepository;
    @Autowired AttendanceRepository attendanceRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    /**
     * Le coût marginal d'une carte, mesuré entre deux tailles pour soustraire le
     * coût fixe de la route.
     */
    @Test
    void combienDeRequetesCouteUneCarte() {
        Mesure une = mesure("qc-1", 1);
        Mesure dix = mesure("qc-10", 10);
        // La forme exacte du relevé client : 35 cartes, 3 hôtes, 3 programmes.
        // Mesurée et non extrapolée — une extrapolation linéaire mentirait dans
        // les deux sens, puisque ce qui reste ne croît plus avec les cartes.
        Mesure client = mesure("qc-35", 35);

        int marginal = (dix.requetes - une.requetes) / 9;

        System.out.println("""

            ┌────────────────────────────────────────────────────────────┐
            │  GET /recaps/mine — coût en requêtes SQL                   │
            ├────────────────────────────────────────────────────────────┤
            │   1 carte  : %4d requêtes
            │  10 cartes : %4d requêtes
            │  ---------------------------------------------------------
            │  35 cartes : %4d requêtes   (3 hôtes, 3 programmes)
            │  ---------------------------------------------------------
            │  COÛT MARGINAL PAR CARTE : %d requêtes
            │  À 200 ms l'aller-retour (base US / service EU) :
            │      les 35 cartes du client coûtent ~%.1f s de base
            └────────────────────────────────────────────────────────────┘
            """.formatted(une.requetes, dix.requetes, client.requetes, marginal,
                client.requetes * 0.2));

        System.out.println("Les requêtes qui se répètent le plus (10 cartes) :\n");
        dix.parTexte.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
            .limit(15)
            .forEach(e -> System.out.printf("  %3d ×  %s%n", e.getValue(), resume(e.getKey())));

        assertThat(une.cartes).isEqualTo(1);
        assertThat(dix.cartes).isEqualTo(10);
        assertThat(client.cartes).isEqualTo(35);

        // LE GARDE-FOU. Ce n'est pas la durée qui est verrouillée — elle
        // dépend d'une machine — mais le fait que le coût ne suive PLUS le
        // nombre de cartes. Trente-cinq cartes ne doivent pas coûter plus que
        // dix, aux quelques requêtes près qu'ajoutent des hôtes et des
        // programmes distincts. Le jour où quelqu'un rouvre un N+1 dans le
        // rendu, c'est cette ligne qui le dit — et elle le dit avant la
        // production, qui mettait soixante secondes à le dire.
        assertThat(client.requetes)
            .as("le coût ne doit plus dépendre du nombre de cartes")
            .isLessThanOrEqualTo(dix.requetes + 2);
        assertThat(marginal)
            .as("coût marginal par carte")
            .isZero();
    }

    // ————————————————————————— mesure —————————————————————————

    private record Mesure(int cartes, int requetes, Map<String, Integer> parTexte) {}

    private Mesure mesure(String prefixe, int cartes) {
        UUID lecteur = decor(prefixe, cartes);

        ch.qos.logback.classic.Logger sql =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level niveauInitial = sql.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sql.addAppender(appender);
        sql.setLevel(Level.DEBUG);

        List<SlotRecapDto> rendues;
        try {
            rendues = recapService.getMine(lecteur);
        } finally {
            sql.setLevel(niveauInitial);
            sql.detachAppender(appender);
            appender.stop();
        }

        Map<String, Integer> parTexte = new LinkedHashMap<>();
        for (ILoggingEvent evenement : appender.list) {
            parTexte.merge(evenement.getFormattedMessage(), 1, Integer::sum);
        }
        return new Mesure(rendues.size(), appender.list.size(), parTexte);
    }

    /**
     * Le décor du relevé client, en réduction : trois hôtes pour toutes les
     * cartes — c'est ce qui rend visible un profil d'hôte résolu par carte
     * plutôt que par hôte.
     */
    private UUID decor(String prefixe, int cartes) {
        User lecteur = nouvelUtilisateur(prefixe + "-lecteur");

        List<Program> programmes = new ArrayList<>();
        for (int h = 0; h < Math.min(3, cartes); h++) {
            User hote = nouvelUtilisateur(prefixe + "-hote-" + h);
            Activity activite = activityRepository.findAll().get(0);
            UserActivity userActivity = userActivityRepository.save(
                UserActivity.builder().user(hote).activity(activite).visibleOnMap(true).build());
            programmes.add(programRepository.save(Program.builder()
                .userActivity(userActivity)
                .title("Programme " + prefixe + " " + h)
                .status(ProgramStatus.ACTIVE)
                .isPublic(true)
                .build()));
        }

        // Une séance à venir par programme : sans elle, nextSlot ne trouve rien
        // et la mesure sous-estime la production, où 20 cartes sur 35 en portent
        // une. Un décor plus favorable que le réel donnerait un chiffre faux
        // dans le sens qui rassure — le pire des deux.
        for (Program programme : programmes) {
            scheduleRepository.save(Schedule.builder()
                .program(programme)
                .placeName("Séance à venir")
                .placeType(PlaceType.PUBLIC)
                .city("Lyon")
                .location(geometryFactory.createPoint(new Coordinate(4.85, 45.77)))
                .startsAt(Instant.now().plus(3, ChronoUnit.DAYS))
                .endsAt(Instant.now().plus(3, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS))
                .status(SlotStatus.OPEN)
                .maxParticipants(10)
                .isOpenToPartners(true)
                .build());
        }

        for (int i = 0; i < cartes; i++) {
            Program programme = programmes.get(i % programmes.size());
            Instant debut = Instant.now().minus(i + 2L, ChronoUnit.DAYS);
            Instant fin = debut.plus(1, ChronoUnit.HOURS);

            Schedule creneau = scheduleRepository.save(Schedule.builder()
                .program(programme)
                .placeName("Lieu " + i)
                .placeType(PlaceType.PUBLIC)
                .city("Lyon")
                .location(geometryFactory.createPoint(new Coordinate(4.85, 45.77)))
                .startsAt(debut)
                .endsAt(fin)
                .status(SlotStatus.PAST)
                .isOpenToPartners(true)
                .build());

            attendanceRepository.save(Attendance.builder()
                .schedule(creneau)
                .user(lecteur)
                .wasPresent(true)
                .attendedAt(debut)
                .build());

            recapRepository.save(SlotRecap.builder()
                .schedule(creneau)
                .occurrenceStart(debut)
                .occurrenceEnd(fin)
                .visibility(RecapVisibility.PRIVATE)
                .attendeeCount(1)
                .build());
        }
        return lecteur.getId();
    }

    private User nouvelUtilisateur(String nom) {
        return userRepository.save(User.builder()
            .email(nom + "-" + UUID.randomUUID() + "@pair.app")
            .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
            .displayName(nom)
            .isActive(true)
            .build());
    }

    /** Une requête SQL ramenée à ce qui la nomme : son verbe et sa table. */
    private static String resume(String sql) {
        String plat = sql.replaceAll("\\s+", " ").trim();
        return plat.length() <= 150 ? plat : plat.substring(0, 150) + "…";
    }
}
