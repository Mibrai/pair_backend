package org.program.pair.domain.indexation;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Repousse une action après la validation de la transaction en cours, ou l'exécute
 * tout de suite s'il n'y en a pas.
 *
 * <p><b>Le défaut fermé ici.</b> Les écouteurs d'entité de ce paquet soumettaient
 * l'indexation depuis {@code @PostPersist} / {@code @PostUpdate}, donc <b>pendant le
 * commit</b> : la pile le montre, {@code afterUpdate} appelé par
 * {@code flushBeforeTransactionCompletion}. Or {@code IndexationService} porte
 * {@code @Transactional(REQUIRES_NEW)} : la tâche suspend la transaction de l'appelant
 * et en ouvre une seconde, sur une autre connexion, pour écrire
 * {@code UPDATE programs … WHERE id = ?} — sur la ligne même que la première
 * transaction vient de verrouiller.
 *
 * <p>Tant que la tâche partait sur un fil {@code indexation-N}, elle attendait
 * quelques millisecondes que le commit libère le verrou. Mais dès qu'elle
 * s'exécutait <b>sur le fil de l'appelant</b> — ce que fait {@code CallerRunsPolicy}
 * quand la file de 100 déborde — ce fil s'attendait lui-même : la seconde
 * transaction réclame un verrou que seule la première peut rendre, et la première ne
 * peut pas finir puisque le fil est dedans. PostgreSQL n'a pas de délai de verrou par
 * défaut, donc <b>le fil HTTP est perdu définitivement</b>.
 *
 * <p>Mesuré le 12/09 sur la suite complète : six fils {@code http-nio-…-exec-*}
 * bloqués depuis 513 s, chacun avec une vingtaine de secondes de CPU brûlées en
 * attente de verrou, et {@code PublicProgramPageIntegrationTest} — verte depuis le
 * 20/08 — rendant quatorze « Timeout on blocking read for 30 s », une toutes les
 * 31 s, sans une ligne de journal du serveur entre deux. Tomcat n'a que dix fils
 * d'exécution : la suite se dégradait à mesure qu'ils étaient consommés.
 *
 * <p><b>Pourquoi ce correctif plutôt qu'un autre.</b> Le commentaire d'
 * {@code indexationExecutor} garde la trace de la tentative précédente : avec
 * {@code AbortPolicy}, un rejet remontait dans la transaction de l'utilisateur et
 * changeait un {@code 201 CREATED} en {@code 500} — 206 tests rouges le 08/09. On a
 * donc remplacé le rejet par {@code CallerRunsPolicy}, ce qui a troqué une erreur
 * visible contre un blocage invisible. Les deux symptômes ont la même cause, que ce
 * commentaire nommait déjà sans en tirer la conséquence : <b>l'indexation n'a rien à
 * faire dans le commit</b>. Une fois la transaction validée, plus aucun verrou n'est
 * tenu : la tâche peut être soumise, rejetée ou exécutée en ligne sans mettre en
 * danger ni la requête ni le fil.
 */
final class ApresCommit {

    private ApresCommit() {
    }

    /**
     * Exécute {@code action} après le commit, ou immédiatement hors transaction.
     *
     * <p>L'enregistrement est permis depuis un rappel d'entité JPA : la
     * synchronisation est encore active pendant {@code doCommit}, et Spring lit la
     * liste des synchronisations pour {@code afterCommit} après le retour de
     * {@code doCommit}.
     *
     * <p>Conséquence assumée : si la transaction est annulée, l'indexation n'a pas
     * lieu — ce qui est juste, puisqu'il n'y a alors rien de nouveau à indexer.
     */
    static void executer(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
