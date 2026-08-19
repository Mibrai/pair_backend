package org.program.pair.domain.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Le payload d'un message poussé — N2, option A.
 *
 * <p>Le client range l'auteur du <b>message</b> dans une zone distincte de
 * l'auteur de la <b>séance</b> : deux personnes différentes, deux clés
 * différentes. D'où {@code messageAuthorName}/{@code messageBody} plutôt que
 * {@code senderName}/{@code messagePreview}.
 *
 * <p>Ce qui n'est délibérément <b>pas</b> ici : le contexte de la séance
 * (programme, activité, lieu). Le fil de discussion ne désigne pas de programme
 * dans ce modèle — {@code Conversation} porte un contexte d'<i>activité</i> —
 * donc il n'y a rien à joindre. C'est le sens de l'option A.
 */
@ExtendWith(MockitoExtension.class)
class ChatPushListenerTest {

    @Mock NotificationService notificationService;

    @InjectMocks ChatPushListener listener;

    @Test
    void doitPorterLAuteurEtLeCorpsDuMessage_sousLesNomsDuTemplate() {
        UUID recipient = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();

        listener.onMessageSent(new MessageSentEvent(
            recipient, sender, conversation, UUID.randomUUID(),
            "Sophie Martin", "On se retrouve devant le court 3 ?", null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notifyPushOnly(
            eq(recipient), any(), eq(NotificationType.NEW_MESSAGE), payload.capture());

        Map<String, Object> sent = payload.getValue();
        assertThat(sent.get("messageAuthorName")).isEqualTo("Sophie Martin");
        assertThat(sent.get("messageBody")).isEqualTo("On se retrouve devant le court 3 ?");
        // Les anciens noms ne doivent plus sortir : servir les deux ferait vivre
        // deux contrats pour une même donnée.
        assertThat(sent).doesNotContainKeys("senderName", "messagePreview");
        // La clé de jointure reste un identifiant, jamais un nom d'affichage.
        assertThat(sent.get("conversationId")).isEqualTo(conversation.toString());
        assertThat(sent.get("senderId")).isEqualTo(sender.toString());
    }

    @Test
    void uneDiffusionDeProgramme_doitPartirSousSonPropreType() {
        // Le tap doit ouvrir le fil du programme, pas une conversation à deux :
        // c'est le type qui porte la route de navigation chez le client.
        UUID recipient = UUID.randomUUID();
        UUID program = UUID.randomUUID();

        listener.onMessageSent(new MessageSentEvent(
            recipient, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "Sophie Martin", "Séance déplacée à 19h", program, "Yoga du matin"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notifyPushOnly(
            eq(recipient), any(), eq(NotificationType.PROGRAM_BROADCAST), payload.capture());

        Map<String, Object> sent = payload.getValue();
        assertThat(sent.get("programId")).isEqualTo(program.toString());
        assertThat(sent.get("programTitle")).isEqualTo("Yoga du matin");
    }

    @Test
    void uneErreurDePush_neDoitPasRemonter() {
        // Le message est écrit et diffusé par WebSocket avant d'arriver ici : une
        // push perdue ne doit pas faire échouer ce qui a déjà eu lieu.
        doThrow(new RuntimeException("FCM indisponible"))
            .when(notificationService).notifyPushOnly(any(), any(), any(), anyMap());

        assertThatCode(() -> listener.onMessageSent(new MessageSentEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "Sophie Martin", "Bonjour", null, null)))
            .doesNotThrowAnyException();
    }
}
