package org.program.pair.config;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ClassUtils;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les tâches {@code @Scheduled} tournent sur le planificateur dédié, et plus sur
 * les fils du courtier WebSocket (P-BA-04, étape 1).
 *
 * <p><b>Ce que ces tests protègent</b> n'est pas la présence d'un bean mais un
 * aiguillage que personne ne voit. Le défaut d'origine n'avait été choisi par
 * personne : {@code @EnableWebSocketMessageBroker} déclarant un
 * {@link TaskScheduler}, l'autoconfiguration de Spring Boot renonçait au sien,
 * et {@code @Scheduled} ne trouvait plus qu'un candidat — celui du courtier. Un
 * job long occupait alors un fil qui devait délivrer des battements de cœur
 * STOMP. Rien dans le code ne le disait ; seuls les journaux le montraient, sous
 * la forme {@code [MessageBroker-N] o.p.p.d.program.jobs.ProgramReminderJob}.
 *
 * <p><b>Pourquoi la forme de ces tests, et pas celle que la fiche proposait.</b>
 * La fiche P-BA-04 suggérait une {@code @TestConfiguration} déclarant un
 * {@code @Scheduled(fixedDelay = 100)} qui relèverait son nom de fil. C'eût été
 * une <b>quatrième configuration de contexte Spring</b>, et le cache en retient
 * quatre : la suite est déjà à son plafond, et le 12/09 une configuration de
 * plus a évincé les trois autres, portant la suite de 9 à 37 minutes (voir le
 * bloc surefire du {@code pom.xml}). Cette classe rejoint donc le contexte
 * partagé d'{@link AbstractIntegrationTest} — ni {@code @TestConfiguration}, ni
 * {@code @MockitoBean}, ni {@code @TestPropertySource}, ni
 * {@code @DirtiesContext} — et va chercher l'aiguillage là où il est réellement
 * décidé : dans le registre que Spring a rempli au démarrage. La preuve y est
 * plus forte que dans une tâche de test, car c'est le registre <i>des quatorze
 * vraies tâches</i> qui est interrogé, pas une quinzième écrite pour la
 * circonstance.
 *
 * <p>Aucune donnée n'est créée ni modifiée ici : les quatre tests ne lisent que
 * des beans du contexte. Ils sont donc indifférents à l'ordre d'exécution et à
 * ce que les autres classes ont laissé dans la base partagée.
 */
class SchedulingConfigIntegrationTest extends AbstractIntegrationTest {

    /**
     * Le préfixe que les journaux Railway doivent montrer après déploiement.
     * Recopié depuis {@link SchedulingConfig} : si quelqu'un renomme la
     * constante là-bas, ce test doit devenir rouge plutôt que de suivre.
     */
    private static final String PREFIXE_ATTENDU = "job-";

    /** Le préfixe des fils du courtier STOMP, celui qu'aucun job ne doit porter. */
    private static final String PREFIXE_INTERDIT = "MessageBroker-";

    /**
     * Le nombre de méthodes {@code @Scheduled} recomptées le 12/09 : quatorze,
     * réparties sur dix classes. L'audit d'origine en annonçait treize ;
     * {@code AttendancePromptJob} en porte trois, {@code OutboxSweepJob} et
     * {@code GdprPurgeJob} deux chacune.
     *
     * <p>L'assertion qui s'en sert est un <b>plancher</b> et non une égalité :
     * un job ajouté demain ne doit pas rendre ce test rouge, alors qu'un job qui
     * cesserait d'être planifié doit se faire remarquer.
     */
    private static final int TACHES_PLANIFIEES_AU_12_09 = 14;

    @Autowired private ApplicationContext context;

    @Test
    void planificateurDesJobs_doitPorterLePrefixeJobEtQuatreFils_quandLeContexteEstDemarre() {
        assertThat(context.containsBean("jobScheduler")).isTrue();

        Object bean = context.getBean("jobScheduler");
        assertThat(bean).isInstanceOf(ThreadPoolTaskScheduler.class);

        ThreadPoolTaskScheduler planificateur = (ThreadPoolTaskScheduler) bean;
        assertThat(planificateur.getThreadNamePrefix()).isEqualTo(PREFIXE_ATTENDU);

        // getCorePoolSize() du pool sous-jacent, et non getPoolSize() du
        // planificateur : ce dernier rend le nombre de fils VIVANTS, qui part de
        // zéro et ne monte qu'à mesure des tâches. L'assertion serait verte ou
        // rouge selon l'instant où cette classe est jouée.
        assertThat(planificateur.getScheduledThreadPoolExecutor().getCorePoolSize())
            .isEqualTo(SchedulingConfig.TAILLE_DU_POOL);

        // Et ce n'est pas le pool du courtier déguisé : deux beans distincts,
        // sinon « les jobs quittent les fils du courtier » serait faux tout en
        // ayant l'air vrai.
        assertThat(context.containsBean("messageBrokerTaskScheduler")).isTrue();
        assertThat(context.getBean("messageBrokerTaskScheduler")).isNotSameAs(planificateur);
    }

