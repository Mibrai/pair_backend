package org.program.pair;

import org.junit.jupiter.api.Test;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.chat.dto.ConversationSummaryDto;
import org.program.pair.domain.chat.dto.CreateConversationRequest;
import org.program.pair.domain.chat.dto.MessageDto;
import org.program.pair.domain.chat.dto.SendMessageRequest;
import org.program.pair.domain.user.dto.UpdateProfileRequest;
import org.program.pair.domain.user.dto.UserPrivateDto;
import org.program.pair.shared.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatFlowIntegrationTest extends AbstractIntegrationTest {

    @Test
    void conversation_neDoitJamaisSeCreer_siCibleRefuseLesMessages() {
        // Enregistrer et login deux utilisateurs
        String tokenA = registerAndLogin(uniqueEmail("initiateur"), "Password123!", "Initiateur");
        String tokenB = registerAndLogin(uniqueEmail("ferme"), "Password123!", "Ferme");

        // UserB désactive la réception de messages
        updateProfile(tokenB, new UpdateProfileRequest(null, null, null, null, false, null));

        // Récupérer l'ID de userB
        UUID targetUserId = getUserId(tokenB);

        // UserA tente de créer une conversation avec userB
        CreateConversationRequest request = new CreateConversationRequest(targetUserId, null, null);

        webTestClient.post()
            .uri("/api/conversations")
            .headers(headers -> headers.setBearerAuth(tokenA))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isForbidden()
            .expectBody(ErrorResponse.class)
            .consumeWith(response -> {
                ErrorResponse error = response.getResponseBody();
                assertThat(error).isNotNull();
            });
    }

    @Test
    void message_contenuXSS_devraitEtreNettoye_avantStockage() {
        // Enregistrer deux utilisateurs
        String tokenA = registerAndLogin(uniqueEmail("a"), "Password123!", "UserA");
        String tokenB = registerAndLogin(uniqueEmail("b"), "Password123!", "UserB");

        // UserA crée une conversation avec userB
        UUID targetUserId = getUserId(tokenB);
        UUID conversationId = createConversation(tokenA, targetUserId);

        // UserA envoie un message avec du contenu XSS
        String maliciousContent = "<script>alert('hack')</script>Salut !";
        sendMessageViaRest(tokenA, conversationId, maliciousContent);

        // UserB récupère les messages
        List<MessageDto> messages = getMessages(tokenB, conversationId);

        // Vérifier que le message a été nettoyé
        assertThat(messages).isNotEmpty();
        assertThat(messages.get(0).content()).doesNotContain("<script>");
        assertThat(messages.get(0).content()).doesNotContain("alert");
    }

    @Test
    void nonMembre_neDoitJamaisAccederALaConversation() {
        // Enregistrer trois utilisateurs
        String tokenA = registerAndLogin(uniqueEmail("a"), "Password123!", "UserA");
        String tokenB = registerAndLogin(uniqueEmail("b"), "Password123!", "UserB");
        String tokenC = registerAndLogin(uniqueEmail("intrus"), "Password123!", "Intrus");

        // UserA crée une conversation avec userB
        UUID targetUserId = getUserId(tokenB);
        UUID conversationId = createConversation(tokenA, targetUserId);

        // UserC (non membre) tente d'accéder aux messages
        webTestClient.get()
            .uri("/api/conversations/{conversationId}/messages", conversationId)
            .headers(headers -> headers.setBearerAuth(tokenC))
            .exchange()
            .expectStatus().value(status ->
                assertThat(status).isIn(HttpStatus.FORBIDDEN.value(), HttpStatus.NOT_FOUND.value())
            );
    }

    // — blocage : ce qu'un fil déjà ouvert doit refuser —
    //
    // Le fil à deux naît tout seul en rejoignant un créneau : au moment du
    // blocage il existe déjà presque toujours, et c'est exactement le cas que
    // visent ces tests — un harceleur qui continue d'écrire là où la porte
    // d'entrée est pourtant fermée.

    @Test
    void unePersonneBloquee_neDoitPlusEcrire_dansLeFilDejaOuvert() {
        String bloqueur = registerAndLogin(uniqueEmail("bloqueur"), "Password123!", "Bloqueur");
        String bloque = registerAndLogin(uniqueEmail("bloque"), "Password123!", "Bloque");
        UUID bloqueId = getUserId(bloque);

        UUID conversationId = createConversation(bloqueur, bloqueId);
        sendMessageViaRest(bloqueur, conversationId, "Bonjour, à demain.");

        block(bloqueur, bloqueId);

        // Le refus est neutre : mot pour mot celui qu'un non-membre reçoit. Un
        // code nommé apprendrait le blocage à qui ne doit rien en savoir.
        webTestClient.post()
            .uri("/api/conversations/{conversationId}/messages", conversationId)
            .headers(headers -> headers.setBearerAuth(bloque))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SendMessageRequest(conversationId, "Tu ne m'échapperas pas."))
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("FORBIDDEN");

        // Et rien n'a été écrit : le refus tombe avant la persistance, pas après.
        assertThat(getMessages(bloqueur, conversationId)).hasSize(1);
    }

    @Test
    void celleQuiABloque_doitRecevoirUSER_BLOCKED_enEcrivant() {
        // L'autre forme du refus, et la réponse opposée : c'est sa décision, elle
        // a droit à savoir pourquoi son message ne part pas.
        String bloqueur = registerAndLogin(uniqueEmail("bloqueur"), "Password123!", "Bloqueur");
        String bloque = registerAndLogin(uniqueEmail("bloque"), "Password123!", "Bloque");
        UUID bloqueId = getUserId(bloque);

        UUID conversationId = createConversation(bloqueur, bloqueId);
        block(bloqueur, bloqueId);

        webTestClient.post()
            .uri("/api/conversations/{conversationId}/messages", conversationId)
            .headers(headers -> headers.setBearerAuth(bloqueur))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SendMessageRequest(conversationId, "Finalement…"))
            .exchange()
            .expectStatus().isForbidden()
            .expectBody()
            .jsonPath("$.code").isEqualTo("USER_BLOCKED");
    }

    @Test
    void unePersonneBloquee_neDoitPlusPartagerSaPosition() {
        // Le contrôle qui compte le plus : une position dit où l'on est.
        String bloqueur = registerAndLogin(uniqueEmail("bloqueur"), "Password123!", "Bloqueur");
        String bloque = registerAndLogin(uniqueEmail("bloque"), "Password123!", "Bloque");
        UUID bloqueId = getUserId(bloque);

        UUID conversationId = createConversation(bloqueur, bloqueId);
        block(bloqueur, bloqueId);

        webTestClient.post()
            .uri("/api/conversations/{conversationId}/location", conversationId)
            .headers(headers -> headers.setBearerAuth(bloque))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("lat", 48.5734, "lng", 7.7521))
            .exchange()
            .expectStatus().isForbidden();

        // Aucune bulle de position dans le fil.
        assertThat(getMessages(bloqueur, conversationId)).isEmpty();
    }

    @Test
    void lHistoriqueDUnFilADeux_doitResterLisible_pourCelleQuiABloque() {
        // Décision D6 : ce qui a été écrit avant reste lisible — c'est la pièce
        // qu'on joint à un signalement, et l'effacer priverait la personne visée
        // de ce qu'elle a besoin de montrer.
        String bloqueur = registerAndLogin(uniqueEmail("bloqueur"), "Password123!", "Bloqueur");
        String bloque = registerAndLogin(uniqueEmail("bloque"), "Password123!", "Bloque");
        UUID bloqueId = getUserId(bloque);

        UUID conversationId = createConversation(bloqueur, bloqueId);
        sendMessageViaRest(bloqueur, conversationId, "On se retrouve où ?");
        sendMessageViaRest(bloque, conversationId, "Je sais où tu habites.");

        block(bloqueur, bloqueId);

        List<MessageDto> historique = getMessages(bloqueur, conversationId);
        assertThat(historique).hasSize(2);
        assertThat(historique).extracting(MessageDto::content)
            .contains("Je sais où tu habites.");
    }

    @Test
    void unFilBloque_doitDireQuIlEstEnLectureSeule_sansLApprendreAuBloque() {
        // Le champ qui permet à l'application d'expliquer au lieu de planter : le
        // composeur se retire avant la frappe, au lieu de perdre un message dans
        // un 403. Sa valeur dépend du côté, et c'est le seul champ du produit
        // dont ce soit le cas.
        String bloqueur = registerAndLogin(uniqueEmail("bloqueur"), "Password123!", "Bloqueur");
        String bloque = registerAndLogin(uniqueEmail("bloque"), "Password123!", "Bloque");
        UUID bloqueId = getUserId(bloque);

        UUID conversationId = createConversation(bloqueur, bloqueId);
        block(bloqueur, bloqueId);

        webTestClient.get()
            .uri("/api/conversations/{conversationId}", conversationId)
            .headers(headers -> headers.setBearerAuth(bloqueur))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.readOnlyReason").isEqualTo("BLOCKED");

        // La forme neutre pour l'autre : ce que dirait aussi un compte désactivé.
        webTestClient.get()
            .uri("/api/conversations/{conversationId}", conversationId)
            .headers(headers -> headers.setBearerAuth(bloque))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.readOnlyReason").isEqualTo("PARTICIPANT_UNAVAILABLE");
    }

    @Test
    void unFilOrdinaire_neDoitPorterAucuneRaisonDeLectureSeule() {
        // Non-régression du champ neuf : il est nul quand le fil s'écrit, et un
        // client qui l'interprète ne doit pas retirer son composeur sans raison.
        String alice = registerAndLogin(uniqueEmail("ouvert-a"), "Password123!", "OuvertA");
        String bob = registerAndLogin(uniqueEmail("ouvert-b"), "Password123!", "OuvertB");

        UUID conversationId = createConversation(alice, getUserId(bob));

        webTestClient.get()
            .uri("/api/conversations/{conversationId}", conversationId)
            .headers(headers -> headers.setBearerAuth(alice))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.readOnlyReason").isEmpty();
    }

    // Helper methods

    private void block(String blockerToken, UUID blockedId) {
        webTestClient.post()
            .uri("/api/users/{id}/block", blockedId)
            .headers(headers -> headers.setBearerAuth(blockerToken))
            .exchange()
            .expectStatus().isNoContent();
    }

    private String registerAndLogin(String email, String password, String displayName) {
        // Enregistrement
        RegisterRequest registerReq = new RegisterRequest(email, password, displayName);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();

        // Login
        LoginRequest loginReq = new LoginRequest(email, password);
        AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(loginReq)
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }

    private UUID getUserId(String token) {
        UserPrivateDto profile = webTestClient.get()
            .uri("/api/users/me")
            .headers(headers -> headers.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UserPrivateDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(profile).isNotNull();
        return profile.id();
    }

    private void updateProfile(String token, UpdateProfileRequest request) {
        webTestClient.put()
            .uri("/api/users/me")
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk();
    }

    private UUID createConversation(String token, UUID targetUserId) {
        CreateConversationRequest request = new CreateConversationRequest(targetUserId, null, null);
        ConversationSummaryDto conversation = webTestClient.post()
            .uri("/api/conversations")
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated()
            .expectBody(ConversationSummaryDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(conversation).isNotNull();
        return conversation.id();
    }

    private void sendMessageViaRest(String token, UUID conversationId, String content) {
        SendMessageRequest request = new SendMessageRequest(conversationId, content);
        webTestClient.post()
            .uri("/api/conversations/{conversationId}/messages", conversationId)
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isCreated();
    }

    private List<MessageDto> getMessages(String token, UUID conversationId) {
        List<MessageDto> messages = webTestClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/api/conversations/{conversationId}/messages")
                .queryParam("limit", 50)
                .build(conversationId))
            .headers(headers -> headers.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(MessageDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(messages).isNotNull();
        return messages;
    }
}
