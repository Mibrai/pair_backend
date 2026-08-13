package org.program.pair.domain.chat;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le non-lu de la messagerie : celui que somme le badge d'icône.
 *
 * <p>Ce que ces tests verrouillent, et qui était faux : le compte portait sur
 * <i>tous</i> les messages postérieurs à la dernière lecture, ses propres envois
 * compris. Un fil où l'on venait d'écrire trois fois s'annonçait donc à trois non
 * lus chez son auteur — et comme le client somme ce champ pour composer son
 * badge, le nombre affiché montait tout seul en écrivant.
 */
class ConversationUnreadCountIntegrationTest extends AbstractIntegrationTest {

    @Test
    void sesPropresMessages_neDoiventJamaisCompterCommeNonLus() {
        String alice = registerAndLogin("alice.unread@pair.app", "Password123!", "Alice");
        String bob = registerAndLogin("bob.unread@pair.app", "Password123!", "Bob");

        UUID conversationId = createConversation(alice, getUserId(bob));

        sendMessage(alice, conversationId, "Premier");
        sendMessage(alice, conversationId, "Deuxième");
        sendMessage(alice, conversationId, "Troisième");

        // Le destinataire : trois messages, pas un fil.
        assertThat(unreadCount(bob)).isEqualTo(3);

        // L'expéditeur : écrire n'est pas recevoir.
        assertThat(unreadCount(alice)).isZero();
    }

    @Test
    void leCompteParFil_doitTomberSurLeTotal() {
        // Les deux autorités du badge — la somme du client sur la liste des
        // conversations, et l'entier servi par /unread-count — doivent sortir de
        // la même règle, sans quoi le badge change de valeur selon l'écran ouvert.
        String alice = registerAndLogin("alice.sum@pair.app", "Password123!", "Alice");
        String bob = registerAndLogin("bob.sum@pair.app", "Password123!", "Bob");

        UUID conversationId = createConversation(alice, getUserId(bob));
        sendMessage(alice, conversationId, "Un");
        sendMessage(alice, conversationId, "Deux");

        long sommeDesFils = conversations(bob).stream()
            .mapToLong(ConversationSummaryDto::unreadCount)
            .sum();

        assertThat(sommeDesFils).isEqualTo(2);
        assertThat(unreadCount(bob)).isEqualTo(sommeDesFils);
    }

    @Test
    void lireLeFil_doitRamenerLeCompteAZero() {
        // Zéro est une valeur légitime : c'est ainsi qu'un badge s'efface.
        String alice = registerAndLogin("alice.read@pair.app", "Password123!", "Alice");
        String bob = registerAndLogin("bob.read@pair.app", "Password123!", "Bob");

        UUID conversationId = createConversation(alice, getUserId(bob));
        sendMessage(alice, conversationId, "Coucou");
        assertThat(unreadCount(bob)).isEqualTo(1);

        markAsRead(bob, conversationId);

        assertThat(unreadCount(bob)).isZero();
    }

    @Test
    void unMessageSupprime_neDoitPlusCompter() {
        String alice = registerAndLogin("alice.del@pair.app", "Password123!", "Alice");
        String bob = registerAndLogin("bob.del@pair.app", "Password123!", "Bob");

        UUID conversationId = createConversation(alice, getUserId(bob));
        sendMessage(alice, conversationId, "À garder");
        UUID supprime = sendMessage(alice, conversationId, "À supprimer").id();

        assertThat(unreadCount(bob)).isEqualTo(2);

        webTestClient.delete()
            .uri("/api/messages/{messageId}", supprime)
            .headers(headers -> headers.setBearerAuth(alice))
            .exchange()
            .expectStatus().isNoContent();

        // Il ne s'affiche plus qu'en « [Message supprimé] » : le compter
        // annoncerait du contenu à lire qui n'existe plus.
        assertThat(unreadCount(bob)).isEqualTo(1);
    }

    // Helpers

    private long unreadCount(String token) {
        UnreadCountResponse response = webTestClient.get()
            .uri("/api/conversations/unread-count")
            .headers(headers -> headers.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UnreadCountResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(response).isNotNull();
        return response.unreadCount();
    }

    /** Même forme que {@code GET /api/notifications/unread-count}. */
    private record UnreadCountResponse(long unreadCount) {
    }

    private List<ConversationSummaryDto> conversations(String token) {
        List<ConversationSummaryDto> conversations = webTestClient.get()
            .uri("/api/conversations")
            .headers(headers -> headers.setBearerAuth(token))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(ConversationSummaryDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(conversations).isNotNull();
        return conversations;
    }

    private void markAsRead(String token, UUID conversationId) {
        webTestClient.post()
            .uri("/api/conversations/{conversationId}/read", conversationId)
            .headers(headers -> headers.setBearerAuth(token))
            .exchange()
            .expectStatus().isNoContent();
    }

    private MessageDto sendMessage(String token, UUID conversationId, String content) {
        MessageDto message = webTestClient.post()
            .uri("/api/conversations/{conversationId}/messages", conversationId)
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SendMessageRequest(conversationId, content))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(MessageDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(message).isNotNull();
        return message;
    }

    private UUID createConversation(String token, UUID targetUserId) {
        ConversationSummaryDto conversation = webTestClient.post()
            .uri("/api/conversations")
            .headers(headers -> headers.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new CreateConversationRequest(targetUserId, null, null))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(ConversationSummaryDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(conversation).isNotNull();
        return conversation.id();
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

    private String registerAndLogin(String email, String password, String displayName) {
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, password, displayName))
            .exchange()
            .expectStatus().isCreated();

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
