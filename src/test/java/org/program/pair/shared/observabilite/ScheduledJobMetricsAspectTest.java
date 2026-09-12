package org.program.pair.shared.observabilite;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ce que l'aspect mesure, et surtout ce qu'il ne peut pas voir.
 *
 * <p>Aucun contexte Spring ici, et c'est délibéré : le cache de contextes de la
 * suite est saturé à quatre entrées, et une configuration de plus a déjà fait
 * passer la suite de 9 à 37 minutes. {@link AspectJProxyFactory} applique
 * l'aspect à un objet nu, ce qui éprouve <b>en plus</b> ce qu'un
 * {@code @MockitoBean} n'éprouverait pas : que le pointcut
 * {@code @annotation(@Scheduled)} accroche réellement, et qu'il n'accroche que
 * ça.
 *
 * <p>Le troisième test est celui qui compte. Les dix jobs de ce projet attrapent
 * leurs exceptions et n'en laissent rien remonter : un aspect seul les verrait
 * tous verts pour toujours. C'est la raison d'être de
 * {@code echecAvale}, et la raison pour laquelle cette classe vérifie que les
 * deux chemins d'échec atterrissent sur la <b>même</b> série.
 */
class ScheduledJobMetricsAspectTest {

    private SimpleMeterRegistry registre;
    private ScheduledJobMetricsAspect aspect;

    @BeforeEach
    void preparer() {
        registre = new SimpleMeterRegistry();
        aspect = new ScheduledJobMetricsAspect(registre);
    }

    /** Le cas ordinaire : le job passe, sa durée et son passage sont comptés. */
    @Test
    void unJob_doitVoirSaDureeMesuree_quandIlReussit() {
        JobFictif job = proxifier(new JobFictif());

        job.tourneBien();
        job.tourneBien();

        assertThat(nombreDExecutions("JobFictif.tourneBien")).isEqualTo(2);
        assertThat(nombreDEchecs("JobFictif.tourneBien")).isZero();
    }

    /**
     * L'exception qui remonte : elle est comptée, et elle continue de remonter.
     * Un aspect de mesure qui avalerait ce qu'il mesure changerait le
     * comportement du job — le planificateur ne le désarmerait plus jamais.
     */
    @Test
    void unJobEnEchec_doitIncrementerSonCompteur_quandLExceptionRemonte() {
        JobFictif job = proxifier(new JobFictif());

        assertThatThrownBy(job::tombe)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("échec attendu");

        assertThat(nombreDEchecs("JobFictif.tombe")).isEqualTo(1);
        // La durée est mesurée même sur l'échec : un job qui part en exception
        // au bout de quarante secondes reste un job lent.
        assertThat(nombreDExecutions("JobFictif.tombe")).isEqualTo(1);
    }

    /**
     * Le trou de l'aspect, et son bouchon.
     *
     * <p>Le job attrape son exception : pour l'aspect, ce tour est un succès, et
     * ce test l'énonce noir sur blanc (aucun échec vu par l'aspect). C'est
     * l'appel explicite du {@code catch} qui rend l'échec visible, sur la même
     * étiquette de job que l'aspect aurait employée.
     */
    @Test
    void echecAvale_doitIncrementerLeMemeCompteur_quandLeJobAttrapeSonException() {
        JobFictif nu = new JobFictif();
        JobFictif job = proxifier(nu);

        job.tombeEtSeTait();
        assertThat(nombreDEchecs("JobFictif.tombeEtSeTait"))
            .as("l'aspect ne peut PAS voir une exception que le job a attrapée")
            .isZero();

        aspect.echecAvale(nu, "tombeEtSeTait");

        assertThat(nombreDEchecs("JobFictif.tombeEtSeTait")).isEqualTo(1);
    }

    /**
     * Les deux chemins doivent tomber dans une seule série, sinon une alerte
     * posée sur {@code job.failure} rate la moitié des échecs du même job.
     */
    @Test
    void lesDeuxCheminsDEchec_doiventAlimenterUneSeuleSerie() {
        JobFictif nu = new JobFictif();
        JobFictif job = proxifier(nu);

        assertThatThrownBy(job::tombe).isInstanceOf(IllegalStateException.class);
        aspect.echecAvale(nu, "tombe");

        assertThat(nombreDEchecs("JobFictif.tombe")).isEqualTo(2);
        assertThat(registre.find(ScheduledJobMetricsAspect.COMPTEUR_ECHEC).counters())
            .as("une seule série pour ce job")
            .hasSize(1);
    }

    /** Une méthode sans {@code @Scheduled} n'a rien à faire dans ces métriques. */
    @Test
    void uneMethodeOrdinaire_neDoitPasEtreMesuree_quandElleNestPasPlanifiee() {
        JobFictif job = proxifier(new JobFictif());

        job.nEstPasUnJob();

        assertThat(registre.find(ScheduledJobMetricsAspect.TIMER_DUREE).timers()).isEmpty();
    }

    /**
     * L'étiquette ne doit pas porter le nom de la classe proxy : {@code
     * JobFictif$$SpringCGLIB$$0} changerait de suffixe d'un démarrage à l'autre
     * et dédoublerait la série à chaque déploiement.
     */
    @Test
    void nomDuJob_doitIgnorerLaClasseProxy_quandLeBeanEstProxifie() {
        JobFictif job = proxifier(new JobFictif());

        assertThat(job.getClass().getName()).contains("$$");
        assertThat(ScheduledJobMetricsAspect.nomDuJob(job.getClass(), "tourneBien"))
            .isEqualTo("JobFictif.tourneBien");
    }

    // ------------------------------------------------------------------ outils

    private long nombreDExecutions(String job) {
        var timer = registre.find(ScheduledJobMetricsAspect.TIMER_DUREE)
            .tag(ScheduledJobMetricsAspect.ETIQUETTE_JOB, job)
            .timer();
        return timer == null ? 0 : timer.count();
    }

    private double nombreDEchecs(String job) {
        var compteur = aspect.compteurDEchec(job);
        return compteur == null ? 0 : compteur.count();
    }

    private JobFictif proxifier(JobFictif cible) {
        AspectJProxyFactory usine = new AspectJProxyFactory(cible);
        usine.setProxyTargetClass(true);
        usine.addAspect(aspect);
        return usine.getProxy();
    }

    /**
     * Un job de test. {@code @Scheduled} n'y déclenche rien — aucun
     * planificateur ne tourne — mais c'est bien l'annotation que le pointcut
     * cherche, et c'est donc elle qu'il faut poser.
     */
    static class JobFictif {

        @Scheduled(fixedDelay = 3_600_000L)
        public void tourneBien() {
            // rien : ce qui est mesuré, c'est le passage
        }

        @Scheduled(fixedDelay = 3_600_000L)
        public void tombe() {
            throw new IllegalStateException("échec attendu");
        }

        /** Le patron des dix jobs réels : l'exception ne sort pas de la méthode. */
        @Scheduled(fixedDelay = 3_600_000L)
        public void tombeEtSeTait() {
            try {
                throw new IllegalStateException("échec avalé");
            } catch (RuntimeException ignoree) {
                // exactement ce que fait OutboxSweepJob.envoyer
            }
        }

        public void nEstPasUnJob() {
            // pas d'annotation : hors du pointcut
        }
    }
}
