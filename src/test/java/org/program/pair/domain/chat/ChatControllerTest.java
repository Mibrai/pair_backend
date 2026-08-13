package org.program.pair.domain.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.chat.dto.SendMessageRequest;
import org.program.pair.domain.user.User;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.security.UserPrincipal;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * La conversation adressée par l'URL fait foi.
 *
 * <p>Ce que ces tests verrouillent, et qui était faux : {@code sendMessage}
 * déclarait {@code @PathVariable conversationId} et ne s'en servait pas — le
 * message partait sur l'identifiant du corps, quel que soit celui du chemin.
 * Sans conséquence tant que les deux coïncident, mais une autorisation écrite
 * sur la ressource adressée aurait porté sur une conversation pendant que
 * l'écriture se faisait dans une autre.
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    ChatService chatService;

    @InjectMocks
    ChatController chatController;

    @Test
    void sendMessage_devraitRefuser_siLeCorpsDesigneUneAutreConversation() {
        UUID addressed = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertThatThrownBy(() -> chatController.sendMessage(
            principal(UUID.randomUUID()),
            addressed,
            new SendMessageRequest(other, "Bonjour")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("ne correspond pas");

        // Refus avant tout envoi : rien ne doit partir dans l'une ni dans l'autre.
        verifyNoInteractions(chatService);
    }

    @Test
    void sendMessage_devraitTransmettre_siLesDeuxCoincident() {
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        SendMessageRequest request = new SendMessageRequest(conversationId, "Bonjour");

        assertThatCode(() -> chatController.sendMessage(principal(senderId), conversationId, request))
            .doesNotThrowAnyException();

        verify(chatService).sendMessage(senderId, request);
    }

    private UserPrincipal principal(UUID userId) {
        User user = new User();
        user.setId(userId);
        return new UserPrincipal(user);
    }
}
