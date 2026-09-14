package org.program.pair.domain.program;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Repousse un envoi après la validation de la transaction en cours, ou le fait
 * tout de suite s'il n'y en a pas.
 *
 * <p><b>Le défaut fermé ici.</b> {@code NotificationService.notify} est
 * {@code @Async} : appelé dans une transaction, il part sur un autre fil
 * <b>avant</b> le commit. Tant qu'une annulation était un geste isolé, la fenêtre
 * ne coûtait presque rien. La fermeture de compte en fait un geste composé —
 * retirer ses inscriptions, annuler chacun de ses créneaux, puis fermer le
 * compte — dans une seule transaction : si la dernière étape échoue, tout est
 * annulé en base, mais les inscrits ont déjà reçu « la séance est annulée » pour
 * un créneau resté ouvert. Et l'application rejoue la route : ils le recevaient
 * une seconde fois.
 *
 * <p><b>Pourquoi pas un {@code @TransactionalEventListener}</b>, comme
 * {@code ScheduleChangeNotificationListener} : l'envoi reste écrit dans la classe
 * qui décide de l'annulation, là où le test « un seul producteur de
 * {@code SLOT_CANCELLED} » le cherche, et les destinataires comme la charge utile
 * sont composés <b>dans</b> la transaction, sur les entités chargées — rien n'a
 * à être relu après coup. Seul l'envoi attend.
 *
 * <p>Même mécanique que {@code domain/indexation/ApresCommit}, qui en documente
 * la raison longuement ; elle est recopiée ici plutôt qu'importée parce que
 * celle-là est privée à son paquet et parle d'indexation.
 *
 * <p>Conséquence assumée : une transaction annulée n'envoie rien, ce qui est
 * exactement le but.
 */
final class EnvoiApresCommit {

    private EnvoiApresCommit() {
    }

    static void executer(Runnable envoi) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    envoi.run();
                }
            });
        } else {
            envoi.run();
        }
    }
}
