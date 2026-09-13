package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.chat.dto.ConversationSummaryDto;
import org.program.pair.domain.chat.dto.CreateConversationRequest;
import org.program.pair.domain.chat.dto.MessageDto;
import org.program.pair.domain.chat.dto.SendMessageRequest;
import org.program.pair.domain.user.dto.UserPrivateDto;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demande mobile du 13/09 (messagerie, P-BA-14) — l'historique d'un fil applique
 * enfin {@code limit}, et se relit par curseur.
 */
class HistoriqueCurseurIntegrationTest extends AbstractIntegrationTest {

    @Test
    void limit_estApplique_etLeFilSansCurseurResteDuPlusRecentAuPlusAncien() {
        Fil fil = fil(5);

        List<MessageDto> deux = lire(fil, b -> b.queryParam("limit", 2));

        assertThat(deux).extracting(MessageDto::id)
            .containsExactly(fil.ids().get(4), fil.ids().get(3));
    }

    @Test
    void limit_estPlafonneACinquante() {
        Fil fil = fil(1);

        assertThat(lire(fil, b -> b.queryParam("limit", 500))).hasSize(1);
        webTestClient.get().uri(b -> uri(b, fil).queryParam("limit", 0).build(fil.conversationId()))
            .headers(h -> h.setBearerAuth(fil.token()))
            .exchange().expectStatus().isBadRequest();
    }

    @Test
    void after_rendLesNouveautesDansLOrdre_puisRienQuandIlNYARienDeNeuf() {
        Fil fil = fil(4);

        assertThat(lire(fil, b -> b.queryParam("after", fil.ids().get(1))))
            .extracting(MessageDto::id)
            .containsExactly(fil.ids().get(2), fil.ids().get(3));
        assertThat(lire(fil, b -> b.queryParam("after", fil.ids().get(3)))).isEmpty();

        UUID nouveau = envoyer(fil.token(), fil.conversationId(), "encore un");
        assertThat(lire(fil, b -> b.queryParam("after", fil.ids().get(3))))
            .extracting(MessageDto::id).containsExactly(nouveau);
    }

    @Test
    void before_rendLaPagePrecedente_duPlusAncienAuPlusRecent() {
        Fil fil = fil(5);

        assertThat(lire(fil, b -> b.queryParam("before", fil.ids().get(4)).queryParam("limit", 2)))
            .extracting(MessageDto::id)
            .containsExactly(fil.ids().get(2), fil.ids().get(3));
    }

    @Test
    void unCurseurInconnuOuDUnAutreFil_rend400() {
        Fil fil = fil(1);
        Fil autre = fil(1);

        for (UUID curseur : List.of(UUID.randomUUID(), autre.ids().get(0))) {
            webTestClient.get().uri(b -> uri(b, fil).queryParam("after", curseur).build(fil.conversationId()))
                .headers(h -> h.setBearerAuth(fil.token()))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_PARAMETER");
        }
        webTestClient.get().uri(b -> uri(b, fil).queryParam("after", fil.ids().get(0))
                .queryParam("before", fil.ids().get(0)).build(fil.conversationId()))
            .headers(h -> h.setBearerAuth(fil.token()))
            .exchange().expectStatus().isBadRequest();
    }

    // — décor —

    private record Fil(String token, UUID conversationId, List<UUID> ids) {}

    private Fil fil(int messages) {
        String a = compte("histo-a");
        String b = compte("histo-b");
        UUID conversationId = webTestClient.post().uri("/api/conversations")
            .headers(h -> h.setBearerAuth(a))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new CreateConversationRequest(userId(b), null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(ConversationSummaryDto.class).returnResult().getResponseBody().id();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < messages; i++) {
            ids.add(envoyer(i % 2 == 0 ? a : b, conversationId, "message " + i));
        }
        return new Fil(a, conversationId, ids);
    }

    private UUID envoyer(String token, UUID conversationId, String contenu) {
        return webTestClient.post().uri("/api/conversations/{id}/messages", conversationId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SendMessageRequest(conversationId, contenu))
            .exchange().expectStatus().isCreated()
            .expectBody(MessageDto.class).returnResult().getResponseBody().id();
    }

    private List<MessageDto> lire(Fil fil, Function<UriBuilder, UriBuilder> parametres) {
        return webTestClient.get()
            .uri(b -> parametres.apply(uri(b, fil)).build(fil.conversationId()))
            .headers(h -> h.setBearerAuth(fil.token()))
            .exchange().expectStatus().isOk()
            .expectBodyList(MessageDto.class).returnResult().getResponseBody();
    }

    private static UriBuilder uri(UriBuilder b, Fil fil) {
        return b.path("/api/conversations/{id}/messages");
    }

    private UUID userId(String token) {
        return webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(UserPrivateDto.class).returnResult().getResponseBody().id();
    }

    private String compte(String prefixe) {
        String email = uniqueEmail(prefixe);
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Histo"))
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
