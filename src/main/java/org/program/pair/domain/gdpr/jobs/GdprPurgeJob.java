package org.program.pair.domain.gdpr.jobs;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.program.pair.shared.observabilite.ScheduledJobMetricsAspect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.audit.AuditLogService;
import org.program.pair.domain.gdpr.GdprService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Les deux purges de rétention (RGPD articles 17 et 5.1.e).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GdprPurgeJob {

    private final GdprService gdprService;
    private final ScheduledJobMetricsAspect metriques;
    private final AuditLogService auditLogService;

    /**
     * L'interrupteur d'exploitation, posé d'avance dans
     * {@code application.properties} — ce lot ne fait que le lire.
     *
     * <p>Il est lu <b>dans</b> la méthode planifiée et non porté par
     * {@code @ConditionalOnProperty} sur le bean : la seconde forme retirerait
     * aussi la purge des journaux d'audit, qui n'a rien à voir, et rendrait
     * l'extinction invisible — un job absent ne dit pas qu'il est absent.
     *
     * <p>Le repli est {@code true} pour qu'un environnement qui ne connaît pas la
     * propriété se comporte comme la configuration commune. L'extinction est donc
     * toujours un geste explicite, ce qui est le bon sens de lecture pour une
     * obligation légale : on n'oublie pas d'effacer par défaut.
     */
    @Value("${pair.gdpr.purge.enabled:true}")
    private boolean purgeActivee;

    /**
     * Efface chaque nuit les comptes dont la suppression a été demandée il y a
     * plus de trente jours.
     *
     * <p><b>Ce que ce journal disait de faux.</b> Il annonçait « {@code N}
     * accounts purged » avec le nombre de <i>candidats</i>, et attrapait
     * l'{@code UnexpectedRollbackException} du commit final en {@code log.error}
     * sans rien dire du nombre de comptes concernés. Une nuit entièrement annulée
     * se lisait donc comme une ligne d'erreur isolée, et la ligne de succès qui la
     * précédait donnait un nombre qui n'avait aucun rapport avec la réalité. Les
     * deux nombres sont maintenant distincts et viennent de la boucle elle-même.
     *
     * <p>Le {@code try/catch} reste : il protège l'ordonnanceur, pas les comptes.
     * Les échecs par compte sont attrapés une couche plus bas, où l'on sait de
     * quel compte il s'agit. Ce qui remonte ici est donc un échec de la
     * <i>sélection</i> — base injoignable, par exemple — et il est compté par
     * {@code ScheduledJobMetricsAspect} sans que ce code n'ait rien à faire, la
     * méthode portant {@code @Scheduled}.
     *
     * <p><b>Aucun verrou distribué</b> : deux instances qui tourneraient à trois
     * heures effaceraient les mêmes comptes, l'une des deux échouant sur des
     * lignes déjà parties. C'est P-BA-04, et ce n'est pas fermé ici. Le service ne
     * tourne aujourd'hui qu'en une instance ; passer à deux avant P-BA-04 demande
     * d'éteindre ce job par la propriété.
     */
    @SchedulerLock(name = "gdpr-purge-accounts", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeInactiveAccounts() {
        if (!purgeActivee) {
            log.warn("Purge RGPD éteinte par pair.gdpr.purge.enabled — "
                + "les demandes de suppression s'accumulent, voir gdpr.purge.pending.oldest.days");
            return;
        }

        log.info("Purge RGPD : début");
        try {
            GdprService.ResultatDePurge resultat = gdprService.purgeInactiveAccounts();
            log.info("Purge RGPD : {} effacé(s), {} échec(s) sur {} candidat(s)",
                resultat.effaces(), resultat.echecs(), resultat.candidats());
        } catch (Exception echec) {
            metriques.echecAvale(this, "purgeInactiveAccounts");
            log.error("Purge RGPD : la sélection des comptes a échoué, aucun compte traité", echec);
        }
    }

    /**
     * Purge old audit logs monthly (retention: 2 years)
     * GDPR Article 5.1.e: Storage limitation
     */
    @SchedulerLock(name = "gdpr-purge-audit-logs", lockAtMostFor = "PT30M")
    @Scheduled(cron = "0 0 4 1 * *") // First day of month at 4 AM
    public void purgeOldAuditLogs() {
        log.info("Starting audit log purge job");

        try {
            auditLogService.purgeOldLogs();
            log.info("Audit log purge job completed");
        } catch (Exception e) {
            metriques.echecAvale(this, "purgeOldAuditLogs");
            log.error("Audit log purge job failed", e);
        }
    }
}
