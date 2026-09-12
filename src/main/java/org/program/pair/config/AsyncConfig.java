package org.program.pair.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Les exécuteurs des tâches d'arrière-plan, et la seule règle qui compte pour
 * eux : <b>aucun ne doit pouvoir ouvrir plus de connexions que le pool n'en a</b>.
 *
 * <p>La classe implémente {@link AsyncConfigurer} pour une raison précise, qui
 * n'est pas un goût de configuration explicite. Spring Boot déclare son
 * {@code applicationTaskExecutor} sous {@code @ConditionalOnMissingBean(Executor.class)} :
 * la seule présence d'{@link #indexationExecutor()} le fait renoncer. Il ne
 * reste alors <b>aucun</b> exécuteur que {@code @Async} sans qualificatif sache
 * choisir — plusieurs candidats, dont ceux du courtier STOMP, et aucun nommé
 * {@code taskExecutor}. Spring l'écrit au démarrage, et on l'a relu tel quel
 * dans quatre rapports de tests du 10/09 :
 *
 * <pre>
 * More than one TaskExecutor bean found within the context, and none is named
 * 'taskExecutor' … [indexationExecutor, clientInboundChannelExecutor,
 * clientOutboundChannelExecutor, brokerChannelExecutor, messageBrokerTaskScheduler]
 * </pre>
 *
 * <p>Faute de candidat, {@code @Async} retombe sur un {@code SimpleAsyncTaskExecutor}
 * neuf, qui <b>n'est pas un pool</b> : il crée un fil par tâche, sans borne.
 * Dix méthodes de l'application sont concernées — celles sans qualificatif ;
 * {@code IndexationService} a le sien. Quatre sont sur
 * {@code NotificationService}, {@code @Transactional} de classe : une rafale de
 * rappels d'agenda réclamait donc autant de connexions que de notifications,
 * pour un pool de 20 ({@code spring.datasource.hikari.maximum-pool-size}) et
 * une attente de 10 s. Le symptôme n'est pas « une notification en retard »
 * mais une requête HTTP ordinaire qui attend une connexion et rend 500.
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    /**
     * L'exécuteur de tous les {@code @Async} sans qualificatif.
     *
     * <p><b>Le nom est le contrat.</b> {@code taskExecutor} est précisément le
     * nom que Spring cherche avant de se rabattre sur un
     * {@code SimpleAsyncTaskExecutor} ; le renommer réintroduirait le défaut
     * sans rien changer d'autre. Il est aussi rendu par
     * {@link #getAsyncExecutor()}, ce qui rend le choix explicite plutôt que
     * dépendant d'une résolution par nom.
     *
     * <p><b>Huit fils au plus</b>, pour 20 connexions : douze restent aux
     * requêtes HTTP, qui sont ce que quelqu'un attend devant son écran. Une
     * notification qui part trois secondes plus tard ne se voit pas ; une
     * requête qui attend une connexion pendant dix secondes se voit toujours.
     *
     * <p><b>{@code CallerRunsPolicy}</b> — même raisonnement que le commentaire
     * d'{@link #indexationExecutor()}, et il vaut la peine d'être redit : la
     * politique par défaut est {@code AbortPolicy}, qui <i>lève</i> dans le fil
     * appelant quand la file déborde. Un job de rappels qui soumet 200
     * notifications verrait donc son tour échouer, notifications perdues
     * comprises. {@code CallerRunsPolicy} exécute la tâche sur le fil appelant :
     * le job s'allonge — c'est la contrepartie, elle est voulue et visible —
     * mais rien n'est perdu et personne ne reçoit d'erreur.
     *
     * <p>La file de 500 absorbe une rafale entière avant que ce repli ne joue.
     *
     * <p><b>Une réserve à garder en tête, relevée le 12/09 mais non mesurée ici.</b>
     * Les producteurs de ce serveur émettent <b>une tâche par destinataire</b> — ainsi
     * {@code SubscriptionService} termine par
     * {@code retained.values().forEach(c -> notificationService.notify(...))}. Publier
     * un programme suivi par mille personnes dépose donc mille tâches d'un coup, et
     * au-delà de 500 c'est {@code CallerRunsPolicy} qui prend le relais : la personne
     * qui publie paierait l'envoi dans sa propre requête. La fiche P-BA-02 accepte
     * qu'« un job puisse s'allonger », ce qui est vrai d'un job et faux d'une requête.
     * Agrandir la file ne ferait que déplacer le seuil : le correctif est que l'éventail
     * dépose <b>une</b> tâche qui boucle, et non une par destinataire — étape 4 de
     * P-BA-02 ({@code Notifier}, lot 2). À traiter là, pas ici.
     */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // À l'arrêt, les tâches déjà en file partent : une notification en vol
        // n'est pas rejouable, personne ne la redemandera. Au-delà de 30 s on
        // abandonne, pour ne pas retenir un redéploiement.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /**
     * {@inheritDoc}
     *
     * <p>L'appel passe par le proxy CGLIB de la configuration : c'est donc le
     * <b>singleton</b> {@code taskExecutor} du contexte qui est rendu, pas une
     * instance neuve. Deux pools de huit fils passeraient inaperçus et
     * doubleraient la consommation de connexions.
     */
    @Override
    public Executor getAsyncExecutor() {
        return taskExecutor();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sans ce gestionnaire, une exception levée par une méthode
     * {@code @Async} au retour {@code void} est journalisée par Spring sans
     * indiquer d'où elle vient : on lit une trace, jamais quelle notification a
     * échoué.
     *
     * <p><b>Les paramètres ne sont pas journalisés, et c'est délibéré.</b> La
     * signature des méthodes concernées est
     * {@code notify(UUID userId, UUID actorId, NotificationType type, Map payload)} :
     * les journaux recevraient des identifiants d'utilisateurs et des charges
     * utiles entières — titres de créneaux, extraits de messages, numéros de
     * contact d'urgence. La classe et le nom de la méthode suffisent à savoir
     * quel chemin a rompu ; la trace jointe dit pourquoi. Ne pas ajouter
     * {@code params} ici (P-BS-19).
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("Tâche asynchrone en échec : {}.{}",
            method.getDeclaringClass().getSimpleName(), method.getName(), ex);
    }

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
        //
        // 12/09 : cette politique était vraie de la lenteur, fausse du blocage.
        // Exécuter la tâche sur le fil de l'appelant PENDANT le commit — ce que
        // décrit le paragraphe ci-dessus — bloquait ce fil définitivement : la
        // tâche porte REQUIRES_NEW et réclamait, par une seconde connexion, le
        // verrou de la ligne que la transaction de l'appelant tenait encore.
        // Six fils HTTP perdus, mesurés. Ce n'est plus possible : les écouteurs
        // d'entité repoussent désormais la soumission APRÈS le commit (voir
        // ApresCommit, dans domain/indexation). Une fois le commit passé, plus
        // aucun verrou n'est tenu, et exécuter en ligne ne coûte que du temps —
        // ce que cette politique promettait. Ne pas revenir à AbortPolicy pour
        // autant : hors transaction, un rejet ne casse plus la requête, mais il
        // perdrait l'indexation en silence.
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
