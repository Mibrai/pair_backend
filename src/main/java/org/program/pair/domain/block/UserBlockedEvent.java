package org.program.pair.domain.block;

import java.util.UUID;

/**
 * Un blocage vient d'être posé.
 *
 * <p><b>Un événement, et non un appel direct, pour une raison de transaction.</b>
 * Les effets d'un blocage débordent largement la table {@code user_blocks} :
 * retirer les inscriptions croisées fait remonter des files d'attente, ce qui
 * <b>promeut quelqu'un et le lui notifie</b>. Or {@code NotificationService.notify}
 * est {@code @Async} et part donc avant le commit : promouvoir depuis la
 * transaction du blocage, c'est risquer d'annoncer une place libérée par un
 * blocage qui n'a pas abouti. Les abonnés de cet événement travaillent en
 * {@code AFTER_COMMIT}, comme {@code ScheduleChangeNotificationListener} et pour
 * la raison qu'{@code ApresCommit} documente.
 *
 * <p><b>Il ne porte aucune notification à personne</b>, et surtout pas à la
 * personne bloquée : le blocage doit rester indétectable par elle. C'est un
 * événement interne, entre modules du même processus.
 *
 * <p>Publié à chaque appel de {@code BlockService.block}, y compris quand le
 * blocage existait déjà : ses abonnés sont idempotents, et rejouer un retrait
 * déjà fait ne coûte que deux lectures.
 *
 * @param blockerId qui a bloqué
 * @param blockedId qui est bloqué
 */
public record UserBlockedEvent(UUID blockerId, UUID blockedId) {}
