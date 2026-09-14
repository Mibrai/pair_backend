package org.program.pair.config;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock.InterceptMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Le planificateur des tâches {@code @Scheduled}, et la raison pour laquelle il
 * doit être <b>à elles seules</b>.
 *
 * <p><b>Le défaut fermé ici.</b> Les quatorze méthodes {@code @Scheduled} de
 * l'application — réparties sur dix classes : {@code AttendancePromptJob} (3),
 * {@code OutboxSweepJob} (2), {@code GdprPurgeJob} (2), puis
 * {@code ExpiredLocationSweepJob}, {@code RecurringSlotRolloverJob},
 * {@code ProgramReminderJob}, {@code ProgramDormancyJob}, {@code CycleNudgeJob},
 * {@code WatchReturnLoopJob} et {@code WatchOutboundJob} — tournaient sur le
 * planificateur du courtier STOMP. On le lit tel quel dans les journaux :
 * {@code [MessageBroker-N] o.p.p.d.program.jobs.ProgramReminderJob}.
 *
 * <p>Personne ne l'avait décidé. {@code @EnableWebSocketMessageBroker} déclare un
 * bean {@code messageBrokerTaskScheduler} de type
 * {@link org.springframework.scheduling.TaskScheduler} ; l'autoconfiguration de
 * planification de Spring Boot est conditionnée à l'absence d'un tel bean et
 * renonce donc à créer le sien. Il ne reste alors, pour {@code @Scheduled},
 * qu'un unique candidat : celui du courtier.
 *
 * <p><b>Pourquoi c'est un défaut et pas un détail.</b> Le pool du courtier est
 * dimensionné pour des messages WebSocket, qui durent des microsecondes. Les
 * jobs de ce dépôt, eux, ouvrent des transactions et parlent au réseau :
 * {@code GdprPurgeJob} parcourt des tables entières, {@code CycleNudgeJob} et
 * {@code ProgramReminderJob} envoient des rafales de notifications. Pendant
 * qu'un job occupe un fil du courtier, ce fil ne délivre plus rien : les
 * battements de cœur STOMP passent en retard et les clients se voient
 * déconnectés. Le symptôme ne se voit pas du côté des jobs — il se voit sur une
 * conversation qui se coupe à trois heures du matin, à l'heure de la purge.
 *
 * <p><b>Et un verrou en base par job (P-BA-04, lot 2, décision D4 option B).</b>
 * Un planificateur dédié range les jobs ; il ne les protège pas d'eux-mêmes.
 * Deux instances du service — le chevauchement de l'ancien et du nouveau
 * conteneur pendant un déploiement, ou une seconde réplique — exécutaient
 * chacune toutes les méthodes {@code @Scheduled}, et doublaient rappels, SMS et
 * bascule des créneaux récurrents. Chaque méthode porte désormais un
 * {@code @SchedulerLock} nommé, pris dans la table {@code shedlock} (V124) à
 * l'heure de la base : une seule instance l'exécute.
 *
 * <p><b>Le verrou entoure l'exécution planifiée, pas la méthode</b>
 * ({@code InterceptMode.PROXY_SCHEDULER}). Deux raisons. Il est pris <b>avant</b>
 * la transaction des jobs qui en ouvrent une, et non dedans : un verrou écrit
 * dans la transaction du job ne serait visible des autres instances qu'au
 * commit, c'est-à-dire trop tard. Et un appel direct — un test qui déclenche
 * {@code tick()} à la main — n'est pas verrouillé : sans cela, un tick de fond
 * tombant au même instant ferait sauter l'appel du test, sans erreur.
 *
 * <p>Ce que le verrou ne rend pas encore possible : plusieurs répliques
 * durables. Le limiteur de débit reste en mémoire, local à chaque instance
 * (P-BS-08).
 *
 * @see AsyncConfig l'exécuteur des {@code @Async}, dont la javadoc raconte le
 *      même genre de mésaventure — un bean manquant, et Spring qui se rabat sur
 *      un choix que personne n'a fait
 */
@Configuration
@EnableSchedulerLock(
    interceptMode = InterceptMode.PROXY_SCHEDULER,
    defaultLockAtMostFor = SchedulingConfig.VERROU_AU_PLUS_PAR_DEFAUT,
    // Proxy de classe : le bean reste un ThreadPoolTaskScheduler pour qui
    // l'injecte par son type (métriques, tests), et pas une simple interface.
    proxyTargetClass = true)
@Slf4j
public class SchedulingConfig implements SchedulingConfigurer {

    /**
     * La durée au bout de laquelle un verrou se libère seul si l'instance qui le
     * tient est morte en cours de job.
     *
     * <p>Au-dessus de la durée normale de chaque job, sans quoi deux exécutions se
     * chevauchent ; en dessous de sa période quand c'est possible, sans quoi une
     * instance tuée fait sauter le passage suivant. Les jobs dont la période est
     * plus courte le précisent sur leur annotation.
     */
    static final String VERROU_AU_PLUS_PAR_DEFAUT = "PT10M";

