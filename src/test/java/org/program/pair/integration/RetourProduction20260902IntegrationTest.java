package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.program.jobs.RecurringSlotRolloverJob;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le relevé de production du 01/09, fait à deux comptes réels par le chantier
 * mobile, et ce qu'il a rendu.
 *
 * <p>Chaque test de ce fichier fige un fait constaté en production, pas une
 * intention de conception : c'est la première campagne du module menée hors
 * simulation, et les deux écarts qu'elle a trouvés étaient invisibles aux tests
 * des lots précédents parce que ceux-ci n'avançaient jamais un créneau récurrent
 * ni ne rejoignaient un créneau par le programme.
 */
class RetourProduction20260902IntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RecurringSlotRolloverJob rolloverJob;

    // — §1 : le contrat de /participants, servi entier —

    @Test
    void laListeDesInscrits_doitPorterLIdentiteDeChacun() {
        // Sans user.id, le menu bloquer / signaler porte sur une personne vide :
        // c'est la seule liste nominative du produit, et celle où se joue le geste
        // de sécurité d'avant-rencontre.
        String hote = inscrit();
        UUID creneau = publier(hote, 5, null);
        String participant = inscrit();
        rejoindre(participant, creneau);

        List<Map<String, Object>> inscrits = webTestClient.get()
            .uri("/api/slots/{id}/participants", creneau)
            .headers(h -> h.setBearerAuth(hote))
            .exchange().expectStatus().isOk()
            .expectBodyList(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult().getResponseBody();

        assertThat(inscrits).hasSize(1);
        Map<String, Object> ligne = inscrits.get(0);
        assertThat(ligne).containsKeys("participationId", "user", "status", "joinMessage", "createdAt");
        assertThat(ligne.get("participationId")).isNotNull();
        assertThat(ligne.get("createdAt")).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> profil = (Map<String, Object>) ligne.get("user");
        assertThat(profil).isNotNull();
        assertThat(profil.get("id")).isNotNull();
        assertThat(profil.get("displayName")).isNotNull();
    }

    // — §2 : le compteur de places, sur les quatre chemins qui le changent —

    @Test
    void unCreneauRecurrentAvance_doitGarderSesInscritsEtSonCompteur() {
        // L'écart du 01/09, à la ligne près : /participants rendait un inscrit
        // CONFIRMED pendant que la fiche du même créneau rendait participantCount 0.
        // Le rollover remettait le compteur à zéro sans retirer personne.
        String hote = inscrit();
        UUID creneau = publier(hote, 5, "FREQ=WEEKLY");
        String participant = inscrit();
        rejoindre(participant, creneau);

        avancerLeCreneau(creneau);

        assertThat(inscritsConfirmes(creneau)).isEqualTo(1);
        assertThat(compteurEnBase(creneau)).isEqualTo(1);
        assertThat(fiche(hote, creneau).participantCount()).isEqualTo(1);
    }

    @Test
    void unCreneauRecurrentComplet_doitLeResterApresAvoirEteAvance() {
        // Corollaire du précédent, et c'est lui qui laissait dépasser le plafond :
        // un créneau complet rouvrait ses places à chaque occurrence, sans qu'aucune
        // ne se libère.
        String hote = inscrit();
        UUID creneau = publier(hote, 1, "FREQ=WEEKLY");
        rejoindre(inscrit(), creneau);

        avancerLeCreneau(creneau);

        assertThat(fiche(hote, creneau).participantCount()).isEqualTo(1);
        assertThat(statutEnBase(creneau)).isEqualTo("FULL");
    }

    @Test
    void rejoindreParLeProgramme_doitCompterUnePlace() {
        // countConfirmedParticipants agrège les deux sources d'inscription, mais
        // seul le chemin /slots/{id}/join réécrivait le compteur. Rejoindre par le
        // programme prenait donc une place que la fiche continuait d'annoncer libre.
        String hote = inscrit();
        UUID creneau = publier(hote, 2, null);
        UUID programme = programmeDu(creneau);

        String participant = inscrit();
        webTestClient.post().uri("/api/programs/{id}/join", programme)
            .headers(h -> h.setBearerAuth(participant))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", creneau.toString()))
            .exchange().expectStatus().isCreated();

        assertThat(compteurEnBase(creneau)).isEqualTo(1);
        assertThat(fiche(hote, creneau).participantCount()).isEqualTo(1);
    }

    @Test
    void quitterLeProgramme_doitRendreLaPlace() {
        // Le défaut symétrique du précédent, et le plus dur à voir : une personne
        // partie continuait d'occuper une place que plus personne ne pouvait
        // prendre, sur un créneau qui ne rouvrait jamais.
        String hote = inscrit();
        UUID creneau = publier(hote, 1, null);
        UUID programme = programmeDu(creneau);
        String participant = inscrit();

        UUID inscription = UUID.fromString(String.valueOf(webTestClient.post()
            .uri("/api/programs/{id}/join", programme)
            .headers(h -> h.setBearerAuth(participant))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", creneau.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));

        assertThat(compteurEnBase(creneau)).isEqualTo(1);
        assertThat(statutEnBase(creneau)).isEqualTo("FULL");

        webTestClient.post().uri("/api/programs/{id}/leave", programme)
            .headers(h -> h.setBearerAuth(participant))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("userProgramId", inscription.toString()))
            .exchange().expectStatus().isNoContent();

        assertThat(compteurEnBase(creneau)).isZero();
        assertThat(statutEnBase(creneau)).isEqualTo("OPEN");
    }

    // — §3 : l'organisateur n'est jamais parmi les inscrits —

    @Test
    void lOrganisateur_nEstJamaisComptePartMiLesInscritsDeSaSeance() {
        // La convention que le chantier mobile demande d'écrire noir sur blanc.
        // Toute la visibilité de son bloc de sécurité repose dessus : « null sur ma
        // propre séance » ne veut pas dire « je n'y vais pas ».
        String hote = inscrit();
        UUID creneau = publier(hote, 5, null);
        rejoindre(inscrit(), creneau);

        assertThat(fiche(hote, creneau).myParticipationStatus()).isNull();
        assertThat(fiche(hote, creneau).participantCount()).isEqualTo(1);

        // Et les trois portes qui le garantissent restent fermées.
        webTestClient.post().uri("/api/slots/{id}/join", creneau)
            .headers(h -> h.setBearerAuth(hote))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isBadRequest();

        webTestClient.post().uri("/api/slots/{id}/waitlist", creneau)
            .headers(h -> h.setBearerAuth(hote))
            .exchange().expectStatus().isBadRequest();

        webTestClient.post().uri("/api/programs/{id}/join", programmeDu(creneau))
            .headers(h -> h.setBearerAuth(hote))
            .exchange().expectStatus().isForbidden();
    }

    // — §4 : à quel titre la séance est proposée —

    @Test
    void laQuestionDePresence_doitDireSiLOnEstHoteOuParticipant() {
        // « Tu étais à ta propre séance ? » a l'air d'un bug quand rien ne dit
        // qu'on l'organise.
        String hote = inscrit();
        UUID creneau = publier(hote, 5, "FREQ=WEEKLY");
        String participant = inscrit();
        rejoindre(participant, creneau);

        avancerLeCreneau(creneau);

        assertThat(rolesEnAttente(hote)).containsExactly("HOST");
        assertThat(rolesEnAttente(participant)).containsExactly("PARTICIPANT");
    }

    @Test
    void laQuestionDePresence_neProposeQueCeQueLEcritureAcceptera() {
        // Le chantier mobile consomme la liste sans filtrer. La règle des deux
        // routes doit donc rester la même : rien de ce qui est proposé ne peut se
        // voir refuser, et un tiers ne se voit jamais rien proposer.
        String hote = inscrit();
        UUID creneau = publier(hote, 5, "FREQ=WEEKLY");
        String participant = inscrit();
        rejoindre(participant, creneau);
        String tiers = inscrit();

        avancerLeCreneau(creneau);

        assertThat(rolesEnAttente(tiers)).isEmpty();
        webTestClient.post().uri("/api/attendances/{id}/confirm", creneau)
            .headers(h -> h.setBearerAuth(tiers))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("wasPresent", true))
            .exchange().expectStatus().isForbidden();

        // Ce qui est proposé, lui, passe.
        webTestClient.post().uri("/api/attendances/{id}/confirm", creneau)
            .headers(h -> h.setBearerAuth(participant))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("wasPresent", true))
            .exchange().expectStatus().isOk();

        // Et ne l'est plus une fois répondu, pour cette séance-là.
        assertThat(rolesEnAttente(participant)).isEmpty();
    }

    // — helpers —

    private void avancerLeCreneau(UUID creneau) {
        Instant passe = Instant.now().minus(8, ChronoUnit.DAYS);
        jdbcTemplate.update("UPDATE schedules SET starts_at = ?, ends_at = ? WHERE id = ?",
            java.sql.Timestamp.from(passe),
            java.sql.Timestamp.from(passe.plus(2, ChronoUnit.HOURS)), creneau);
        rolloverJob.rollPastRecurringSchedulesForward();
    }

    private List<String> rolesEnAttente(String token) {
        List<Map<String, Object>> attente = webTestClient.get().uri("/api/attendances/pending")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBodyList(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult().getResponseBody();
        return attente.stream().map(m -> (String) m.get("role")).toList();
    }

    private int inscritsConfirmes(UUID creneau) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM slot_participations
            WHERE schedule_id = ? AND status = 'CONFIRMED'
            """, Integer.class, creneau);
    }

    private int compteurEnBase(UUID creneau) {
        return jdbcTemplate.queryForObject(
            "SELECT participant_count FROM schedules WHERE id = ?", Integer.class, creneau);
    }

    private String statutEnBase(UUID creneau) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM schedules WHERE id = ?", String.class, creneau);
    }

    private UUID programmeDu(UUID creneau) {
        return jdbcTemplate.queryForObject(
            "SELECT program_id FROM schedules WHERE id = ?", UUID.class, creneau);
    }

    private void rejoindre(String token, UUID creneau) {
        webTestClient.post().uri("/api/slots/{id}/join", creneau)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isCreated();
    }

    private SlotFeedItemDto fiche(String token, UUID creneau) {
        return webTestClient.get().uri("/api/slots/{id}", creneau)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
    }

    private UUID publier(String token, int places, String recurrence) {
        UUID activite = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto creneau = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activite, Instant.now().plus(4, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", places, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(creneau).isNotNull();
        if (recurrence != null) {
            jdbcTemplate.update("UPDATE schedules SET recurrence_rule = ? WHERE id = ?",
                recurrence, creneau.scheduleId());
        }
        return creneau.scheduleId();
    }

    private String inscrit() {
        String email = uniqueEmail("prod0902");
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
        return auth.accessToken();
    }
}
