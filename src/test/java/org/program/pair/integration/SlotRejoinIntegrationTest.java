package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quitter un créneau ne doit pas être définitif.
 *
 * <p>Défaut signalé par le client le 04/09 et reproduit trois fois sur trois
 * contre la production : {@code DELETE} posait {@code WITHDRAWN} sur la ligne de
 * participation, et le contrôle d'unicité du {@code POST} portait sur
 * l'existence de cette ligne plutôt que sur son état. Se désinscrire fermait
 * donc la porte pour de bon, avec un message — « Vous avez déjà rejoint ce
 * créneau » — adressé à quelqu'un qui venait de le quitter.
 *
 * <p>Le premier test est la reproduction exacte de leur relevé. Les suivants
 * tiennent ce qui ne devait pas s'assouplir en même temps : le client demandait
 * que le fait d'être parti cesse d'être un motif de refus, pas un droit
 * d'entrée.
 */
class SlotRejoinIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void rejoindreApresAvoirQuitte_doitAboutir() {
        // La reproduction du client, geste pour geste.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String participant = registerAndLogin();

        join(participant, slotId).expectStatus().isCreated();
        assertThat(myStatus(participant, slotId)).isEqualTo("CONFIRMED");

        leave(participant, slotId);
        assertThat(myStatus(participant, slotId)).isEqualTo("WITHDRAWN");

