package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduit le bug de production : le payload d'une notification revient
 * comme un dump des propriétés internes du JsonNode Jackson
 * ("{'array': false, 'bigDecimal': false, ...}") au lieu du JSON métier
 * réellement stocké. Utilise la notification F1000000-...-0002 du seed V27
 * (toujours présente, écrite en SQL brut donc non affectée par le bug
 * d'écriture Hibernate/jsonb documenté séparément) pour isoler le problème
 * de LECTURE/SÉRIALISATION.
 */
class NotificationPayloadIntegrationTest extends AbstractIntegrationTest {

    @Test
    void getNotifications_devraitRenvoyerUnPayloadJsonExploitable_pasUnDumpJackson() {
        String token = login("lena.mueller@web.de", "Pair2024!");

        String body = webTestClient.get()
            .uri("/api/notifications?page=0&size=50")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).contains("f1000000-0000-0000-0000-000000000002");

        // Le payload métier réel doit être lisible comme JSON imbriqué...
        assertThat(body).contains("\"fromUserName\":\"Max Schmidt\"");
        // ...et ne doit jamais dégénérer en dump des accesseurs internes de JsonNode.
        assertThat(body).doesNotContain("\"bigDecimal\"");
        assertThat(body).doesNotContain("\"bigInteger\"");
    }

    private String login(String email, String password) {
        AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, password))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }
}
