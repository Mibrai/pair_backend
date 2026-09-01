package org.program.pair.domain.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.repository.OutboxMessageRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Vide l'outbox : envoie ce qui attend, et efface ce qui est parti depuis assez
 * longtemps.
 *
 * <p><b>Rythme serré pour l'envoi.</b> Toutes les dix secondes : un message
 * d'alerte livré avec vingt minutes de retard ne vaut à peu près rien, et
 * l'engagement de remise du §7.2 se compte en dizaines de secondes. C'est le
 * balayage qui borne le délai entre le dépôt d'une alerte et sa remise au
 * fournisseur.
 *
 * <p><b>Purge quotidienne du corps.</b> Un message porte un nom, un lieu, une
 * heure ; une fois parti, il n'a pas à s'attarder. La purge efface les messages
 * remis il y a plus de sept jours — assez pour recouper un accusé de remise
 * tardif, pas au-delà. C'est la même règle que le reste du module : tout expire
 * par défaut.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxSweepJob {

    /** Rétention du corps d'un message parti, avant purge. */
    private static final int RETENTION_JOURS = 7;

    private final OutboxService outboxService;
    private final OutboxMessageRepository repository;

    @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
    public void envoyer() {
        try {
            int envoyes = outboxService.dispatchPending();
            if (envoyes > 0) {
                log.debug("Outbox : {} message(s) remis au fournisseur", envoyes);
            }
        } catch (Exception e) {
            log.error("Balayage de l'outbox en échec", e);
        }
    }

    @Scheduled(cron = "0 20 3 * * *") // chaque nuit, à 3h20
    @Transactional
    public void purger() {
        try {
            int effaces = repository.deleteBySentAtBefore(
                Instant.now().minus(RETENTION_JOURS, ChronoUnit.DAYS));
            if (effaces > 0) {
                log.info("Outbox : {} message(s) parti(s) purgé(s)", effaces);
            }
        } catch (Exception e) {
            log.error("Purge de l'outbox en échec", e);
        }
    }
}
