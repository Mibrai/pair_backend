package org.program.pair.domain.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.shared.observabilite.ScheduledJobMetricsAspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * P-BA-21 — un job qui avale son exception la compte quand même.
 *
 * <p>L'aspect ne voit que ce qui sort de la méthode ; les jobs attrapent tout
 * pour que l'exécution suivante parte. Sans l'appel dans le {@code catch}, une
 * outbox en panne permanente ne faisait monter aucun compteur.
 */
class OutboxSweepJobEchecTest {

    @Test
    void unBalayageEnEchec_incrementeJobFailure_bienQuAvale() {
        SimpleMeterRegistry registre = new SimpleMeterRegistry();
        OutboxService service = mock(OutboxService.class);
        doThrow(new IllegalStateException("base indisponible")).when(service).dispatchPending();
        OutboxSweepJob job = new OutboxSweepJob(service, new ScheduledJobMetricsAspect(registre),
            mock(OutboxMessageRepository.class));

        job.envoyer();

        assertThat(registre.counter("job.failure", "job", "OutboxSweepJob.envoyer").count()).isEqualTo(1.0);
    }
}
