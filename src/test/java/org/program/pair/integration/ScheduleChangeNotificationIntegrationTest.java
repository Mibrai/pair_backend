package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * P-BL-06 — changer l'heure ou le lieu prévient enfin chaque inscrit.
 *
 * <p><b>Le défaut.</b> {@code updateSchedule} écrivait lieu, type, coordonnées,
 * adresse, {@code startsAt} et {@code endsAt} sans un appel à
 * {@code notificationService}. {@code SCHEDULE_CHANGED} existait dans
 * l'énumération, était classé critique et destiné à l'e-mail, et n'avait
 * <b>aucun producteur</b>. L'organisateur avançait sa séance d'une heure, la
 * voyait bouger sur son écran, et trois personnes se présentaient à l'ancienne.
 *
 * <p><b>Ce que ces tests surveillent surtout, c'est le silence.</b> Trois des six
 * portent sur ce qui ne doit <b>pas</b> partir : la note d'accueil corrigée, la
 * modification refusée, et l'organisateur lui-même. Un canal critique qui parle
 * pour rien est un canal qu'on finit par couper — annulations comprises, qui en
 * sont la raison d'être.
 *
 * <p>La base est partagée par toute la suite : chaque méthode crée ses comptes,
 * son programme et son créneau, et n'interroge que ses propres identifiants.
 */
class ScheduleChangeNotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    /**
     * L'ancienne heure voyage avec la nouvelle, et c'est tout l'intérêt.
     *
     * <p>« La séance est modifiée » n'aide personne à décider s'il doit se
     * réorganiser ; « avancée à 18 h, au lieu de 19 h » oui. Le client compose la
     * phrase — il a la langue et le fuseau de l'appareil — à partir de
     * {@code previousStartsAt}, que seule cette notification peut lui donner : une
     * fois la ligne réécrite, l'ancienne heure n'existe plus nulle part.
     */
    @Test
    void avancerLHeure_doitPrevenirChaqueInscrit_avecLAncienneHeure() {
        Terrain terrain = terrain(PlaceType.PUBLIC, true, 5);
        Compte inscrit = compte();
        rejoindre(inscrit, terrain);

        Instant ancienDebut = terrain.debut();
        Instant nouveauDebut = ancienDebut.minus(1, ChronoUnit.HOURS);
        modifier(terrain, Map.of("startsAt", nouveauDebut.toString()));

        Map<String, Object> payload = dernierePayload(inscrit);

        // Seule l'heure a bougé : le lieu n'est pas annoncé.
        assertThat(champsModifies(inscrit)).containsExactly("TIME");
        assertThat(payload.get("previousStartsAt")).isEqualTo(ancienDebut.toString());
        // La nouvelle valeur y est aussi : la charge décrit l'état courant,
        // l'ancienne n'étant là que pour la phrase.
        assertThat(payload.get("sessionAt")).isEqualTo(nouveauDebut.toString());
    }

    /**
     * Un changement de lieu prévient aussi la file d'attente.
     *
     * <p>Quelqu'un qui attend une place a organisé sa journée autour de ce créneau
     * autant qu'un inscrit : le laisser monter vers l'ancienne adresse serait le
     * seul cas où être promu coûte un déplacement pour rien.
     */
    @Test
    void changerLeLieu_doitPrevenirLaFileDAttenteAussi() {
        Terrain terrain = terrain(PlaceType.PUBLIC, true, 1);
        Compte occupant = compte();
        rejoindre(occupant, terrain);

        Compte enFile = compte();
        webTestClient.post().uri("/api/slots/{id}/waitlist", terrain.scheduleId())
            .headers(h -> h.setBearerAuth(enFile.token()))
            .exchange().expectStatus().isCreated();

        modifier(terrain, Map.of("placeName", "Gymnase Victor-Hugo",
            "lat", 48.58, "lng", 7.76));

        assertThat(champsModifies(enFile)).contains("PLACE");
        assertThat(champsModifies(occupant)).contains("PLACE");
    }

    /**
     * Corriger la note d'accueil ne prévient personne.
     *
     * <p>La règle de détection ne retient que l'heure et le lieu, et c'est le
     * garde-fou de tout ce lot : la note d'accueil, la langue, les étiquettes
     * d'accessibilité, la capacité ne changent ni quand ni où il faut être. Les
     * annoncer ferait d'une faute de frappe corrigée un e-mail à tous les
     * inscrits, et trois e-mails inutiles font couper le canal.
     */
    @Test
    void changerLaNoteDAccueil_neDoitPrevenirPersonne() {
        Terrain terrain = terrain(PlaceType.PUBLIC, true, 5);
        Compte inscrit = compte();
        rejoindre(inscrit, terrain);

        modifier(terrain, Map.of("welcomeNote", "Apportez un tapis."));

        rienNArrive(inscrit);
    }

    /**
     * Une modification refusée n'envoie rien — <b>et c'est la raison de l'écouteur
     * après commit</b>.
     *
     * <p>{@code notify} est {@code @Async} : appelée depuis {@code updateSchedule},
     * elle part avant le commit et rien ne la rattrape s'il n'a pas lieu. On
     * annoncerait alors « votre séance est avancée à 18 h » pour un horaire qui n'a
     * jamais été enregistré, et la personne se présenterait à la mauvaise heure sur
     * la foi de notre propre message.
     */
    @Test
    void uneModificationRefusee_neDoitRienEnvoyer() {
        Terrain terrain = terrain(PlaceType.PUBLIC, true, 5);
        Compte inscrit = compte();
        rejoindre(inscrit, terrain);

        Instant nouveauDebut = terrain.debut().plus(1, ChronoUnit.HOURS);
        webTestClient.put()
            .uri("/api/programs/{programId}/schedules/{scheduleId}",
                terrain.programId(), terrain.scheduleId())
            .headers(h -> h.setBearerAuth(terrain.hote().token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "startsAt", nouveauDebut.toString(),
                // La fin avant le début : refusé après application des champs.
                "endsAt", nouveauDebut.minus(30, ChronoUnit.MINUTES).toString()))
            .exchange().expectStatus().isBadRequest();

        // L'horaire n'a pas bougé, et personne n'a rien reçu.
        assertThat(scheduleRepository.findById(terrain.scheduleId()).orElseThrow().getStartsAt())
            .isEqualTo(terrain.debut());
        rienNArrive(inscrit);
    }

    /** L'organisateur sait ce qu'il vient de faire : se le voir annoncer le renseignerait mal. */
    @Test
    void lOrganisateur_neDoitPasRecevoirSaPropreModification() {
        Terrain terrain = terrain(PlaceType.PUBLIC, true, 5);
        Compte inscrit = compte();
        rejoindre(inscrit, terrain);

        modifier(terrain, Map.of("startsAt",
            terrain.debut().plus(2, ChronoUnit.HOURS).toString()));

        // On attend d'abord que l'envoi destiné à l'inscrit soit arrivé : sans
        // cela, « l'organisateur n'a rien reçu » serait vrai simplement parce que
        // rien n'est encore parti.
        attendreUneNotification(inscrit);
        assertThat(compter(terrain.hote())).isZero();
    }

    /**
     * Une adresse qui n'était pas diffusable ne part pas comme « ancienne adresse ».
     *
     * <p>C'est le point le plus délicat de la charge utile. Le créneau est privé et
     * n'assume pas son adresse exacte : personne, hors participant confirmé, ne la
     * voit sur la fiche. Publier l'ancienne dans une notification serait le chemin
     * par lequel la règle de visibilité se contourne — et un changement de lieu est
     * précisément le geste qui peut faire passer un créneau de public à privé.
     */
    @Test
    void uneAdressePriveeAnterieure_neDoitPasPartirDansLePayload() {
        String adresseSecrete = "13 rue Confidentielle";
        Terrain terrain = terrain(PlaceType.PRIVATE, false, 5, adresseSecrete);
        Compte inscrit = compte();
        rejoindre(inscrit, terrain);

        modifier(terrain, Map.of("placeName", "Autre salle", "lat", 48.60, "lng", 7.80));

        assertThat(champsModifies(inscrit)).contains("PLACE");

        // Les clés d'adresse sont ABSENTES, et non vides : NotificationPayload
        // n'écrit jamais une valeur nulle, et le client teste une présence.
        assertThat(dernierePayload(inscrit))
            .doesNotContainKey("previousAddress")
            .doesNotContainKey("addressPublic")
            // Ni coordonnées, jamais : ni les anciennes, ni les nouvelles.
            .doesNotContainKey("lat")
            .doesNotContainKey("lng");

        // Et l'adresse n'a pas fui sous un autre nom : la charge entière est lue.
        assertThat(chargeEntiere(inscrit)).doesNotContain(adresseSecrete);
    }

    // ------------------------------------------------------------------ outils

    private record Compte(UUID id, String token) {}

    private record Terrain(Compte hote, UUID programId, UUID scheduleId, Instant debut) {}

    /** Un hôte, un programme actif, un créneau à venir — créés pour cette méthode seule. */
    private Terrain terrain(PlaceType type, boolean adresseVisible, int places) {
        return terrain(type, adresseVisible, places, "1 avenue de l'Europe");
    }

    private Terrain terrain(PlaceType type, boolean adresseVisible, int places, String adresse) {
        Compte hote = compte();
        User host = userRepository.findById(hote.id()).orElseThrow();
        Activity activity = activityRepository.findAll().get(0);
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Horaire " + UUID.randomUUID().toString().substring(0, 8))
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .allowParticipantMessages(true)
            .build());

        Instant debut = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Studio " + UUID.randomUUID().toString().substring(0, 6))
            .placeType(type)
            .addressPublic(adresse)
            .showExactAddress(adresseVisible)
            .location(geometryFactory.createPoint(new Coordinate(7.7521, 48.5734)))
            .startsAt(debut)
            .endsAt(debut.plus(java.time.Duration.ofHours(6))) // large : les tests repoussent le début sans toucher la fin
            .maxParticipants(places)
            .isOpenToPartners(true)
            .build());

        return new Terrain(hote, program.getId(), schedule.getId(), debut);
    }

    private void modifier(Terrain terrain, Map<String, Object> champs) {
        webTestClient.put()
            .uri("/api/programs/{programId}/schedules/{scheduleId}",
                terrain.programId(), terrain.scheduleId())
            .headers(h -> h.setBearerAuth(terrain.hote().token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new HashMap<>(champs))
            .exchange().expectStatus().isOk();
    }

    private void rejoindre(Compte qui, Terrain terrain) {
        webTestClient.post().uri("/api/slots/{id}/join", terrain.scheduleId())
            .headers(h -> h.setBearerAuth(qui.token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isCreated();
    }

    /**
     * Attend qu'une modification soit arrivée chez cette personne.
     *
     * <p>Deux asynchronies se cumulent : l'écouteur attend le commit, puis
     * {@code notify} est {@code @Async}. Interroger la base aussitôt après le
     * {@code PUT} lit donc parfois zéro — un test qui passe ou échoue selon la
     * charge de la machine.
     */
    private void attendreUneNotification(Compte qui) {
        await().atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(100))
            .until(() -> compter(qui) > 0);
    }

    /**
     * La dernière charge utile reçue, <b>désérialisée</b>.
     *
     * <p>Même façon de faire que {@code AttendancePromptIntegrationTest} : la
     * colonne {@code payload} est lue en chaîne puis relue par
     * {@code objectMapper}. C'est ce qui donne un accès par clé et par type — une
     * clé absente se distingue d'une clé vide, {@code changedFields} redevient une
     * liste — là où chercher une sous-chaîne dans le JSON entier confondrait les
     * deux et dépendrait du contenu voisin : la charge porte le nom de l'activité,
     * qui vient de la base, et « Pilates » contient « lat ».
     *
     * <p>{@code ORDER BY sent_at} et non {@code created_at}, qui n'existe pas sur
     * cette table — c'est la faute que PostgreSQL a refusée.
     */
    private Map<String, Object> dernierePayload(Compte qui) {
        attendreUneNotification(qui);
        String json = jdbcTemplate.queryForObject("""
            SELECT payload FROM notifications
            WHERE user_id = ? AND type = ?
            ORDER BY sent_at DESC LIMIT 1
            """, String.class, qui.id(), NotificationType.SCHEDULE_CHANGED.name());
        assertThat(json).isNotNull();
        try {
            return objectMapper.readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("payload illisible : " + json, e);
        }
    }

    /** Ce que la charge annonce comme ayant changé. */
    @SuppressWarnings("unchecked")
    private List<String> champsModifies(Compte qui) {
        return (List<String>) dernierePayload(qui).get("changedFields");
    }

    /**
     * La charge utile telle quelle, pour ce qui doit en être <b>absent</b>.
     *
     * <p>Une lecture par clé ne peut pas prouver qu'une adresse n'a pas fui sous
     * un autre nom ; celle-ci si.
     */
    private String chargeEntiere(Compte qui) {
        attendreUneNotification(qui);
        return jdbcTemplate.queryForObject("""
            SELECT payload FROM notifications
            WHERE user_id = ? AND type = ?
            ORDER BY sent_at DESC LIMIT 1
            """, String.class, qui.id(), NotificationType.SCHEDULE_CHANGED.name());
    }

    /**
     * Rien n'arrive, et rien n'arrivera.
     *
     * <p>{@code during} et non un simple {@code assertThat(…).isZero()} : sans
     * fenêtre d'observation, « personne n'a rien reçu » serait vrai simplement
     * parce que l'envoi n'est pas encore parti.
     */
    private void rienNArrive(Compte qui) {
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
            .until(() -> compter(qui) == 0L);
    }

    private long compter(Compte qui) {
        Long compte = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?",
            Long.class, qui.id(), NotificationType.SCHEDULE_CHANGED.name());
        return compte == null ? 0L : compte;
    }

    private Compte compte() {
        String email = uniqueEmail("horaire");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Horaire" + UUID.randomUUID().toString().substring(0, 8)))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();

        return new Compte(userRepository.findByEmail(email).orElseThrow().getId(),
            auth.accessToken());
    }
}
