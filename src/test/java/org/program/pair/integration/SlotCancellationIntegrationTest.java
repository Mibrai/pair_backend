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

    // — helpers —

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
