package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.attendance.dto.ConfirmedAttendanceDto;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.recap.RecapVisibility;
import org.program.pair.domain.recap.SlotRecap;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.CategoryRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotRecapRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/attendances/mine} — l'histoire sur laquelle le client calcule
 * ses motifs d'affiche.
 *
 * <p>La classe éprouve <b>la promesse</b> du contrat, et pas seulement la forme
 * de la réponse : une présence confirmée sur une séance qui n'a
 * <b>aucune carte-souvenir</b> doit produire une entrée. C'est la phrase que le
 * client demandait explicitement, et sans ce test elle ne serait qu'une
 * intention — {@code /recaps/mine} ne rend que les cartes portant au moins une
 * contribution, et c'est précisément cette dépendance que la route supprime.
 *
 * <p><b>Décor à soi</b>, comme la base est partagée entre classes : catégorie et
 * activité aux noms uniques — puisque ce sont ces noms que les assertions
 * regardent — et créneaux posés à Grenoble plutôt que sur le point de
 * Strasbourg que huit classes se disputent déjà.
 */
class AttendanceMineIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired SlotRecapRepository recapRepository;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    /** Grenoble : un décor qui n'est disputé par aucune autre classe. */
    private static final Coordinate GRENOBLE = new Coordinate(5.724, 45.188);

    /**
     * La promesse du contrat, et la raison d'être de la route.
     *
     * <p>Deux séances vécues, une seule porte une carte-souvenir. Les deux
     * doivent sortir : lire son histoire à travers les cartes ferait disparaître
     * la seconde, et avec elle le motif qu'elle déclenche.
     */
    @Test
    void unePresenceSurUneSeanceSansCarteSouvenir_figureQuandMemeDansLHistoire() {
        String unique = suffixe();
        String email = uniqueEmail("mine-sans-carte");
        String token = inscritEtConnecte(email);
        User moi = userRepository.findByEmail(email).orElseThrow();

        Program programme = programme("Grimpe " + unique, "Verticale " + unique, "green-teal");

        Instant avecCarte = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant sansCarte = Instant.now().minus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        Schedule creneauAvecCarte = creneau(programme, avecCarte);
        Schedule creneauSansCarte = creneau(programme, sansCarte);

        presence(creneauAvecCarte, moi, avecCarte, true);
        presence(creneauSansCarte, moi, sansCarte, true);

        // Une seule des deux séances a une carte-souvenir. L'autre n'en aura
        // jamais si personne n'y contribue — et c'est le cas majoritaire.
        recapRepository.save(SlotRecap.builder()
            .schedule(creneauAvecCarte)
            .occurrenceStart(avecCarte)
            .occurrenceEnd(avecCarte.plus(1, ChronoUnit.HOURS))
            .visibility(RecapVisibility.PRIVATE)
            .attendeeCount(1)
            .build());

        List<ConfirmedAttendanceDto> histoire = mine(token);

        assertThat(histoire)
            .as("les deux séances vécues, carte ou pas")
            .extracting(ConfirmedAttendanceDto::scheduleId)
            .containsExactly(creneauSansCarte.getId(), creneauAvecCarte.getId());

        ConfirmedAttendanceDto orpheline = histoire.stream()
            .filter(e -> e.scheduleId().equals(creneauSansCarte.getId()))
            .findFirst().orElseThrow();

        assertThat(recapRepository.findByScheduleIdAndOccurrenceStart(
                creneauSansCarte.getId(), sansCarte))
            .as("la séance n'a bien AUCUNE carte-souvenir")
            .isEmpty();
        assertThat(orpheline.slotStartedAt()).isEqualTo(sansCarte);
        assertThat(orpheline.activityName()).isEqualTo("Verticale " + unique);
        assertThat(orpheline.categoryColorRamp()).isEqualTo("green-teal");
        assertThat(orpheline.activityId()).isNotNull();
    }

    /**
     * « Présence confirmée » se lit <i>confirmée présente</i>. Répondre « je n'y
     * étais pas » n'écrit rien dans l'histoire : un motif déclenché sur une
     * séance manquée serait un souvenir inventé.
     */
    @Test
    void repondreQuOnNYEtaitPas_nEcritRienDansLHistoire() {
        String unique = suffixe();
        String email = uniqueEmail("mine-absent");
        String token = inscritEtConnecte(email);
        User moi = userRepository.findByEmail(email).orElseThrow();

        Program programme = programme("Danse " + unique, "Contact " + unique, "pink-rose");

        Instant present = Instant.now().minus(6, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant absent = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        Schedule creneauPresent = creneau(programme, present);
        Schedule creneauAbsent = creneau(programme, absent);

        presence(creneauPresent, moi, present, true);
        presence(creneauAbsent, moi, absent, false);

        assertThat(mine(token))
            .extracting(ConfirmedAttendanceDto::scheduleId)
            .containsExactly(creneauPresent.getId())
            .doesNotContain(creneauAbsent.getId());
    }

    /**
     * De la plus récente à la plus ancienne, et sur la date de la SÉANCE — pas
     * sur celle de la confirmation, qui peut arriver des jours après et dans
     * n'importe quel ordre.
     */
    @Test
    void lHistoireEstRendueDeLaPlusRecenteALaPlusAncienne() {
        String unique = suffixe();
        String email = uniqueEmail("mine-ordre");
        String token = inscritEtConnecte(email);
        User moi = userRepository.findByEmail(email).orElseThrow();

        Program programme = programme("Poterie " + unique, "Tournage " + unique, "amber-brown");

        Instant ancienne = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant moyenne = Instant.now().minus(15, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant recente = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        // Écrites dans le désordre, et confirmées dans un ordre encore différent :
        // c'est attendedAt qui trie, jamais confirmedAt ni l'ordre d'insertion.
        Schedule cMoyenne = creneau(programme, moyenne);
        Schedule cRecente = creneau(programme, recente);
        Schedule cAncienne = creneau(programme, ancienne);

        presence(cMoyenne, moi, moyenne, true);
        presence(cRecente, moi, recente, true);
        presence(cAncienne, moi, ancienne, true);

        assertThat(mine(token))
            .extracting(ConfirmedAttendanceDto::slotStartedAt)
            .containsExactly(recente, moyenne, ancienne);
    }

    /** L'histoire de quelqu'un d'autre ne se mélange jamais à la sienne. */
    @Test
    void chacunNeLitQueSaPropreHistoire() {
        String unique = suffixe();
        String monEmail = uniqueEmail("mine-moi");
        String monToken = inscritEtConnecte(monEmail);
        User moi = userRepository.findByEmail(monEmail).orElseThrow();

        String sonEmail = uniqueEmail("mine-elle");
        String sonToken = inscritEtConnecte(sonEmail);
        User elle = userRepository.findByEmail(sonEmail).orElseThrow();

        Program programme = programme("Course " + unique, "Trail " + unique, "red-orange");

        Instant seance = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Schedule creneau = creneau(programme, seance);

        presence(creneau, moi, seance, true);

        assertThat(mine(monToken))
            .extracting(ConfirmedAttendanceDto::scheduleId)
            .contains(creneau.getId());

        assertThat(mine(sonToken))
            .as("elle n'était pas à cette séance")
            .extracting(ConfirmedAttendanceDto::scheduleId)
            .doesNotContain(creneau.getId());
        assertThat(elle.getId()).isNotEqualTo(moi.getId());
    }

    // ————————————————————————— décor —————————————————————————

    private List<ConfirmedAttendanceDto> mine(String token) {
        return webTestClient.get()
            .uri("/api/attendances/mine")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(ConfirmedAttendanceDto.class)
            .returnResult()
            .getResponseBody();
    }

    private static String suffixe() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Un programme à soi, jusqu'à la catégorie. Le nom de l'activité et la rampe
     * sont ce que les assertions regardent : les emprunter au référentiel ferait
     * dépendre ce test de ce que d'autres classes y ajoutent.
     */
    private Program programme(String nomCategorie, String nomActivite, String rampe) {
        Category categorie = categoryRepository.save(Category.builder()
            .name(nomCategorie)
            .icon("mountain")
            .colorRamp(rampe)
            .build());

        Activity activite = activityRepository.save(Activity.builder()
            .name(nomActivite)
            .slug(nomActivite.toLowerCase().replace(' ', '-'))
            .category(categorie)
            .build());

        User hote = userRepository.save(User.builder()
            .email(uniqueEmail("mine-hote"))
            .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
            .displayName("Hôte " + nomActivite)
            .isActive(true)
            .build());

        UserActivity pratique = userActivityRepository.save(
            UserActivity.builder().user(hote).activity(activite).visibleOnMap(true).build());

        return programRepository.save(Program.builder()
            .userActivity(pratique)
            .title("Programme " + nomActivite)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());
    }

    private Schedule creneau(Program programme, Instant debut) {
        return scheduleRepository.save(Schedule.builder()
            .program(programme)
            .placeName("Salle " + UUID.randomUUID().toString().substring(0, 6))
            .placeType(PlaceType.PUBLIC)
            .city("Grenoble")
            .location(geometryFactory.createPoint(GRENOBLE))
            .startsAt(debut)
            .endsAt(debut.plus(1, ChronoUnit.HOURS))
            .status(SlotStatus.PAST)
            .isOpenToPartners(true)
            .build());
    }

    private void presence(Schedule creneau, User qui, Instant seance, boolean etaitLa) {
        attendanceRepository.save(Attendance.builder()
            .schedule(creneau)
            .user(qui)
            .wasPresent(etaitLa)
            .attendedAt(seance)
            .build());
    }

    private String inscritEtConnecte(String email) {
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", email.split("@")[0]))
            .exchange()
            .expectStatus().isCreated();

        AuthResponse reponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(reponse).isNotNull();
        return reponse.accessToken();
    }
}
