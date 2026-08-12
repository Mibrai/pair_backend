package org.program.pair.domain.notification;

import lombok.RequiredArgsConstructor;
import org.program.pair.repository.MessageRepository;
import org.program.pair.repository.NotificationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Ce qui reste à lire pour un utilisateur, et le total qu'affiche le badge
 * d'icône.
 *
 * <p><b>Pourquoi un seul endroit.</b> iOS n'offre <i>qu'un</i> badge par app :
 * celui de l'icône est le total de ce qui reste à lire, toutes natures
 * confondues. Un message non lu y compte autant qu'une notification non lue —
 * l'utilisateur ne distingue pas les deux depuis son écran d'accueil. Trois
 * chemins ont besoin de ce total : la charge push d'une notification, celle
 * d'un message, et le push silencieux d'effacement. S'ils le calculaient
 * chacun, ils divergeraient sans que personne ne s'en aperçoive.
 *
 * <p>Le compte porte sur des <b>messages</b>, pas sur des fils : cinq messages
 * non lus d'une même personne valent cinq. C'est la règle du client, et les
 * deux autorités du badge se contrediraient à chaque bascule premier plan /
 * arrière-plan si le serveur comptait autrement.
 */
@Component
@RequiredArgsConstructor
public class UnreadCounter {

    private final NotificationRepository notificationRepository;
    private final MessageRepository messageRepository;

    /** Notifications in-app non lues. */
    @Transactional(readOnly = true)
    public long notifications(UUID userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    /** Messages non lus, tous fils confondus. */
    @Transactional(readOnly = true)
    public long messages(UUID userId) {
        return messageRepository.countUnreadByUserId(userId);
    }

    /**
     * Valeur du badge d'icône : {@code notifications non lues + messages non lus}.
     *
     * <p>Zéro est une valeur légitime — c'est ainsi qu'un badge s'efface.
     */
    @Transactional(readOnly = true)
    public long badge(UUID userId) {
        return notifications(userId) + messages(userId);
    }
}
