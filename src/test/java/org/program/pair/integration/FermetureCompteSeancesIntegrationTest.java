package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.AccountClosureEffects;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.watch.WatchState;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.WatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Fermer son compte annule ses séances et prévient leurs inscrits (P-BL-18).
 *
 * <p><b>Le défaut.</b> La fermeture posait {@code is_active = false} et rien
 * d'autre. Le créneau d'un hôte au compte fermé étant masqué partout depuis le
 * 14/09, un inscrit voyait sa séance disparaître sans un mot. Côté participant,
 * la place restait prise et la liste d'attente n'avançait pas.
 *
 * <p><b>Décor à soi</b> : Montpellier, qu'aucune autre classe n'emploie, et
 * chaque méthode crée ses propres comptes. Les notifications sont
 * comptées <b>par écart</b> autour de la fermeture, après que celles de la mise
 * en place (inscriptions notifiées à l'hôte, {@code @Async}) sont arrivées :
 * sinon « rien d'autre » serait faux ou vrai selon la charge de la machine.
 */
class FermetureCompteSeancesIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 43.6108;
    private static final double LNG = 3.8767;

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired WatchRepository watchRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AccountClosureEffects accountClosureEffects;
    @Autowired PlatformTransactionManager transactionManager;

    /**
     * Le test demandé : deux inscrits et une personne en liste d'attente, le
     * créneau passe {@code CANCELLED}, trois {@code SLOT_CANCELLED}, rien d'autre.
     */
    @Test
    void fermerSonCompte_doitAnnulerSonCreneau_etPrevenirInscritsEtFile() {
        Compte hote = compte();
        Creneau creneau = publier(hote, 2);
        Compte inscritA = compte();
        Compte inscritB = compte();
        Compte enAttente = compte();
        rejoindre(inscritA, creneau.scheduleId());
        rejoindre(inscritB, creneau.scheduleId());
        attendre(enAttente, creneau.scheduleId());

        List<Compte> tous = List.of(hote, inscritA, inscritB, enAttente);
        Map<UUID, Long> avant = notificationsStabilisees(tous);

        fermer(hote);

        Map<String, Object> ligne = jdbcTemplate.queryForMap(
            "SELECT status, cancelled_by, cancellation_reason FROM schedules WHERE id = ?",
            creneau.scheduleId());
        assertThat(ligne.get("status")).isEqualTo("CANCELLED");
        assertThat(ligne.get("cancelled_by")).isEqualTo(hote.id());
        // La raison n'est pas nommée : le texte d'annulation habituel suffit.
        assertThat(ligne.get("cancellation_reason")).isNull();

        // Trois, et elles le restent : sans la fenêtre d'observation, « rien
        // d'autre » serait vrai simplement parce que rien n'est encore parti.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
            .until(() -> ecart(tous, avant) == 3L);

        for (Compte concerne : List.of(inscritA, inscritB, enAttente)) {
            assertThat(typesRecusDepuis(concerne, avant)).containsExactly("SLOT_CANCELLED");
        }
        assertThat(total(hote)).isEqualTo(avant.get(hote.id()));

        // Et le compte est bien fermé, dans le même geste.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT is_active FROM users WHERE id = ?", Boolean.class, hote.id())).isFalse();
    }

    /**
     * Ce que la notification ouvre existe : la fiche et « mes créneaux » rendent
     * le créneau annulé, bien que son hôte ait fermé son compte.
     *
     * <p>Depuis l'incident du 14/09, le créneau d'un hôte fermé est introuvable.
     * Sans exception pour l'annulé, l'inscrit qui touche {@code SLOT_CANCELLED}
     * lirait « Créneau introuvable » à la place de « séance annulée ».
     */
    @Test
    void apresLaFermeture_lInscritDoitVoirLeCreneauAnnule() {
        Compte hote = compte();
        Creneau creneau = publier(hote, 5);
        Compte inscrit = compte();
        rejoindre(inscrit, creneau.scheduleId());

        fermer(hote);

        webTestClient.get().uri("/api/slots/{id}", creneau.scheduleId())
            .headers(h -> h.setBearerAuth(inscrit.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("CANCELLED");

        List<SlotFeedItemDto> mesCreneaux = webTestClient.get()
            .uri(b -> b.path("/api/slots/mine").queryParam("upcoming", true).build())
            .headers(h -> h.setBearerAuth(inscrit.token()))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(mesCreneaux)
            .filteredOn(item -> item.scheduleId().equals(creneau.scheduleId()))
            .singleElement()
            .extracting(SlotFeedItemDto::status).isEqualTo("CANCELLED");

        // L'exception ne vaut que pour l'annulé : on ne rejoint pas pour autant.
        webTestClient.post().uri("/api/slots/{id}/join", creneau.scheduleId())
            .headers(h -> h.setBearerAuth(compte().token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isNotFound();
    }

    /**
     * Ses inscriptions chez les autres sont retirées comme un départ ordinaire :
     * la place se libère, la file avance, et l'organisateur n'apprend rien.
     */
    @Test
    void fermerSonCompte_doitRetirerSesInscriptions_etFaireAvancerLaFile() {
        Compte organisateur = compte();
        Creneau creneau = publier(organisateur, 1);
        Compte partant = compte();
        Compte enAttente = compte();
        rejoindre(partant, creneau.scheduleId());
        attendre(enAttente, creneau.scheduleId());

        List<Compte> tous = List.of(organisateur, enAttente);
        Map<UUID, Long> avant = notificationsStabilisees(tous);

        fermer(partant);

        assertThat(statutParticipation(creneau.scheduleId(), partant.id())).isEqualTo("WITHDRAWN");
        assertThat(statutParticipation(creneau.scheduleId(), enAttente.id())).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM schedules WHERE id = ?", String.class, creneau.scheduleId()))
            .isNotEqualTo("CANCELLED");

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
            .until(() -> ecart(tous, avant) == 1L);
        assertThat(typesRecusDepuis(enAttente, avant)).containsExactly("WAITLIST_PROMOTED");
        assertThat(total(organisateur)).isEqualTo(avant.get(organisateur.id()));
    }

    /** Ses programmes sont archivés, comme par {@code deleteProgram}. */
    @Test
    void fermerSonCompte_doitArchiverSesProgrammes() {
        Compte hote = compte();
        Creneau creneau = publier(hote, 5);

        fermer(hote);

        Map<String, Object> programme = jdbcTemplate.queryForMap(
            "SELECT status, archived_at FROM programs WHERE id = ?", creneau.programId());
        assertThat(programme.get("status")).isEqualTo("ARCHIVED");
        assertThat(programme.get("archived_at")).isNotNull();
    }

    /**
     * Une séance passée ne se réécrit pas : son historique sert la fiabilité et
     * les cartes-souvenirs. Seul ce qui est devant nous est annulé.
     */
    @Test
    void fermerSonCompte_neDoitPasReecrireUneSeancePassee() {
        Compte hote = compte();
        Creneau passe = publier(hote, 5);
        Creneau aVenir = publier(hote, 5);
        jdbcTemplate.update(
            "UPDATE schedules SET starts_at = now() - interval '2 days', "
                + "ends_at = now() - interval '2 days' + interval '1 hour' WHERE id = ?",
            passe.scheduleId());

        fermer(hote);

        assertThat(statutCreneau(passe.scheduleId())).isNotEqualTo("CANCELLED");
        assertThat(statutCreneau(aVenir.scheduleId())).isEqualTo("CANCELLED");
    }

    /**
     * Ses veilles sur les séances des autres se referment, sans rien envoyer : un
     * compte fermé ne peut plus lever une veille, et la boucle retour alerterait
     * son proche pour une séance où il n'ira pas.
     */
    @Test
    void fermerSonCompte_doitRefermerSesVeilles() {
        Compte organisateur = compte();
        // Dans deux heures : une veille ne s'arme que sur une séance proche.
        Creneau creneau = publier(organisateur, 5, Instant.now().plus(2, ChronoUnit.HOURS));
        Compte partant = compte();
        rejoindre(partant, creneau.scheduleId());
        UUID veille = armer(partant, creneau.scheduleId());

        fermer(partant);

        assertThat(watchRepository.findById(veille).orElseThrow().getState())
            .isEqualTo(WatchState.CLOSED);
    }

    /**
     * La garantie qui rend l'ordre D2 sûr : une fermeture qui échoue après avoir
     * annulé ne laisse ni créneau annulé ni annonce partie.
     *
     * <p>L'échec est provoqué après {@code apply}, dans la même transaction —
     * exactement la place du {@code is_active = false} et de la révocation des
     * sessions. Avant ce lot, {@code SLOT_CANCELLED} partait avant le commit : les
     * inscrits auraient été prévenus d'une annulation qui n'a pas eu lieu.
     */
    @Test
    void uneFermetureQuiEchoue_neDoitRienAnnuler_niRienEnvoyer() {
        Compte hote = compte();
        Creneau creneau = publier(hote, 5);
        Compte inscrit = compte();
        rejoindre(inscrit, creneau.scheduleId());
        Map<UUID, Long> avant = notificationsStabilisees(List.of(inscrit));

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            accountClosureEffects.apply(hote.id());
            throw new IllegalStateException("panne simulée après les effets");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(statutCreneau(creneau.scheduleId())).isNotEqualTo("CANCELLED");
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
            .until(() -> ecart(List.of(inscrit), avant) == 0L);
    }

    // — outils —

    private record Compte(UUID id, String token) {}

    private record Creneau(UUID scheduleId, UUID programId) {}

    private void fermer(Compte compte) {
        webTestClient.delete().uri("/api/gdpr/delete-account")
            .headers(h -> h.setBearerAuth(compte.token()))
            .exchange().expectStatus().isNoContent();
    }

    /**
     * Le nombre de notifications de chaque compte, une fois qu'il ne bouge plus
     * pendant une seconde : les envois de la mise en place sont {@code @Async}.
     */
    private Map<UUID, Long> notificationsStabilisees(List<Compte> comptes) {
        Map<UUID, Long>[] vu = new Map[]{snapshot(comptes)};
        await().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(10))
            .until(() -> {
                Map<UUID, Long> maintenant = snapshot(comptes);
                boolean stable = maintenant.equals(vu[0]);
                vu[0] = maintenant;
                return stable;
            });
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5))
            .until(() -> snapshot(comptes).equals(vu[0]));
        return vu[0];
    }

    private Map<UUID, Long> snapshot(List<Compte> comptes) {
        Map<UUID, Long> compte = new java.util.HashMap<>();
        for (Compte c : comptes) {
            compte.put(c.id(), total(c));
        }
        return compte;
    }

    private long ecart(List<Compte> comptes, Map<UUID, Long> avant) {
        return comptes.stream().mapToLong(c -> total(c) - avant.get(c.id())).sum();
    }

    private long total(Compte compte) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, compte.id());
    }

    /** Les types reçus au-delà du compte d'avant, du plus ancien au plus récent. */
    private List<String> typesRecusDepuis(Compte compte, Map<UUID, Long> avant) {
        return jdbcTemplate.queryForList(
            "SELECT type FROM notifications WHERE user_id = ? ORDER BY sent_at, id OFFSET ?",
            String.class, compte.id(), avant.get(compte.id()));
    }

    private String statutParticipation(UUID scheduleId, UUID userId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM slot_participations WHERE schedule_id = ? AND user_id = ?",
            String.class, scheduleId, userId);
    }

    private String statutCreneau(UUID scheduleId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM schedules WHERE id = ?", String.class, scheduleId);
    }

    private Creneau publier(Compte hote, int places) {
        return publier(hote, places, Instant.now().plus(3, ChronoUnit.DAYS));
    }

    private Creneau publier(Compte hote, int places, Instant debut) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        Map<?, ?> body = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(hote.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, debut, debut.plus(Duration.ofHours(2)),
                "Esplanade Charles-de-Gaulle", PlaceType.PUBLIC, LAT, LNG,
                "Esplanade Charles-de-Gaulle", null, "Montpellier", places, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return new Creneau(
            UUID.fromString(String.valueOf(body.get("scheduleId"))),
            UUID.fromString(String.valueOf(body.get("programId"))));
    }

    private void rejoindre(Compte compte, UUID scheduleId) {
        webTestClient.post().uri("/api/slots/{id}/join", scheduleId)
            .headers(h -> h.setBearerAuth(compte.token()))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isCreated();
    }

    private void attendre(Compte compte, UUID scheduleId) {
        webTestClient.post().uri("/api/slots/{id}/waitlist", scheduleId)
            .headers(h -> h.setBearerAuth(compte.token()))
            .exchange().expectStatus().isCreated();
    }

    private UUID armer(Compte compte, UUID scheduleId) {
        UUID guardianId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(compte.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Proche", "phone", uniqueMobile(),
                              "email", uniqueEmail("proche-fermeture")))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
        String consentement = guardianRepository.findByIdAndOwnerId(guardianId, compte.id())
            .orElseThrow().getConsentToken();
        webTestClient.post().uri("/public/guardian-consent/{t}/accept", consentement)
            .exchange().expectStatus().isOk();

        return UUID.fromString(String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(compte.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(),
                              "guardianId", guardianId.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private Compte compte() {
        String email = uniqueEmail("fermeture");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Fermeture"))
            .exchange().expectStatus().isCreated();
        adresseVerifiee(email);

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return new Compte(auth.userId(), auth.accessToken());
    }
}
