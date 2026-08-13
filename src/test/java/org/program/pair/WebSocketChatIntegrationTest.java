package org.program.pair;

import org.junit.jupiter.api.Test;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.chat.dto.ConversationSummaryDto;
import org.program.pair.domain.chat.dto.CreateConversationRequest;
import org.program.pair.domain.chat.dto.MessageDto;
import org.program.pair.domain.chat.dto.SendMessageRequest;
import org.program.pair.domain.user.dto.UserPrivateDto;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSocketChatIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void messageEnvoyeViaWebSocket_doitEtreRecuParLAutreMembre() throws Exception {
        // Note: Ce test nécessite que WebSocket soit activé dans WebSocketConfig
        // Si WebSocket n'est pas encore configuré, ce test échouera

        // Enregistrer deux utilisateurs
        String tokenA = registerAndLogin("wsA@pair.app", "Password123!", "UserA");
        String tokenB = registerAndLogin("wsB@pair.app", "Password123!", "UserB");

        // Créer une conversation entre A et B
        UUID targetUserId = getUserId(tokenB);
        UUID conversationId = createConversation(tokenA, targetUserId);

        // Configurer le client WebSocket STOMP
        WebSocketStompClient stompClient = new WebSocketStompClient(
            new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        // Headers de connexion avec le token de userB
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + tokenB);

        // Future pour synchroniser la réception du message
        CompletableFuture<MessageDto> receivedMessage = new CompletableFuture<>();

        try {
            // UserB se connecte au WebSocket
            StompSession sessionB = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws/chat",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS);

            // UserB s'abonne à sa file de messages
            sessionB.subscribe("/user/queue/messages", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return MessageDto.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    receivedMessage.complete((MessageDto) payload);
                }
            });

            // UserA envoie un message via REST (qui déclenche le broadcast WebSocket)
            sendMessageViaRest(tokenA, conversationId, "Salut via WebSocket !");

            // Attendre la réception du message
            MessageDto received = receivedMessage.get(5, TimeUnit.SECONDS);

            // Vérifications
            assertThat(received.content()).isEqualTo("Salut via WebSocket !");
            assertThat(received.conversationId()).isEqualTo(conversationId);

            // Fermer la session
            sessionB.disconnect();
        } catch (Exception e) {
            // Si WebSocket n'est pas configuré, on affiche un message informatif
            System.err.println("ATTENTION: Ce test nécessite que WebSocket soit activé dans WebSocketConfig");
            System.err.println("Erreur: " + e.getMessage());
            // On relance l'exception pour que le test échoue
            throw e;
        }
    }

    @Test
    void connexionWebSocket_devraitEchouer_sansTokenValide() {
        // Configurer le client WebSocket STOMP
        WebSocketStompClient stompClient = new WebSocketStompClient(
            new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));

        // Headers avec un token invalide
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer token.invalide.xyz");

        // Tenter de se connecter avec un token invalide
        assertThatThrownBy(() ->
            stompClient.connectAsync(
                "ws://localhost:" + port + "/ws/chat",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {}
            ).get(5, TimeUnit.SECONDS)
        ).hasCauseInstanceOf(Exception.class);
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
}
