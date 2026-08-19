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

    // Helper methods

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
