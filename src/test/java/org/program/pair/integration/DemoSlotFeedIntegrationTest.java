package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduit le bug de démo/production : les créneaux du seed (München)
 * étaient tous dans le passé une fois la migration appliquée depuis plus de
 * quelques jours, rendant /api/slots/feed — l'écran d'accueil du produit —
 * systématiquement vide même avec une position correcte à Munich. La
 * migration V47 + le job de reconduction hebdomadaire garantissent qu'au
 * moins deux créneaux ouverts, de deux hôtes différents, restent toujours
 * visibles dans la fenêtre "maintenant -> +7 jours".
 */
class DemoSlotFeedIntegrationTest extends AbstractIntegrationTest {

    private static final double MUNICH_LAT = 48.1351;
    private static final double MUNICH_LNG = 11.5820;

    @Test
    void feedAutourDeMunich_neDoitJamaisEtreVide_etDoitCouvrirAuMoinsDeuxHotes() {
        String token = registerAndLogin("demo-feed-checker@pair.app");

        List<SlotFeedItemDto> feed = webTestClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/api/slots/feed")
                .queryParam("lat", MUNICH_LAT)
                .queryParam("lng", MUNICH_LNG)
                .queryParam("radiusMeters", 20000)
                .build())
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(feed).isNotNull();
        assertThat(feed).as("le feed autour de Munich ne doit jamais être vide").isNotEmpty();

        long distinctHosts = feed.stream().map(item -> item.host().id()).distinct().count();
        assertThat(distinctHosts)
            .as("le feed doit couvrir des créneaux d'au moins deux hôtes différents")
            .isGreaterThanOrEqualTo(2);

        assertThat(feed).anySatisfy(item -> assertThat(item.isOpenToPartners()).isTrue());
    }

    private String registerAndLogin(String email) {
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", email.split("@")[0]))
            .exchange()
            .expectStatus().isCreated();

        AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new org.program.pair.domain.auth.dto.LoginRequest(email, "Password123!"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }
}
