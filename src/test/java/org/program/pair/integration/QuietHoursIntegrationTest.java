package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.notification.PushNotificationServiceInterface;
import org.program.pair.domain.notification.dto.QuietHoursDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot D6 — heures de silence.
 *
 * <p>Le silence coupe <b>la push, jamais la notification</b> : elle est écrite en
 * base dans tous les cas et attend au réveil. C'est la même règle que la sourdine
 * d'une conversation au lot D5 — ne pas sonner n'est pas ne pas recevoir.
 *
 * <p>La fenêtre est calculée à partir de l'heure courante, ce qui interdit de
 * l'écrire en dur : les tests posent donc une fenêtre <b>relative à maintenant</b>,
 * de sorte qu'ils disent la même chose à trois heures du matin qu'à midi.
 */
class QuietHoursIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId APP_ZONE = ZoneId.of("Europe/Paris");

    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * Simulé pour observer ce que le silence coupe : une push ne laisse aucune
     * trace en base, contrairement à la notification in-app.
     */
    @MockitoBean PushNotificationServiceInterface pushService;

    // — le réglage —

    @Test
    void unCompteNeuf_naAucuneHeureDeSilence() {
        String token = registerAndLogin();

        QuietHoursDto quiet = getQuietHours(token);

        assertThat(quiet.enabled()).isFalse();
        assertThat(quiet.start()).isNull();
        assertThat(quiet.end()).isNull();
    }

    @Test
    void uneNuit_doitPouvoirEtreReglee_puisRetiree() {
        String token = registerAndLogin();

        QuietHoursDto set = putQuietHours(token, 22, 7);
        assertThat(set.enabled()).isTrue();
        assertThat(set.start()).isEqualTo(22);
        assertThat(set.end()).isEqualTo(7);

        QuietHoursDto cleared = putQuietHours(token, null, null);
        assertThat(cleared.enabled()).isFalse();
    }

    @Test
    void uneMoitieDeReglage_doitEtreRefusee() {
        // Deviner la borne manquante ferait taire des notifications sur une
        // intention supposée, et dans un sens qui ne se remarque pas.
        String token = registerAndLogin();

        expectBadRequest(token, 22, null);
        expectBadRequest(token, null, 7);
    }

    @Test
    void deuxBornesEgales_doiventEtreRefusees() {
        // « 22 → 22 » se lit aussi bien « une minute » que « toute la journée ».
        expectBadRequest(token(), 22, 22);
    }

    @Test
    void uneHeureHorsBornes_doitEtreRefusee() {
        expectBadRequest(token(), 24, 7);
    }

    // — ce que le silence ne coupe pas —

    @Test
    void pendantLeSilence_laNotification_doitQuandMemeEtreEcrite() {
        // Ne pas sonner n'est pas ne pas recevoir : elle attend au réveil, comme
        // pour une conversation en sourdine au lot D5. C'est la seule moitié de la
        // règle qu'on puisse observer d'ici — le filtrage des pushes vit dans
        // PushNotificationService, que ce test remplace justement par un
        // simulacre, et il est vérifié dans PushNotificationServiceTest.
        String token = registerAndLogin();
        UUID userId = userId(token);
        registerDevice(token);
        silenceNow(token);

        notify(userId, NotificationType.NEW_FOLLOWER);

        assertThat(pollNotificationCount(userId, "NEW_FOLLOWER")).isEqualTo(1);
    }

    @Test
    void leReglage_doitSurvivreAuRelevePar_GET() {
        // La fenêtre est stockée en SMALLINT et relue en Short : un écart de type
        // sur ce chemin ne se voit qu'ici, la validation de schéma d'Hibernate
        // ayant déjà refusé de démarrer la première fois.
        String token = registerAndLogin();
        putQuietHours(token, 22, 7);

        QuietHoursDto reread = getQuietHours(token);

        assertThat(reread.start()).isEqualTo(22);
        assertThat(reread.end()).isEqualTo(7);
        assertThat(reread.enabled()).isTrue();
    }

    @Test
    void uneFenetreQuiTraverseMinuit_doitEtreAcceptee() {
        // Le réglage courant du produit, et celui qu'une comparaison naïve
        // rendrait sans effet.
        String token = registerAndLogin();

        assertThat(putQuietHours(token, 23, 6).enabled()).isTrue();
    }

    // — helpers —

    /** Une fenêtre qui contient l'heure courante, quelle qu'elle soit. */
    private void silenceNow(String token) {
        int hour = ZonedDateTime.now(APP_ZONE).getHour();
        putQuietHours(token, hour, (hour + 1) % 24);
    }

    private void notify(UUID userId, NotificationType type) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("programTitle", "Yoga du soir");
        notificationService.notify(userId, type, payload);
    }

    @Autowired org.program.pair.domain.notification.NotificationService notificationService;

    private long pollNotificationCount(UUID userId, String type) {
        long count = 0;
        for (int attempt = 0; attempt < 50 && count == 0; attempt++) {
            count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?",
                Long.class, userId, type);
            if (count == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return count;
    }

    private void registerDevice(String token) {
        webTestClient.post().uri("/api/notifications/devices")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "token", "fcm-" + UUID.randomUUID(),
                "platform", "ANDROID",
                "timezone", "Europe/Paris"))
            .exchange().expectStatus().is2xxSuccessful();
    }

    private QuietHoursDto getQuietHours(String token) {
        return webTestClient.get().uri("/api/notifications/quiet-hours")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(QuietHoursDto.class).returnResult().getResponseBody();
    }

    private QuietHoursDto putQuietHours(String token, Integer start, Integer end) {
        Map<String, Object> body = new HashMap<>();
        body.put("start", start);
        body.put("end", end);
        return webTestClient.put().uri("/api/notifications/quiet-hours")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isOk()
            .expectBody(QuietHoursDto.class).returnResult().getResponseBody();
    }

    private void expectBadRequest(String token, Integer start, Integer end) {
        Map<String, Object> body = new HashMap<>();
        body.put("start", start);
        body.put("end", end);
        webTestClient.put().uri("/api/notifications/quiet-hours")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isBadRequest();
    }

    private String cachedToken;

    private String token() {
        if (cachedToken == null) {
            cachedToken = registerAndLogin();
        }
        return cachedToken;
    }

    private UUID userId(String token) {
        return UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private String registerAndLogin() {
        String email = uniqueEmail("quiet");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Dormeur"))
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
