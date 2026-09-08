package org.program.pair.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * L'exécuteur d'indexation ne doit <b>jamais</b> refuser une tâche.
 *
 * <p>Ce n'est pas une exigence de confort. {@code ProgramIndexationListener}
 * soumet l'indexation <b>pendant le commit</b> de la transaction : un refus ne
 * se traduit pas par « un programme mal indexé », mais par une
 * {@code TransactionSystemException} qui fait échouer l'écriture de
 * l'utilisateur. Autrement dit, la création d'un programme rend 500 — pour une
 * tâche d'arrière-plan dont c'était censé être tout l'intérêt de ne pas bloquer.
 *
 * <p>Le défaut était invisible avant le 08/09 parce que la suite forkait une JVM
 * par classe : chaque classe recevait un exécuteur neuf et ne remplissait jamais
 * sa file de 100. Réunies dans une seule JVM, elles ont saturé l'exécuteur
 * partagé, et 206 tests sont tombés sur « ExecutorService in active state did
 * not accept task », dont un {@code 201 CREATED} devenu {@code 500}. La
 * saturation n'a pourtant rien d'un artefact de test : il suffit d'une rafale de
 * créations en production.
 */
class AsyncConfigTest {

    private ThreadPoolTaskExecutor executeur() {
        Executor executor = new AsyncConfig().indexationExecutor();
        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        return (ThreadPoolTaskExecutor) executor;
    }

    @Test
    void saturationNeRefuseAucuneTache() {
        ThreadPoolTaskExecutor executor = executeur();
        // Cinq fils au maximum, cent en file : au-delà de 105 tâches en vol, la
        // politique par défaut (AbortPolicy) lèverait. On en soumet largement
        // plus, et assez lentes pour que la file n'ait pas le temps de se vider.
        int taches = 400;
        AtomicInteger executees = new AtomicInteger();

        try {
            assertThatCode(() -> {
                for (int i = 0; i < taches; i++) {
                    executor.execute(() -> {
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        executees.incrementAndGet();
                    });
                }
            }).doesNotThrowAnyException();

            executor.shutdown();   // setWaitForTasksToCompleteOnShutdown(true)

            // Aucune tâche perdue : celles que la file n'a pas pu prendre ont été
            // exécutées sur le fil appelant, ce qui ralentit mais n'efface rien.
            assertThat(executees).hasValue(taches);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void laPolitiqueDeRejetEstExplicite() {
        ThreadPoolTaskExecutor executor = executeur();
        try {
            // Le comportement est vérifié par le test ci-dessus ; celui-ci nomme
            // l'intention, pour qu'un remplacement par une autre politique — une
            // DiscardPolicy, par exemple, qui perdrait les tâches en silence —
            // se voie comme une décision et non comme un détail.
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        } finally {
            executor.shutdown();
        }
    }
}
