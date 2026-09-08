package org.program.pair.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    @Bean(name = "indexationExecutor")
    public Executor indexationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("indexation-");

        // Saturé, l'exécuteur exécute la tâche sur le fil appelant au lieu de la
        // refuser.
        //
        // Sans cette ligne, la politique par défaut est AbortPolicy : au-delà de
        // 5 fils occupés et 100 tâches en file, submit() lève. Or l'indexation
        // est soumise par ProgramIndexationListener PENDANT le commit — le rejet
        // remonte donc dans la transaction de l'utilisateur, qui échoue. Le
        // symptôme n'est pas « un programme mal indexé » mais « la création du
        // programme rend 500 », ce qui est bien pire que ce que l'indexation
        // prétend coûter.
        //
        // Constaté le 08/09 en faisant tourner la suite dans une seule JVM :
        // 206 tests rouges sur 23 classes, tous sur « ExecutorService in active
        // state did not accept task », dont un 201 CREATED devenu 500. Le fork
        // par classe le masquait — chaque classe avait son exécuteur neuf et ne
        // le remplissait jamais. La saturation, elle, n'a rien d'un artefact de
        // test : il suffit d'une rafale de créations en production.
        //
        // CallerRunsPolicy ralentit l'appelant quand la file déborde, ce qui est
        // exactement la contrepartie voulue : l'indexation reste faite, la
        // requête reste servie, et la lenteur se voit au lieu d'une erreur.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();

        log.info("Indexation async executor initialized: core={}, max={}, queue={}",
            executor.getCorePoolSize(),
            executor.getMaxPoolSize(),
            executor.getQueueCapacity());

        return executor;
    }
}
