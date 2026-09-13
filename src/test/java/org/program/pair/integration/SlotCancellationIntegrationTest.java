package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
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
 * Lot C2 — annulation notifiée.
 *
 * <p>Ce qui distingue une annulation du reste : ne pas la recevoir coûte un
 * déplacement pour rien. Ces tests portent donc surtout sur les destinataires —
 * qui reçoit, et qui aurait été oublié.
 */
class SlotCancellationIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void annuler_doitMarquerLeCreneau_avecMotifDateEtAuteur() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);

        cancel(host, slotId, "Le gymnase est fermé");

        Map<String, Object> row = jdbcTemplate.queryForMap("""
            SELECT status, cancellation_reason, cancelled_at, cancelled_by
            FROM schedules WHERE id = ?
            """, slotId);

        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("cancellation_reason")).isEqualTo("Le gymnase est fermé");
        assertThat(row.get("cancelled_at")).isNotNull();
        assertThat(row.get("cancelled_by")).isEqualTo(userId(host));
    }

    @Test
    void unTiers_neDoitPasPouvoirAnnuler() {
        // 404 et non 403 : confirmer l'existence d'un créneau qu'on n'organise
        // pas n'a aucune raison d'être.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String stranger = registerAndLogin();

        webTestClient.post().uri("/api/slots/{id}/cancel", slotId)
            .headers(h -> h.setBearerAuth(stranger))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "non"))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void annulerDeuxFois_doitEtreRefuse() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        cancel(host, slotId, null);

        webTestClient.post().uri("/api/slots/{id}/cancel", slotId)
            .headers(h -> h.setBearerAuth(host))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isBadRequest();
    }

    @Test
    void lesInscrits_doiventEtreNotifies() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String participant = registerAndLogin();
        join(participant, slotId);

        cancel(host, slotId, "Empêchement");

        assertThat(cancellationsFor(participant)).isEqualTo(1);
    }

    @Test
    void laListeDAttente_doitEtreNotifieeAussi() {
        // Quelqu'un qui attendait une place a organisé sa journée autour de ce
        // créneau autant qu'un inscrit. Le filtre historique l'ignorait.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 1);
        String occupant = registerAndLogin();
        join(occupant, slotId);

        String waiting = registerAndLogin();
        webTestClient.post().uri("/api/slots/{id}/waitlist", slotId)
            .headers(h -> h.setBearerAuth(waiting))
            .exchange().expectStatus().isCreated();

        cancel(host, slotId, "Empêchement");

        assertThat(cancellationsFor(waiting)).isEqualTo(1);
        assertThat(cancellationsFor(occupant)).isEqualTo(1);
    }

    @Test
    void lOrganisateur_neDoitPasSeNotifierLuiMeme() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String participant = registerAndLogin();
        join(participant, slotId);

        cancel(host, slotId, null);

        // On attend d'abord que l'envoi destiné au participant soit arrivé :
        // sans cela, « l'organisateur n'a rien reçu » serait vrai simplement
        // parce que rien n'est encore parti.
        assertThat(cancellationsFor(participant)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'SLOT_CANCELLED'
            """, Long.class, userId(host))).isZero();
    }

    @Test
    void laChargeUtile_doitPorterLeMotif_etDeQuoiRebondir() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String participant = registerAndLogin();
        join(participant, slotId);

        // Un autre créneau de la même activité, à côté et à venir.
        publishSlot(registerAndLogin(), 5);

        cancel(host, slotId, "Le gymnase est ferme");

        UUID participantId = userId(participant);
        String payload = await(() -> jdbcTemplate.queryForList("""
                SELECT payload FROM notifications
                WHERE user_id = ? AND type = 'SLOT_CANCELLED'
                """, String.class, participantId)
            .stream().findFirst().orElse(null));

        assertThat(payload).contains("cancellationReason").contains("Le gymnase est ferme");
        // Un nombre, pas une liste. La charge utile est composée une fois pour
        // tous les destinataires : y détailler des créneaux de repli ferait
        // voyager leurs adresses vers des gens dont aucun n'a été consulté.
        // L'adresse du créneau annulé, elle, y figure — c'est la diffusable, que
        // ofSchedule pose précisément pour ce cas.
        assertThat(payload).contains("alternativesCount");
        assertThat(payload).doesNotContain("\"alternatives\"");
    }

    @Test
    void leCreneauAnnule_doitDisparaitreDuFil() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host, 5);
        String viewer = registerAndLogin();

        assertThat(feedIds(viewer)).contains(slotId);
        cancel(host, slotId, null);
        assertThat(feedIds(viewer)).doesNotContain(slotId);
    }

    @Test
    void uneAnnulation_doitEtreClasseeCritique() {
        // C'est ce qui la fera passer outre les heures de silence en D6, et ce
        // qui autorise son e-mail : les deux lisent la même classification.
        assertThat(NotificationType.SLOT_CANCELLED.isCritical()).isTrue();
        assertThat(NotificationType.NEARBY_PROGRAM.isCritical()).isFalse();
    }

    // ————————————————————————————————— P-BL-19 : un seul chemin d'annulation

    /**
     * Annuler puis supprimer n'envoie qu'<b>une</b> annulation.
     *
     * <p><b>Le défaut.</b> Il existait deux implémentations de l'annulation.
     * {@code POST /slots/{id}/cancel} renseignait motif, date et auteur ;
     * {@code DELETE .../schedules/{id}} posait {@code CANCELLED} sans rien d'autre,
     * ne testait pas le statut, et renotifiait tous les concernés. Un organisateur
     * qui annulait puis nettoyait son agenda envoyait donc deux « la séance est
     * annulée » pour un seul fait, et la seconde arrivait sans motif alors que la
     * première en portait un.
     */
    @Test
    void annulerPuisSupprimer_neDoitEnvoyerQuUneAnnulation() {
        String host = registerAndLogin();
        Creneau creneau = publishSlotComplet(host, 5);
        String participant = registerAndLogin();
        join(participant, creneau.scheduleId());

        cancel(host, creneau.scheduleId(), "Le gymnase est fermé");
        assertThat(cancellationsFor(participant)).isEqualTo(1);

        supprimer(host, creneau);

        // Et elle reste à une : une seconde n'arrive pas en retard. Sans la
        // fenêtre d'observation, « il n'y en a qu'une » serait vrai simplement
        // parce que la deuxième n'est pas encore partie.
        UUID participantId = userId(participant);
        org.awaitility.Awaitility.await()
            .during(java.time.Duration.ofSeconds(2))
            .atMost(java.time.Duration.ofSeconds(6))
            .until(() -> compterAnnulations(participantId) == 1L);
    }

    /**
     * Supprimer un créneau qui concerne quelqu'un est une annulation complète :
     * date et auteur enregistrés, comme par la route d'annulation.
     *
     * <p><b>Le motif reste nul, et c'est exact</b> : le geste « supprimer » n'en
     * porte aucun — la route n'a pas de corps. Ce que la délégation apporte est
     * qu'il y a désormais une colonne pour l'accueillir, et un auteur pour répondre
     * plus tard à « qui a annulé cette séance, et quand ». La fiche P-BL-19
     * nommait ce test « doitPoserMotifDateEtAuteur » ; le motif n'était pas
     * atteignable par ce chemin.
     */
    @Test
    void supprimerUnCreneauAvecInscrits_doitPoserDateEtAuteur() {
        String host = registerAndLogin();
        Creneau creneau = publishSlotComplet(host, 5);
        String participant = registerAndLogin();
        join(participant, creneau.scheduleId());

        supprimer(host, creneau);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT status, cancelled_at, cancelled_by, cancellation_reason"
                + " FROM schedules WHERE id = ?", creneau.scheduleId());

        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("cancelled_at")).isNotNull();
        assertThat(row.get("cancelled_by")).isEqualTo(userId(host));
        assertThat(row.get("cancellation_reason")).isNull();

        // Et l'inscrit est prévenu une fois, par le chemin unique.
        assertThat(cancellationsFor(participant)).isEqualTo(1);
    }

    /** Personne à prévenir : la ligne disparaît pour de bon, comme avant. */
    @Test
    void supprimerUnCreneauSansPersonne_doitLeSupprimer() {
        String host = registerAndLogin();
        Creneau creneau = publishSlotComplet(host, 5);

        supprimer(host, creneau);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM schedules WHERE id = ?", Long.class, creneau.scheduleId()))
            .isZero();
    }

    /**
     * Un seul producteur de {@code SLOT_CANCELLED} dans tout le code source.
     *
     * <p><b>Pourquoi un test déclaratif et non un comptage.</b> Un comptage de
     * notifications prouve qu'un chemin donné n'en envoie qu'une ; il ne dit rien
     * du troisième chemin que quelqu'un écrira dans six mois. Ce test-ci échoue
     * dès qu'un second appelant passe {@code SLOT_CANCELLED} à {@code notify},
     * même si aucun test fonctionnel ne le traverse — c'est exactement le défaut
     * qu'a produit {@code deleteSchedule}, invisible à chaque test pris séparément.
     *
     * <p>La recherche porte sur l'<b>appel</b> et non sur la mention : le type est
     * nommé légitimement par sa propre énumération, par la composition des textes
     * push, par l'e-mail et par la règle de visibilité du lieu. Seul
     * {@code notify(… SLOT_CANCELLED …)} est un envoi.
     */
    @Test
    void unSeulProducteur_doitEmettreSlotCancelled() throws java.io.IOException {
        java.util.regex.Pattern envoi =
            java.util.regex.Pattern.compile("notify\\([^;]*SLOT_CANCELLED");

        List<String> producteurs;
        try (var chemins = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            producteurs = chemins
                .filter(c -> c.toString().endsWith(".java"))
                .filter(c -> {
                    try {
                        return envoi.matcher(java.nio.file.Files.readString(c)).find();
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .map(c -> c.getFileName().toString())
                .sorted()
                .toList();
        }

        assertThat(producteurs).containsExactly("SlotCancellationService.java");
    }

    // — helpers —

    private record Creneau(UUID scheduleId, UUID programId) {}

    /** {@code DELETE} rend 200 et dit ce qu'il a fait (P-BA-16) ; ces tests-ci ne lisent que ses effets. */
    private void supprimer(String token, Creneau creneau) {
        webTestClient.delete()
            .uri("/api/programs/{programId}/schedules/{scheduleId}",
                creneau.programId(), creneau.scheduleId())
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk();
    }

    private Creneau publishSlotComplet(String token, int maxParticipants) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        Map<?, ?> body = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(3, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", maxParticipants, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return new Creneau(
            UUID.fromString(String.valueOf(body.get("scheduleId"))),
            UUID.fromString(String.valueOf(body.get("programId"))));
    }

    private long compterAnnulations(UUID userId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'SLOT_CANCELLED'",
            Long.class, userId);
    }

    private void cancel(String token, UUID slotId, String reason) {
        webTestClient.post().uri("/api/slots/{id}/cancel", slotId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(reason == null ? Map.of() : Map.of("reason", reason))
            .exchange().expectStatus().isNoContent();
    }

    /**
     * Le nombre de notifications d'annulation reçues, une fois l'envoi arrivé.
     *
     * <p>{@code notify} est {@code @Async} : la requête HTTP rend la main avant
     * que la ligne ne soit écrite. Interroger la base aussitôt lisait donc
     * parfois zéro — un test qui passe ou échoue selon la charge de la machine,
     * exactement le genre d'instabilité que le lot 0 a passé du temps à retirer.
     */
    private long cancellationsFor(String token) {
        UUID id = userId(token);
        return await(() -> jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'SLOT_CANCELLED'
            """, Long.class, id));
    }

    /** Attend qu'une valeur devienne non nulle et non vide, ou abandonne. */
    private static <T> T await(java.util.function.Supplier<T> probe) {
        T last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            last = probe.get();
            boolean settled = last instanceof Long count ? count > 0 : last != null;
            if (settled) {
                return last;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last;
    }

    private void join(String token, UUID slotId) {
        webTestClient.post().uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isCreated();
    }

    private List<UUID> feedIds(String token) {
        List<SlotFeedItemDto> feed = webTestClient.get()
            .uri(b -> b.path("/api/slots/feed")
                .queryParam("lat", LAT).queryParam("lng", LNG)
                .queryParam("radiusMeters", 20000).build())
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class).returnResult().getResponseBody();
        return feed.stream().map(SlotFeedItemDto::scheduleId).toList();
    }

    private UUID userId(String token) {
        return UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private UUID publishSlot(String token, int maxParticipants) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(3, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", maxParticipants, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("cancel");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Organisateur"))
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
