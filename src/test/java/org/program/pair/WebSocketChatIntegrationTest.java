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
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        String tokenA = registerAndLogin(uniqueEmail("wsA"), "Password123!", "UserA");
        String tokenB = registerAndLogin(uniqueEmail("wsB"), "Password123!", "UserB");

        // Créer une conversation entre A et B
        UUID targetUserId = getUserId(tokenB);
        UUID conversationId = createConversation(tokenA, targetUserId);

        // Configurer le client WebSocket STOMP
        WebSocketStompClient stompClient = new WebSocketStompClient(
            new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));

        // Le convertisseur doit être celui de Jackson 3, comme le serveur.
        // MessageDto porte un Instant, et l'ancien MappingJackson2MessageConverter
        // s'appuie sur un Jackson 2 sans module java.time : il échouait à
        // désérialiser la trame. Le message arrivait donc bien — le journal du
        // courtier le montrait, avec la même destination résolue au SUBSCRIBE et
        // au MESSAGE — mais handleFrame n'était jamais atteint.
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());

        // Headers de connexion avec le token de userB
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + tokenB);

        BlockingQueue<MessageDto> received = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> clientError = new AtomicReference<>();

        // StompSessionHandlerAdapter ne fait rien de ses erreurs. C'est ce
        // silence qui a fait passer ce test pour instable pendant des mois : il
        // expirait au bout de cinq secondes sans jamais dire pourquoi. On les
        // retient, pour que la prochaine panne se lise dans le message d'échec.
        StompSessionHandlerAdapter errorAwareHandler = new StompSessionHandlerAdapter() {
            @Override
            public void handleException(StompSession session, StompCommand command,
                                        StompHeaders headers, byte[] payload, Throwable exception) {
                clientError.compareAndSet(null, exception);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                clientError.compareAndSet(null, exception);
            }
        };

        // UserB se connecte au WebSocket
        StompSession sessionB = stompClient.connectAsync(
            "ws://localhost:" + port + "/ws/chat",
            new WebSocketHttpHeaders(),
            connectHeaders,
            errorAwareHandler
        ).get(5, TimeUnit.SECONDS);

        sessionB.subscribe("/user/queue/messages", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return MessageDto.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((MessageDto) payload);
            }
        });

        // S'abonner est une trame envoyée, pas un appel qui rend la main une fois
        // le serveur prêt : rien ne garantit que le SUBSCRIBE soit traité avant le
        // message, puisqu'ils empruntent deux connexions distinctes. L'accusé de
        // réception STOMP réglerait la question, mais le courtier en mémoire de
        // Spring n'en émet pas. On renvoie donc jusqu'à ce que la file réponde ;
        // le contenu étant identique à chaque tentative, un doublon éventuel ne
        // change rien à l'assertion.
        MessageDto message = null;
        for (int attempt = 1; attempt <= 3 && message == null; attempt++) {
            sendMessageViaRest(tokenA, conversationId, "Salut via WebSocket !");
            message = received.poll(2, TimeUnit.SECONDS);
        }

        assertThat(clientError.get())
            .as("le client STOMP a signalé une erreur")
            .isNull();
        assertThat(message)
            .as("aucun message reçu sur /user/queue/messages")
            .isNotNull();
        assertThat(message.content()).isEqualTo("Salut via WebSocket !");
        assertThat(message.conversationId()).isEqualTo(conversationId);

        sessionB.disconnect();
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
