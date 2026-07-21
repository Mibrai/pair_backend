package org.program.pair.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Couvre la régression : GET /api/notifications renvoyait 500 pour tout
 * utilisateur ayant une notification (ou une préférence) dont le type en
 * base (seedé par V12/V13/V27, ex. 'NEW_REVIEW', 'NEW_PEER_REC', 'NEW_BADGE')
 * n'existait pas dans l'enum NotificationType — Hibernate lève une
 * IllegalArgumentException en hydratant la ligne, avant même d'atteindre le
 * DTO.
 */
class NotificationIntegrationTest extends AbstractIntegrationTest {

    // Seedée par V27 : Lena Müller, a une notification de type legacy 'NEW_PEER_REC'
    // et une préférence de type legacy — c'est ce qui déclenchait le 500.
    private static final String SEEDED_EMAIL = "lena.mueller@web.de";
    private static final String SEEDED_PASSWORD = "Pair2024!";

    private String loginAndGetToken() {
        AuthResponse resp = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(SEEDED_EMAIL, SEEDED_PASSWORD))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();
        return resp.accessToken();
    }

    @Test
    void getNotifications_devraitRenvoyer200_pourUnUtilisateurAyantDesNotificationsLegacy() throws Exception {
        String token = loginAndGetToken();

        byte[] raw = webTestClient.get()
            .uri("/api/notifications?page=0&size=50")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .returnResult().getResponseBody();

        JsonNode body = objectMapper.readTree(new String(raw, StandardCharsets.UTF_8));
        assertThat(body).isNotNull();
        assertThat(body.get("content")).isNotNull();
        assertThat(body.get("content").isArray()).isTrue();
        assertThat(body.get("content")).isNotEmpty();
        assertThat(body.get("content").get(0).has("id")).isTrue();
        assertThat(body.get("content").get(0).has("type")).isTrue();
    }

    @Test
    void getPreferences_devraitRenvoyer200_pourUnUtilisateurAyantDesPreferencesLegacy() {
        String token = loginAndGetToken();

        webTestClient.get()
            .uri("/api/notifications/preferences")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void getUnreadCount_devraitRenvoyer200() {
        String token = loginAndGetToken();

        webTestClient.get()
            .uri("/api/notifications/unread-count")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk();
    }
}
