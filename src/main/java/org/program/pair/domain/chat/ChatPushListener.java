package org.program.pair.domain.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Transforme un message écrit en push {@code NEW_MESSAGE} chez son destinataire.
 *
 * <p><b>Ce qui manquait.</b> {@code ChatService.sendMessage} ne diffusait que par
 * WebSocket, qui ne porte que jusqu'à une application ouverte. Application fermée,
 * un message reçu ne déclenchait donc rien du tout : pas de bannière, et surtout
 * pas de {@code aps.badge} — iOS conservait alors la valeur précédente du badge,
 * ce qui donnait un nombre figé sur l'icône quel que soit le volume reçu.
 *
 * <p>Le type {@code NEW_MESSAGE} et ses textes traduits existaient déjà : aucun
 * producteur ne les émettait.
 *
 * <p>La push part <b>après le commit</b> : le badge qu'elle porte doit compter le
 * message qui vient d'être écrit. {@code notifyPushOnly} étant {@code @Async},
 * l'aller-retour FCM ne s'ajoute pas au temps de réponse de l'envoi.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatPushListener {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSent(MessageSentEvent event) {
        try {
            // Une diffusion de programme n'est pas un message à deux : elle a son
            // type, donc sa route de navigation chez le client, et son titre
            // nomme le programme. Le reste du payload est identique — c'est le
            // même message, écrit au même endroit.
            boolean broadcast = event.programId() != null;

            notificationService.notifyPushOnly(
                event.recipientId(),
                event.senderId(),
                broadcast ? NotificationType.PROGRAM_BROADCAST : NotificationType.NEW_MESSAGE,
                NotificationPayload.empty()
                    .with("conversationId", event.conversationId())
                    .with("messageId", event.messageId())
                    .with("senderId", event.senderId())
                    .with("programId", event.programId())
                    .with("programTitle", event.programTitle())
                    // messageAuthorName/messageBody, et non senderName/
                    // messagePreview : ce sont les noms qu'attend le template de
                    // notification du client, qui range l'auteur du message dans
                    // une zone distincte de l'auteur de la séance. Le renommage
                    // est sûr sans repli — ce payload est construit et consommé
                    // dans le même processus (notifyPushOnly est @Async, pas
                    // distribué), donc aucune version n'en lit d'une autre.
                    .with("messageAuthorName", event.senderName())
                    .with("messageBody", event.preview())
                    .build());
        } catch (Exception e) {
            // Le message est écrit et diffusé : une push perdue ne doit pas
            // faire échouer ce qui a déjà eu lieu.
            log.error("Failed to push new message to user {}: {}",
                event.recipientId(), e.getMessage());
        }
    }
}
