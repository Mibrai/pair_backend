package org.program.pair.domain.chat;

import java.util.UUID;

/**
 * Un message vient d'être écrit, et {@code recipientId} ne l'a pas encore lu.
 *
 * <p>Un événement <b>par destinataire</b> : chacun a ses appareils, sa langue,
 * sa préférence de push et son propre total de non-lus. Rien de commun à
 * factoriser, et un destinataire dont l'envoi échoue ne doit pas emporter les
 * autres.
 *
 * <p><b>Pourquoi un événement plutôt qu'un appel direct.</b> Le badge se lit en
 * base, et {@code sendMessage} tourne dans une transaction : compté avant son
 * commit, le total oublierait le message qui vient d'être écrit — le badge
 * arriverait sur le téléphone avec une unité de retard, systématiquement.
 * L'écouteur attend donc le commit (voir {@link ChatPushListener}).
 */
public record MessageSentEvent(
    UUID recipientId,
    UUID senderId,
    UUID conversationId,
    UUID messageId,
    String senderName,
    String preview,
    /**
     * Programme du fil de diffusion, {@code null} pour une conversation à deux.
     *
     * <p>C'est ce qui décide du type de notification chez l'écouteur, et donc de
     * l'écran qu'ouvre le tap : le fil du programme plutôt qu'une conversation
     * à deux.
     */
    UUID programId,
    String programTitle
) {
}