        join(participant, slotId).expectStatus().isCreated();
        assertThat(myStatus(participant, slotId)).isEqualTo("CONFIRMED");
    }

    @Test
    void hesiterPlusieursFois_doitResterPossible() {
        // Le client le dit mieux que nous : changer d'avis deux fois est le
        // comportement ordinaire de quelqu'un qui hésite entre deux séances du
        // même soir. Une correction qui ne tiendrait qu'un aller-retour aurait
        // seulement déplacé la porte d'un cran.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String participant = registerAndLogin();

        for (int i = 0; i < 3; i++) {
            join(participant, slotId).expectStatus().isCreated();
            leave(participant, slotId);
        }
        join(participant, slotId).expectStatus().isCreated();

        assertThat(myStatus(participant, slotId)).isEqualTo("CONFIRMED");
    }

    @Test
    void laPlaceRendue_doitEtreReprise_pasComptéeEnDouble() {
        // Le §2.a du client : si WITHDRAWN ne compte plus pour l'unicité, il ne
        // doit pas compter pour les places non plus. Un aller-retour-retour qui
        // laisserait deux places prises pour une personne remplirait le créneau
        // de gens qui l'ont quitté.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String participant = registerAndLogin();

        join(participant, slotId).expectStatus().isCreated();
        assertThat(participantCount(host, slotId)).isEqualTo(1);

        leave(participant, slotId);
        assertThat(participantCount(host, slotId)).isZero();

        join(participant, slotId).expectStatus().isCreated();
        assertThat(participantCount(host, slotId)).isEqualTo(1);
    }

    @Test
    void uneInscriptionEnCours_doitToujoursEtreRefusee() {
        // Le seul refus que ce message ait jamais dit vrai, et il ne bouge pas.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String participant = registerAndLogin();

        join(participant, slotId).expectStatus().isCreated();

        join(participant, slotId).expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("SLOT_ALREADY_JOINED");
    }

    @Test
    void uneAttenteEnCours_doitEtreRefusee_avecUnMessageVrai() {
        // La file existe pour ordonner l'entrée : convertir sa propre attente en
        // inscription par ce chemin doublerait tous ceux qui attendent devant.
        //
        // Atteindre cette branche demande une place libre ET quelqu'un encore en
        // file : sur un créneau plein, le refus tombe bien avant. On reconstitue
        // donc le seul cas où les deux coexistent, et il est réel — la promotion
        // saute un candidat en conflit d'agenda et le laisse dans la file (voir
        // WaitlistPromoter). Le conflit est ensuite levé, si bien que le refus
        // testé ici ne peut venir que de l'attente elle-même.
        String hote = registerAndLogin();
        UUID creneau = publishSlot(hote, 1);
        Instant memeHeure = Instant.now().plus(2, ChronoUnit.DAYS);
        UUID ailleurs = publishSlotAt(registerAndLogin(), 5, memeHeure);

        String premier = registerAndLogin();
        join(premier, creneau).expectStatus().isCreated();          // le créneau est plein

        String candidat = registerAndLogin();
        join(candidat, ailleurs).expectStatus().isCreated();        // engagement concurrent
        webTestClient.post().uri("/api/slots/{id}/waitlist", creneau)
            .headers(h -> h.setBearerAuth(candidat))
            .exchange().expectStatus().isCreated();

        // La place se libère, mais le candidat est en conflit : il est sauté.
        leave(premier, creneau);
        assertThat(myStatus(candidat, creneau)).isEqualTo("WAITLISTED");

        // Le conflit disparaît. Il reste une place libre et un candidat en file.
        leave(candidat, ailleurs);

        // Un code à lui : le message est rendu depuis le bundle par error.<CODE>,
        // jamais depuis l'exception, si bien que garder SLOT_ALREADY_JOINED
        // aurait forcément servi « vous avez déjà rejoint » à quelqu'un qui
        // n'avait pas rejoint.
        join(candidat, creneau).expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SLOT_ALREADY_WAITLISTED")
            .jsonPath("$.message").value(m ->
                assertThat((String) m).contains("liste d'attente"));
    }

    @Test
    void unCreneauComplet_doitResterRefuseApresUnDepart() {
        // §2.b : le fait d'être parti cesse d'être un motif de refus, les autres
        // motifs restent entiers. Celui-ci le vérifie sur le chemin le plus
        // exposé — la place rendue est reprise par quelqu'un d'autre avant qu'on
        // ne revienne.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 1);
        String premier = registerAndLogin();
        String second = registerAndLogin();

        join(premier, slotId).expectStatus().isCreated();
        leave(premier, slotId);
        join(second, slotId).expectStatus().isCreated();

        join(premier, slotId).expectStatus().isEqualTo(400)
            .expectBody().jsonPath("$.code").isEqualTo("SLOT_NOT_ACCEPTING_PARTICIPANTS");
    }

    @Test
    void unCreneauPasse_doitResterRefuseApresUnDepart() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String participant = registerAndLogin();

        join(participant, slotId).expectStatus().isCreated();
        leave(participant, slotId);

        // Reculé en base : la route refuse de publier un créneau déjà passé.
        jdbcTemplate.update("UPDATE schedules SET starts_at = ?, ends_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)),
            java.sql.Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS)),
            slotId);

        join(participant, slotId).expectStatus().isEqualTo(400)
            .expectBody().jsonPath("$.code").isEqualTo("SLOT_ALREADY_STARTED");
    }

    @Test
    void lesTracesDuDepart_doiventEtreEffacees() {
        // Quatre champs survivaient à la réactivation, et chacun ment à sa
        // façon. withdrawn_at est le plus visible : une inscription qui porte
        // une date de désistement se lit comme un désistement partout où on la
        // relit — dont le signal de fiabilité, qui compte les départs.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String participant = registerAndLogin();

        join(participant, slotId).expectStatus().isCreated();
        leave(participant, slotId);
        assertThat(colonne(slotId, participant, "withdrawn_at")).isNotNull();

        join(participant, slotId).expectStatus().isCreated();

        assertThat(colonne(slotId, participant, "withdrawn_at")).isNull();
        assertThat(colonne(slotId, participant, "waitlist_position")).isNull();
        assertThat(colonne(slotId, participant, "promoted_at")).isNull();
        assertThat(colonne(slotId, participant, "attendance_closed_at")).isNull();
    }

    @Test
    void uneSeuleLigne_doitSurvivreAuxAllersRetours() {
        // La contrainte d'unicité (schedule_id, user_id) interdit la seconde
        // ligne : c'est ce qui impose de réactiver plutôt que de créer, et ce
        // test le rend explicite plutôt que de le laisser à la contrainte.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String participant = registerAndLogin();

        join(participant, slotId).expectStatus().isCreated();
        leave(participant, slotId);
        join(participant, slotId).expectStatus().isCreated();

        Integer lignes = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM slot_participations sp JOIN users u ON sp.user_id = u.id "
                + "WHERE sp.schedule_id = ? AND u.email = ?",
            Integer.class, slotId, emailDe(participant));
        assertThat(lignes).isEqualTo(1);
    }

    @Test
    void leCompteurFige_doitSeRemettreDAccordDesLaPremiereEcriture() {
        // Le §3 du client, qu'il signalait sans l'affirmer : participant_count
        // ne revenait pas à sa valeur de départ. La valeur de départ était
        // fausse — le compteur est dénormalisé et ne se répare qu'en étant
        // touché. On force ici une valeur périmée, comme en portaient les lignes
        // écrites avant le 02/09, et on vérifie que la première écriture la
        // remplace par la vérité. V100 fait le même travail sur l'existant.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String participant = registerAndLogin();

        jdbcTemplate.update("UPDATE schedules SET participant_count = 7 WHERE id = ?", slotId);
        assertThat(participantCount(host, slotId)).isEqualTo(7);

        join(participant, slotId).expectStatus().isCreated();

        assertThat(participantCount(host, slotId)).isEqualTo(1);
    }

    // — helpers —

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec join(
            String token, UUID slotId) {
        return webTestClient.post().uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange();
    }

    private void leave(String token, UUID slotId) {
        webTestClient.delete().uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isNoContent();
    }

    private String myStatus(String token, UUID slotId) {
        SlotFeedItemDto slot = webTestClient.get().uri("/api/slots/{id}", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.myParticipationStatus();
    }

    private Integer participantCount(String token, UUID slotId) {
        SlotFeedItemDto slot = webTestClient.get().uri("/api/slots/{id}", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.participantCount();
    }

    private Object colonne(UUID slotId, String token, String colonne) {
        return jdbcTemplate.queryForMap(
            "SELECT " + colonne + " AS v FROM slot_participations sp "
                + "JOIN users u ON sp.user_id = u.id "
                + "WHERE sp.schedule_id = ? AND u.email = ?",
            slotId, emailDe(token)).get("v");
    }

    /** L'email est porté par le jeton ; on le retient à l'inscription. */
    private final Map<String, String> emails = new java.util.HashMap<>();

    private String emailDe(String token) {
        return emails.get(token);
    }

    private UUID publishSlot(String token, int maxParticipants) {
        return publishSlotAt(token, maxParticipants, Instant.now().plus(2, ChronoUnit.DAYS));
    }

    private UUID publishSlotAt(String token, int maxParticipants, Instant startsAt) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, startsAt, null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", maxParticipants, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("rejoin");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Participant"))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        emails.put(auth.accessToken(), email);
        return auth.accessToken();
    }
}
