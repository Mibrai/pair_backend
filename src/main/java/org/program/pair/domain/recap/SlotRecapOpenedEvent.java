package org.program.pair.domain.recap;

import java.time.Instant;
import java.util.UUID;

/**
 * La carte-souvenir d'une séance vient de naître — sa première contribution
 * vient d'arriver.
 *
 * <p>C'est <b>l'instant où une affiche devient calculable</b> : jusque-là, la
 * séance n'apparaissait dans aucune carte, donc dans aucun
 * {@code GET /api/recaps/mine}, donc dans rien que le client puisse composer.
 *
 * <p><b>Pourquoi un événement, et pas un appel direct à
 * {@code NotificationService} depuis {@code SlotRecapService}.</b> Deux raisons,
 * dont une seule aurait suffi :
 *
 * <ul>
 *   <li><b>la transaction</b> — {@code NotificationService.notify} est
 *       {@code @Async} : appelé au milieu d'une contribution, il part avant le
 *       commit et survit à un rollback. Une notification annonçant une carte qui
 *       n'existe pas mène le destinataire sur un écran vide. Un
 *       {@code @TransactionalEventListener(AFTER_COMMIT)} ne peut pas se
 *       tromper là-dessus ;</li>
 *   <li><b>le sens de la dépendance</b> — la carte-souvenir n'a pas à connaître
 *       l'existence des affiches. Elle annonce ce qu'elle sait : une carte s'est
 *       ouverte. Ce que le module « affiche » en fait ne la regarde pas.</li>
 * </ul>
 *
 * @param openedBy qui a contribué le premier — il vient d'agir dans
 *                 l'application, sur cet écran précisément, et n'a pas besoin
 *                 qu'on l'y renvoie
 */
public record SlotRecapOpenedEvent(UUID scheduleId, Instant occurrenceStart, UUID openedBy) {}