    /**
     * Le registre que Spring a réellement utilisé désigne notre planificateur.
     *
     * <p>C'est l'assertion centrale de la fiche. {@code ScheduledTaskRegistrar}
     * n'est pas un bean : il vit à l'intérieur du
     * {@link ScheduledAnnotationBeanPostProcessor}, qui le remplit au démarrage
     * puis appelle {@code configureTasks} sur chaque
     * {@code SchedulingConfigurer} du contexte. Le lire suppose donc un accès
     * par réflexion à un champ privé de Spring — assumé, et volontairement
     * <b>non</b> remplacé par une simple vérification du contrat de
     * {@link SchedulingConfig} : appeler nous-mêmes {@code configureTasks} ne
     * prouverait que ce que la classe promet, pas ce que Spring en a fait.
     *
     * <p>Contrepartie : une montée de version de Spring qui renommerait ce champ
     * rendra ce test rouge. C'est le bon symptôme — le message d'échec nomme le
     * champ, et l'aiguillage devra alors être revérifié à la main de toute façon.
     */
    @Test
    void registreDeLaPlanification_doitDesignerLePlanificateurDedie_quandLeContexteEstDemarre() {
        assertThat(registrar().getScheduler())
            .as("le TaskScheduler du ScheduledTaskRegistrar de Spring")
            .isSameAs(context.getBean("jobScheduler"));

        // Les quatorze tâches sont bien enregistrées : un aiguillage juste sur un
        // registre vide ne prouverait rien.
        Set<ScheduledTask> taches = context.getBean(ScheduledAnnotationBeanPostProcessor.class)
            .getScheduledTasks();
        assertThat(taches).hasSizeGreaterThanOrEqualTo(TACHES_PLANIFIEES_AU_12_09);
    }

    /**
     * La preuve par le nom du fil, prise sur le planificateur que Spring a
     * retenu — pas sur le bean, qu'on pourrait avoir raison de vérifier et tort
     * de croire branché.
     */
    @Test
    void tacheDuRegistre_doitTournerSurUnFilJob_quandElleEstExecutee() throws Exception {
        TaskScheduler celuiQuiPlanifieLesJobs = registrar().getScheduler();
        assertThat(celuiQuiPlanifieLesJobs).isNotNull();

        CompletableFuture<String> nomDuFil = new CompletableFuture<>();
        celuiQuiPlanifieLesJobs.schedule(
            () -> nomDuFil.complete(Thread.currentThread().getName()),
            Instant.now());

        // Dix secondes : les quatre fils peuvent être occupés par un vrai job au
        // moment où cette classe passe, puisque la suite partage un contexte et
        // que les boucles de veille tournent pour de bon.
        String nom = nomDuFil.get(10, TimeUnit.SECONDS);

        assertThat(nom).startsWith(PREFIXE_ATTENDU);
        assertThat(nom).doesNotStartWith(PREFIXE_INTERDIT);
    }

    /**
     * Quartz a quitté le classpath (P-BA-04, étape 2).
     *
     * <p>La dépendance était déclarée et jamais utilisée. Ce que sa présence
     * coûtait n'était pas le poids du jar mais une autoconfiguration qui
     * démarrait un second ordonnanceur, et un candidat de plus à la résolution
     * par type dans une application qui en avait déjà trop.
     *
     * <p>Le test porte sur la <b>classe</b> et non sur le {@code pom.xml} : une
     * dépendance retirée d'un endroit et ramenée en transitive par un autre
     * laisserait le pom propre et le classpath inchangé.
     */
    @Test
    void quartz_doitAvoirQuitteLeClasspath_quandLeStarterEstRetire() {
        assertThat(ClassUtils.isPresent("org.quartz.Scheduler", null)).isFalse();
    }

    /**
     * Le registre interne du {@link ScheduledAnnotationBeanPostProcessor}, celui
     * qui porte les quatorze tâches et le planificateur qui les exécute.
     */
    private ScheduledTaskRegistrar registrar() {
        ScheduledAnnotationBeanPostProcessor processeur =
            context.getBean(ScheduledAnnotationBeanPostProcessor.class);

        Object champ = ReflectionTestUtils.getField(processeur, "registrar");
        assertThat(champ)
            .as("champ privé « registrar » de ScheduledAnnotationBeanPostProcessor "
                + "— renommé par une montée de version de Spring ?")
            .isInstanceOf(ScheduledTaskRegistrar.class);
        return (ScheduledTaskRegistrar) champ;
    }
}
