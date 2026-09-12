package org.program.pair.shared.observabilite;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Mesure chaque méthode {@code @Scheduled} : combien de temps elle a pris, et
 * combien de fois elle a échoué.
 *
 * <p><b>Ce qu'on ne savait pas avant.</b> Dix jobs tournent dans cette
 * application — outbox toutes les dix secondes, rappels de programme, rollover
 * des créneaux récurrents, purge RGPD, relance de cycle, boucle de retour de
 * veille. Aucun ne rendait compte de quoi que ce soit ailleurs que dans une
 * ligne de journal, et une ligne de journal ne déclenche pas d'alerte : un job
 * qui a cessé de tourner, ou qui échoue à chaque tour depuis trois jours,
 * ressemblait exactement à un job qui n'a rien à faire. C'est la fiche d'audit
 * P-BA-21, étape 4.
 *
 * <p><b>Deux mesures, et pourquoi il en faut deux.</b> Le {@code Timer}
 * {@code job.duration} porte le compte des exécutions autant que leur durée :
 * son {@code _count} qui n'avance plus dit « ce job ne tourne plus », ce qu'aucun
 * compteur d'échec ne dirait. Le {@code Counter} {@code job.failure} dit
 * l'inverse : il tourne, et il se casse.
 *
 * <p><b>Le trou que l'aspect ne peut pas boucher seul, et c'est le point
 * important.</b> Les jobs de ce projet <b>avalent</b> leurs exceptions : chacun
 * enveloppe son corps dans un {@code try/catch (Exception e) { log.error(…); }}
 * — voir {@code OutboxSweepJob.envoyer} ou {@code GdprPurgeJob.purgeInactiveAccounts}.
 * Du point de vue de l'aspect, un tel tour est un <b>succès</b> : la méthode
 * rend la main normalement, rien ne remonte jusqu'ici. Un aspect posé seul
 * mesurerait donc dix jobs dont aucun n'échoue jamais, ce qui est pire que pas
 * de mesure du tout — c'est une mesure qui rassure.
 *
 * <p>D'où {@link #echecAvale(Object, String)} : le {@code catch} d'un job
 * l'appelle pour compter l'échec que l'aspect ne verra pas. Les deux chemins
 * alimentent le <b>même</b> compteur, sous le même nom de job, et ne peuvent pas
 * double-compter — soit l'exception remonte et l'aspect la voit, soit le job
 * l'attrape et l'annonce.
 *
 * <p><b>Nommage.</b> {@code job} vaut {@code ClasseSimple.methode}, pris sur le
 * type <b>déclarant</b> et non sur {@code getTarget().getClass()} : les beans de
 * job sont proxifiés par CGLIB dès que cet aspect existe, et le nom de la classe
 * proxy porte un suffixe illisible qui changerait d'un démarrage à l'autre. Une
 * étiquette de métrique doit être stable, sinon les séries se dédoublent à chaque
 * déploiement.
 *
 * <p>Aucune donnée personnelle ne peut entrer dans ces étiquettes : elles sont
 * dérivées de noms de classes et de méthodes, jamais d'un argument (P-BS-19).
 *
 * <p><b>{@code HIGHEST_PRECEDENCE}, et ce n'est pas cosmétique.</b> Sans ordre
 * explicite, cet aspect serait le plus interne des conseils et se placerait donc
 * <i>à l'intérieur</i> de celui de {@code @Transactional}, qui garde l'ordre par
 * défaut. Deux conséquences : la durée mesurée exclurait le commit — soit,
 * derrière un proxy vers San Francisco, une part non négligeable du temps
 * réel — et une exception levée <b>au</b> commit ne serait pas comptée comme un
 * échec du job, alors que c'est précisément le genre d'échec qu'on cherche.
 * Placé à l'extérieur, l'aspect mesure le tour complet, transaction comprise.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScheduledJobMetricsAspect {

    /** Durée et nombre d'exécutions, par job. */
    public static final String TIMER_DUREE = "job.duration";

    /** Échecs, par job — qu'ils remontent ou que le job les attrape. */
    public static final String COMPTEUR_ECHEC = "job.failure";

    /** L'unique étiquette : {@code ClasseSimple.methode}. */
    public static final String ETIQUETTE_JOB = "job";

    private final MeterRegistry registre;

    public ScheduledJobMetricsAspect(MeterRegistry registre) {
        this.registre = registre;
    }

    /**
     * Autour de toute méthode portant {@code @Scheduled}.
     *
     * <p>La durée est enregistrée dans les deux cas, succès comme échec : une
     * exécution qui part en exception au bout de quarante secondes est une
     * information sur le job, et l'écarter du timer laisserait croire que le job
     * est rapide.
     */
    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object mesurer(ProceedingJoinPoint point) throws Throwable {
        String job = nomDuJob(point);
        Timer.Sample mesure = Timer.start(registre);
        try {
            return point.proceed();
        } catch (Throwable echec) {
            registre.counter(COMPTEUR_ECHEC, ETIQUETTE_JOB, job).increment();
            throw echec;
        } finally {
            mesure.stop(registre.timer(TIMER_DUREE, ETIQUETTE_JOB, job));
        }
    }

    /**
     * À appeler depuis le {@code catch} d'un job qui avale son exception.
     *
     * <p>Sans cet appel, l'échec est invisible pour la métrique — voir la
     * javadoc de la classe. Le job garde son {@code log.error}, qui sert au
     * diagnostic ; ceci sert à l'alerte.
     *
     * <p>Usage, depuis le job lui-même :
     * <pre>{@code
     * } catch (Exception e) {
     *     log.error("Balayage de l'outbox en échec", e);
     *     metriques.echecAvale(this, "envoyer");
     * }
     * }</pre>
     *
     * @param job      le bean de job ; son type déclarant donne l'étiquette
     * @param methode  le nom de la méthode {@code @Scheduled} qui a échoué,
     *                 celui-là même que l'aspect emploie
     */
    public void echecAvale(Object job, String methode) {
        registre.counter(COMPTEUR_ECHEC, ETIQUETTE_JOB, nomDuJob(job.getClass(), methode))
            .increment();
    }

    /**
     * Le même nom que l'aspect pose, calculable sans lui.
     *
     * <p>{@code getUserClass} défait le proxy CGLIB : sans lui, un job appelant
     * {@link #echecAvale} avec {@code this} depuis un bean proxifié produirait
     * une étiquette différente de celle de l'aspect, et les deux chemins
     * d'échec du même job compteraient dans deux séries distinctes.
     */
    public static String nomDuJob(Class<?> classeDuJob, String methode) {
        return org.springframework.util.ClassUtils.getUserClass(classeDuJob).getSimpleName()
            + "." + methode;
    }

    private static String nomDuJob(ProceedingJoinPoint point) {
        MethodSignature signature = (MethodSignature) point.getSignature();
        return nomDuJob(signature.getDeclaringType(), signature.getName());
    }

    /**
     * Rendu visible pour les tests : le compteur d'un job, ou {@code null} s'il
     * n'a encore jamais échoué — Micrometer ne crée un compteur qu'au premier
     * incrément.
     */
    Counter compteurDEchec(String job) {
        return registre.find(COMPTEUR_ECHEC).tag(ETIQUETTE_JOB, job).counter();
    }
}