    /**
     * Le fournisseur de verrous : la table {@code shedlock}, à l'heure de la base.
     *
     * <p>{@code usingDbTime} et non l'horloge de la JVM : deux conteneurs dont les
     * horloges diffèrent de quelques secondes comparent sinon deux heures
     * différentes, et l'un peut croire expiré un verrou que l'autre tient.
     */
    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(jdbcTemplate)
            .usingDbTime()
            .build());
    }

    /**
     * Le préfixe des fils, qui est la partie visible du correctif.
     *
     * <p>C'est lui qu'on cherche dans les journaux Railway après déploiement :
     * une ligne de job doit porter {@code [job-N]} et jamais
     * {@code [MessageBroker-N]}. Le renommer rendrait muette la vérification
     * après livraison de la fiche P-BA-04.
     */
    static final String PREFIXE_DES_FILS = "job-";

    /**
     * Quatre fils.
     *
     * <p>Le nombre ne vient pas des quatorze méthodes mais de ce qui peut tourner
     * <b>en même temps</b>. Les dix tâches {@code cron} sont échelonnées à la
     * main — 3 h 00, 3 h 20, 3 h 30, 3 h 50, 4 h 00, puis les minutes 15, 40 et
     * les multiples de 5 et 10 — et les quatre tâches {@code fixedDelay} ne
     * relancent qu'après la fin de leur tour, par construction. Quatre
     * exécutions simultanées sont donc déjà un cas de chevauchement.
     *
     * <p>La borne haute n'est pas le processeur mais le pool de connexions :
     * {@code spring.datasource.hikari.maximum-pool-size} vaut 20, dont huit sont
     * déjà promises à {@code AsyncConfig.taskExecutor}. Quatre de plus laissent
     * huit connexions aux requêtes HTTP, qui sont ce que quelqu'un attend devant
     * son écran.
     *
     * <p>Ce que ce nombre coûte s'il est trop petit : un job en retard, pas un
     * job perdu — {@link java.util.concurrent.ScheduledThreadPoolExecutor} met
     * en file, il ne rejette pas. La fiche P-BA-21 mesurera {@code job.duration}
     * et la métrique {@code executor} de ce planificateur ; c'est là qu'on verra
     * s'il faut monter, et non ici.
     */
    static final int TAILLE_DU_POOL = 4;

    /** Le délai laissé à un job en cours pour finir quand le service s'arrête. */
    private static final int SECONDES_AVANT_ABANDON = 30;

    /**
     * Le planificateur des jobs, et lui seul.
     *
     * <p><b>Le gestionnaire d'erreurs journalise sans relancer, et c'est le
     * point délicat.</b> Sur une tâche périodique, une exception qui remonte
     * jusqu'à {@link java.util.concurrent.ScheduledExecutorService} <b>annule la
     * tâche</b> : le job ne repart jamais, en silence, jusqu'au prochain
     * redémarrage. Spring s'en protège par défaut
     * ({@code TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER}) ; en posant notre propre
     * gestionnaire on reprend cette responsabilité, d'où une lambda qui se
     * contente de journaliser. Ne jamais y ajouter de {@code throw} : ce serait
     * troquer un job qui échoue une fois contre un job qui ne tourne plus.
     *
     * <p><b>À l'arrêt</b>, le pool attend jusqu'à trente secondes le job en
     * cours plutôt que de l'interrompre au milieu d'une transaction — une purge
     * coupée en deux laisse un état à moitié effacé. Les tâches périodiques, en
     * revanche, ne sont pas replanifiées : {@code ScheduledThreadPoolExecutor}
     * les annule dès {@code shutdown()}. Au-delà de trente secondes on
     * abandonne, pour ne pas retenir un redéploiement.
     *
     * <p><b>{@code removeOnCancelPolicy}</b> : sans elle, une tâche annulée reste
     * dans la file du pool jusqu'à son heure théorique. Avec quatorze tâches
     * dont certaines toutes les dix secondes, et un contexte Spring réutilisé
     * d'une classe de test à l'autre, cette rétention se voit en mémoire.
     */
    @Bean(name = "jobScheduler")
    public ThreadPoolTaskScheduler jobScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(TAILLE_DU_POOL);
        scheduler.setThreadNamePrefix(PREFIXE_DES_FILS);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(t -> log.error("Job planifié en échec", t));
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(SECONDES_AVANT_ABANDON);
        return scheduler;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>La désignation explicite est tout l'objet de cette méthode.</b> Sans
     * elle, Spring chercherait un {@code TaskScheduler} : d'abord un bean nommé
     * {@code taskScheduler}, qui n'existe pas ici, puis un unique candidat par
     * type — et il y en a désormais deux, {@code jobScheduler} et
     * {@code messageBrokerTaskScheduler}. Deux candidats font renoncer la
     * résolution par type, et l'on retomberait sur le comportement de repli au
     * lieu du planificateur qu'on vient d'écrire. Ce n'est pas une préférence de
     * style : c'est la seule façon de garantir lequel des deux est pris.
     *
     * <p>L'appel à {@link #jobScheduler()} passe par le proxy CGLIB de la
     * configuration : c'est le <b>singleton</b> du contexte qui est rendu, pas
     * une instance neuve. Deux pools de quatre fils, l'un planifiant les jobs et
     * l'autre exposé aux métriques, ne se verraient nulle part.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(jobScheduler());
    }
}
