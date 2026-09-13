package org.program.pair.domain.gdpr;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ce que la purge RGPD laisse voir d'elle-même à l'exploitation (P-BA-21).
 *
 * <p><b>Deux séries, et il faut les deux.</b> Un compteur d'échecs seul ne dit
 * rien d'un job qui ne tourne plus : un job éteint n'échoue jamais, et sa série
 * reste à zéro, c'est-à-dire rassurante. C'est la jauge d'arriéré qui fait la
 * différence — elle monte d'un jour par jour tant que la plus vieille demande
 * n'est pas exécutée, que la cause soit un échec, un drapeau resté à
 * {@code false}, ou un ordonnanceur qui ne se déclenche plus.
 *
 * <p><b>Le compteur est enregistré au démarrage, avant tout échec.</b> Sans cela
 * sa série n'existe pas tant qu'il n'a pas été incrémenté, et « aucune série » ne
 * se distingue pas de « série à zéro » : on ne peut donc pas écrire d'alerte sur
 * son absence. C'est la même raison qui vaut pour {@code outbox.claimed}.
 *
 * <p><b>Pas de préfixe {@code meetdo.}</b>, contrairement à ce qu'écrivait la
 * fiche d'audit : le dépôt nomme ses métriques sans préfixe de produit
 * ({@code job.duration}, {@code job.failure}, {@code outbox.sent}). Une série
 * seule sous un préfixe que rien d'autre ne porte se perd dans un tableau de
 * bord.
 */
@Component
@Slf4j
public class MetriquesPurgeRgpd {

    /** Comptes que la purge n'a pas réussi à effacer, un par échec. */
    public static final String COMPTEUR_ECHECS = "gdpr.purge.failures";

    /** Comptes effacés, cumulés. */
    public static final String COMPTEUR_EFFACES = "gdpr.purge.erased";

    /**
     * Âge, en jours, de la plus vieille demande de suppression non exécutée.
     *
     * <p>Doit rester sous le délai de rétention plus un jour. Au-delà, la purge
     * ne fait plus son travail, quelle que soit la raison.
     */
    public static final String JAUGE_ARRIERE = "gdpr.purge.pending.oldest.days";

    /**
     * Une jauge est évaluée une fois par registre enfant du composite à chaque
     * collecte. Cette fenêtre évite d'émettre autant de requêtes que de registres
     * pour une seule collecte, sans jamais rendre une valeur périmée à l'échelle
     * de ce que la jauge mesure — des jours.
     */
    private static final Duration FRAICHEUR = Duration.ofSeconds(30);

    private final UserRepository userRepository;
    private final Counter echecs;
    private final Counter effaces;

    private final AtomicLong derniereLecture = new AtomicLong(Long.MIN_VALUE);
    private volatile double arriereEnCache = Double.NaN;

    public MetriquesPurgeRgpd(MeterRegistry registre, UserRepository userRepository) {
        this.userRepository = userRepository;

        this.echecs = Counter.builder(COMPTEUR_ECHECS)
            .description("Comptes que la purge RGPD n'a pas réussi à effacer")
            .register(registre);
        this.effaces = Counter.builder(COMPTEUR_EFFACES)
            .description("Comptes effacés par la purge RGPD")
            .register(registre);

        Gauge.builder(JAUGE_ARRIERE, this, MetriquesPurgeRgpd::arriereEnJours)
            .description("Âge de la plus vieille demande de suppression non exécutée "
                + "— doit rester sous le délai de rétention, sinon la purge ne tourne plus")
            .baseUnit("days")
            .strongReference(true)
            .register(registre);
    }

    /** Un compte de plus effacé. */
    public void compteEfface() {
        effaces.increment();
    }

    /** Un compte de plus que la purge a laissé derrière elle. */
    public void compteEnEchec() {
        echecs.increment();
    }

    /**
     * L'arriéré, relu au plus une fois par {@link #FRAICHEUR}.
     *
     * <p>Rend {@code NaN} — et non zéro — quand la lecture échoue ou quand la
     * file est vide de <i>date connue</i>. Zéro voudrait dire « la plus vieille
     * demande date d'aujourd'hui », ce qui est une affirmation ; l'absence de
     * valeur n'en est pas une.
     */
    double arriereEnJours() {
        long maintenant = System.nanoTime();
        long precedente = derniereLecture.get();
        if (precedente != Long.MIN_VALUE && maintenant - precedente < FRAICHEUR.toNanos()) {
            return arriereEnCache;
        }

        try {
            Instant plusAncienne = userRepository.findPlusAncienneDemandeNonPurgee();
            arriereEnCache = plusAncienne == null
                ? Double.NaN
                : Duration.between(plusAncienne, Instant.now()).toMillis() / 86_400_000.0;
        } catch (RuntimeException echec) {
            // Une collecte ne doit pas remplir les journaux à chaque passage :
            // l'absence de la série se voit là où elle se regarde.
            log.debug("Lecture de l'arriéré de purge RGPD impossible", echec);
            arriereEnCache = Double.NaN;
        }
        derniereLecture.set(maintenant);
        return arriereEnCache;
    }
}
