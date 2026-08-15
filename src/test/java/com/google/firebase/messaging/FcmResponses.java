package com.google.firebase.messaging;

import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseException;

/**
 * Fabrique de {@link SendResponse} réels, pour les tests.
 *
 * <p><b>Pourquoi cette classe est dans le paquet du SDK.</b>
 * {@code SendResponse} et {@code FirebaseMessagingException} sont {@code final},
 * et le simulateur de Mockito de ce projet ne sait pas les intercepter : la vraie
 * méthode s'exécute derrière le {@code when(...)}, qui échoue alors en
 * {@code UnfinishedStubbingException}. Leurs fabriques
 * ({@code SendResponse.fromMessageId}, {@code fromException},
 * {@code FirebaseMessagingException.withMessagingErrorCode}) sont de portée
 * paquet — d'où ce fichier, qui n'existe que sous {@code src/test} et n'est
 * jamais empaqueté.
 *
 * <p>Le résultat est meilleur qu'un mock : le code sous test lit de vrais
 * objets du SDK, avec le comportement réel de {@code isSuccessful()} et de
 * {@code getMessagingErrorCode()}.
 */
public final class FcmResponses {

    private FcmResponses() {
    }

    /** Un envoi accepté par FCM. */
    public static SendResponse success(String messageId) {
        return SendResponse.fromMessageId(messageId);
    }

    /** Un envoi rejeté, portant le code d'erreur de messagerie voulu. */
    public static SendResponse failure(MessagingErrorCode code) {
        FirebaseException cause = new FirebaseException(
            ErrorCode.INVALID_ARGUMENT, "test — " + code, null);
        return SendResponse.fromException(
            FirebaseMessagingException.withMessagingErrorCode(cause, code));
    }
}
