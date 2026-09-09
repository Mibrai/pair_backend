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
 * <p>La classe éprouve ensuite <b>de quoi la route permet de trancher</b> :
 * {@code categoryName}, {@code cityLabel} et {@code hostId} n'y sont pas pour
 * être affichés — cette liste ne s'affiche pas — mais pour qu'un module qui juge
 * une transition puisse la réfuter au lieu de l'affirmer. Le cas le plus parlant
 * est reproduit tel quel : deux activités distinctes sous une même catégorie,
 * qui est la forme exacte des trois affiches fausses qu'a mesurées le client.
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

    // ————————————— les trois colonnes d'arbitrage (B13) —————————————

    /**
     * Les trois colonnes que le client réclamait, et ce qu'elles servent.
     *
     * <p>Elles ne sont pas là pour être <b>affichées</b> — cette liste ne
     * s'affiche pas, elle se calcule dessus. Elles sont là pour qu'un module qui
     * juge une transition puisse la <b>trancher</b> : la catégorie de cette
     * séance se compare à celles des séances antérieures, sa ville aux villes
     * déjà vues, son hôte aux hôtes déjà rencontrés. Sans elles, la seule issue
     * était d'affirmer sans savoir.
     */
    @Test
    void lHistoirePorteDeQuoiArbitrerLaCategorieLaVilleEtLHote() {
        String unique = suffixe();
        String email = uniqueEmail("mine-arbitrage");
        String token = inscritEtConnecte(email);
        User moi = userRepository.findByEmail(email).orElseThrow();

        Program programme = programme("Cinéphilie " + unique, "Ciné-club " + unique, "orange-red");
        UUID hote = programme.getUserActivity().getUser().getId();

        Instant seance = Instant.now().minus(6, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Schedule creneau = creneau(programme, seance);
        presence(creneau, moi, seance, true);

        ConfirmedAttendanceDto entree = uneEntree(mine(token), creneau.getId());

        assertThat(entree.categoryName()).isEqualTo("Cinéphilie " + unique);
        assertThat(entree.cityLabel()).isEqualTo("Grenoble");
        assertThat(entree.hostId())
            .as("l'auteur du programme est l'hôte de la séance")
            .isEqualTo(hote);

        // La précision que le client demandait explicitement pour éviter un
        // aller-retour : une rampe n'est pas un nom, et « première fois en
        // orange-red » n'est pas une phrase.
        assertThat(entree.categoryName()).isNotEqualTo(entree.categoryColorRamp());
        assertThat(entree.categoryColorRamp()).isEqualTo("orange-red");
    }

    /**
     * Le défaut que ces colonnes ferment, reproduit en entier.
     *
     * <p>Deux séances, deux <b>activités distinctes</b>, une <b>même
     * catégorie</b> — c'est la forme exacte des trois affiches fausses qu'a
     * mesurées le client. {@code activityName} suffisait à réfuter une première
     * pratique ; il ne dit rien d'une première catégorie, et la sélection
     * descendait d'un rang pour retomber sur un motif que rien n'arbitrait.
     *
     * <p>Ici les deux entrées portent le même {@code categoryName} et des
     * {@code activityName} différents : la seconde séance est réfutable, et elle
     * ne l'était pas hier.
     */
    @Test
    void deuxActivitesDUneMemeCategorie_portentLeMemeNomDeCategorie() {
        String unique = suffixe();
        String email = uniqueEmail("mine-meme-categorie");
        String token = inscritEtConnecte(email);
        User moi = userRepository.findByEmail(email).orElseThrow();

        Category categorie = categoryRepository.save(Category.builder()
            .name("Écrans " + unique)
            .icon("movie")
            .colorRamp("blue-purple")
            .build());

        Program cinema = programme(categorie, "Cinéma " + unique);
        Program serie = programme(categorie, "Séries " + unique);

        Instant premiere = Instant.now().minus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant seconde = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        Schedule creneauCinema = creneau(cinema, premiere);
        Schedule creneauSerie = creneau(serie, seconde);
        presence(creneauCinema, moi, premiere, true);
        presence(creneauSerie, moi, seconde, true);

        List<ConfirmedAttendanceDto> histoire = mine(token);
        ConfirmedAttendanceDto ancienne = uneEntree(histoire, creneauCinema.getId());
        ConfirmedAttendanceDto recente = uneEntree(histoire, creneauSerie.getId());

        assertThat(recente.activityName())
            .as("deux pratiques différentes : la première PRATIQUE reste vraie")
            .isNotEqualTo(ancienne.activityName());
        assertThat(recente.categoryName())
            .as("mais la même catégorie — c'est ce qui réfute la première CATÉGORIE")
            .isEqualTo(ancienne.categoryName())
            .isEqualTo("Écrans " + unique);
    }

    /**
     * {@code premiereFoisHote} — « la première fois que tu as posé un créneau
     * toi-même », le motif de rang 1 le plus rare.
     *
     * <p>Il ne se lit nulle part ailleurs : une séance qu'on a organisée et une
     * séance où l'on est allé sont deux lignes identiques dans cette liste, à
     * l'hôte près. On y est présent comme les autres — c'est {@code hostId} qui
     * les distingue, et rien d'autre.
     */
    @Test
    void laSeanceQuOnOrganiseSoiMeme_seReconnaitALHote() {
        String unique = suffixe();
        String email = uniqueEmail("mine-hote-soi");
        String token = inscritEtConnecte(email);
        User moi = userRepository.findByEmail(email).orElseThrow();

        Program leMien = programmePourHote(moi, "Course " + unique, "Trail " + unique, "green-teal");
        Program celuiDUnAutre = programme("Nage " + unique, "Bassin " + unique, "blue-purple");

        Instant chezMoi = Instant.now().minus(9, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant chezLAutre = Instant.now().minus(8, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        Schedule creneauMien = creneau(leMien, chezMoi);
        Schedule creneauAutre = creneau(celuiDUnAutre, chezLAutre);
        presence(creneauMien, moi, chezMoi, true);
        presence(creneauAutre, moi, chezLAutre, true);

        List<ConfirmedAttendanceDto> histoire = mine(token);

        assertThat(uneEntree(histoire, creneauMien.getId()).hostId())
            .as("on est l'hôte de ce qu'on organise, et présent comme les autres")
            .isEqualTo(moi.getId());
        assertThat(uneEntree(histoire, creneauAutre.getId()).hostId())
            .as("chez quelqu'un d'autre, ce n'est pas soi")
            .isNotEqualTo(moi.getId());
    }

    /**
     * Une ville absente reste nulle. Elle n'est jamais devinée à partir des
     * coordonnées, alors que le créneau en porte : une ville devinée ferait
     * naître une « première fois à Grenoble » qui n'a pas eu lieu, et c'est
     * exactement la classe d'erreur que ce lot vient fermer.
     */
    @Test
    void uneVilleNonRenseignee_resteNulle_plutotQueDevinee() {
        String unique = suffixe();
        String email = uniqueEmail("mine-sans-ville");
        String token = inscritEtConnecte(email);
        User moi = userRepository.findByEmail(email).orElseThrow();

        Program programme = programme("Randonnée " + unique, "Sentier " + unique, "green-teal");
        Instant seance = Instant.now().minus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        Schedule sansVille = scheduleRepository.save(Schedule.builder()
            .program(programme)
            .placeName("Refuge " + unique)
            .placeType(PlaceType.PUBLIC)
            // Pas de .city(...), et pourtant un point : c'est le cas où deviner
            // serait tentant.
            .location(geometryFactory.createPoint(GRENOBLE))
            .startsAt(seance)
            .endsAt(seance.plus(1, ChronoUnit.HOURS))
            .status(SlotStatus.PAST)
            .isOpenToPartners(true)
            .build());
        presence(sansVille, moi, seance, true);

        assertThat(uneEntree(mine(token), sansVille.getId()).cityLabel()).isNull();
    }

    // ————————————————————————— décor —————————————————————————

    /** L'entrée d'un créneau donné, ou l'échec du test si l'histoire l'a perdue. */
    private static ConfirmedAttendanceDto uneEntree(List<ConfirmedAttendanceDto> histoire, UUID creneau) {
        assertThat(histoire).isNotNull();
        return histoire.stream()
            .filter(e -> e.scheduleId().equals(creneau))
            .findFirst()
            .orElseThrow(() -> new AssertionError("le créneau " + creneau + " manque à l'histoire"));
    }

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
        return programme(categorie, nomActivite);
    }

    /**
     * Un programme dans une catégorie <b>déjà créée</b> — ce qu'il faut pour
     * poser deux activités distinctes sous une même catégorie, la forme exacte du
     * défaut que {@code categoryName} vient fermer.
     */
    private Program programme(Category categorie, String nomActivite) {
        User hote = userRepository.save(User.builder()
            .email(uniqueEmail("mine-hote"))
            .passwordHash("$2a$10$neverusedbecausethisuserneverlogsin0000000000000000000")
            .displayName("Hôte " + nomActivite)
            .isActive(true)
            .build());
        return programmePourHote(hote, categorie, nomActivite);
    }

    /** Un programme dont l'hôte est désigné — pour la séance qu'on organise soi-même. */
    private Program programmePourHote(User hote, String nomCategorie, String nomActivite, String rampe) {
        Category categorie = categoryRepository.save(Category.builder()
            .name(nomCategorie)
            .icon("mountain")
            .colorRamp(rampe)
            .build());
        return programmePourHote(hote, categorie, nomActivite);
    }

    private Program programmePourHote(User hote, Category categorie, String nomActivite) {
        Activity activite = activityRepository.save(Activity.builder()
            .name(nomActivite)
            .slug(nomActivite.toLowerCase().replace(' ', '-'))
            .category(categorie)
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
