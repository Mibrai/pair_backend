package org.program.pair.domain.notification;

import java.util.UUID;

/**
 * Le non-lu d'un utilisateur vient de changer <b>sans qu'aucune push ne parte</b> :
 * il a lu ses notifications, ou ouvert une conversation.
 *
 * <p>Cas visé : la lecture a eu lieu ailleurs — sur le web, ou sur un second
 * appareil. Le téléphone resté fermé garde alors son badge, qui annonce du non-lu
 * qui n'existe plus. L'événement déclenche un push silencieux d'effacement (voir
 * {@link BadgeSyncListener}).
 *
 * <p>Il est publié <i>dans</i> la transaction qui marque la lecture, mais traité
 * après son commit : le compte doit se lire sur des données écrites, sans quoi le
 * badge réémis serait celui d'avant la lecture.
 */
public record UnreadChangedEvent(UUID userId) {
}
