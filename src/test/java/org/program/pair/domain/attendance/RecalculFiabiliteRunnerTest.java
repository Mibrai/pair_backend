package org.program.pair.domain.attendance;

import org.junit.jupiter.api.Test;
import org.program.pair.repository.UserRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** P-BL-16, étape 3 — le recalcul parcourt tous les comptes par lots, et un échec n'arrête rien. */
class RecalculFiabiliteRunnerTest {

    @Test
    void leRecalcul_parcourtTousLesLots_etSurvitAUnEchec() {
        UserRepository users = mock(UserRepository.class);
        PracticeStatsService stats = mock(PracticeStatsService.class);
        List<UUID> premierLot = IntStream.range(0, RecalculFiabiliteRunner.TAILLE_DU_LOT)
            .mapToObj(i -> UUID.randomUUID()).toList();
        List<UUID> dernierLot = List.of(UUID.randomUUID(), UUID.randomUUID());
        doReturn(premierLot).when(users).findAllIds(argThat((Pageable p) -> p.getPageNumber() == 0));
        doReturn(dernierLot).when(users).findAllIds(argThat((Pageable p) -> p.getPageNumber() == 1));
        doThrow(new IllegalStateException("compte illisible")).when(stats).recalculateFor(premierLot.get(3));

        RecalculFiabiliteRunner.Bilan bilan = new RecalculFiabiliteRunner(users, stats).recalculerTout();

        assertThat(bilan.recalcules()).isEqualTo(RecalculFiabiliteRunner.TAILLE_DU_LOT + 2 - 1);
        assertThat(bilan.echecs()).isEqualTo(1);
        verify(stats, times(RecalculFiabiliteRunner.TAILLE_DU_LOT + 2)).recalculateFor(any());
    }
}
