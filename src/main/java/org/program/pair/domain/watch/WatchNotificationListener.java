package org.program.pair.domain.watch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.notification.NotificationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fait partir les notifications de veille <b>après le commit</b> de la
 * transaction qui a validé le changement d'état.
 *
 * <p>Même patron que {@code ChatPushListener}, pour une raison voisine mais plus
 * grave : là-bas un badge arrivait avec une unité de retard, ici un rappel ou une
 * alerte partait pour un état qui n'a jamais été enregistré. Les deux boucles de
 * veille avalaient chaque {@code RuntimeException} de leur tour, mais la
 * transaction unique qui les portait était déjà marquée <i>rollback-only</i> :
 * le passage entier était perdu au commit, pushs déjà envoyés.
 *
 * <p><b>Pas de {@code fallbackExecution = true}</b>, et c'est délibéré.
 * {@link WatchEscalationService} est {@code @Transactional} sur la classe : un
 * événement publié hors transaction y est impossible tant que personne n'appelle
 * ses méthodes de l'intérieur de la classe. Si cela arrivait, la notification
 * silencieusement perdue est un défaut qu'on veut voir en test, pas un défaut
 * qu'un repli masquerait en production.
 *
 * <p>Les échecs sont avalés : l'état est enregistré et commité, une push perdue
 * ne doit pas faire échouer ce qui a déjà eu lieu — et surtout pas remonter dans
 * la boucle, qui n'a plus de transaction à annuler à ce stade.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WatchNotificationListener {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationDemandee(WatchNotificationDemandee event) {
        try {
            // La variante qui connaît l'acteur : le filtre de blocage est posé au
            // point de passage de NotificationService, et un acteur nul y vaut
            // « personne n'a provoqué ceci » — le cas de tout ce qu'émettent les
            // minuteurs.
            notificationService.notify(
                event.userId(), event.actorId(), event.type(), event.payload());
        } catch (Exception e) {
            log.error("Veille : notification {} non partie à {} : {}",
                event.type(), event.userId(), e.getMessage());
        }
    }
}
