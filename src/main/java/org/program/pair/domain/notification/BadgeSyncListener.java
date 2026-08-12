package org.program.pair.domain.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Remet le badge d'icône à sa valeur quand la lecture a eu lieu ailleurs.
 *
 * <p>Aucune push ordinaire ne part sur une lecture : sans ce rattrapage, le
 * téléphone resté fermé garde le badge d'avant jusqu'à la prochaine ouverture de
 * l'app — c'est-à-dire exactement jusqu'au moment où le badge ne sert plus.
 *
 * <p><b>Après commit, et hors du fil appelant.</b> {@code AFTER_COMMIT} garantit
 * que le compte relu inclut la lecture qui vient d'avoir lieu ; {@code @Async}
 * évite qu'un aller-retour FCM ne s'ajoute au temps de réponse de la requête qui
 * a déclenché tout ça.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BadgeSyncListener {

    private final UnreadCounter unreadCounter;
    private final PushNotificationServiceInterface pushService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUnreadChanged(UnreadChangedEvent event) {
        try {
            pushService.sendBadgeUpdate(event.userId(), unreadCounter.badge(event.userId()));
        } catch (Exception e) {
            // Un badge non rafraîchi se corrige à la prochaine ouverture de l'app :
            // ce n'est pas une raison pour faire remonter une erreur au lecteur.
            log.error("Failed to refresh badge for user {}: {}", event.userId(), e.getMessage());
        }
    }
}
