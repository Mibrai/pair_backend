package org.program.pair.domain.publicslot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.repository.ScheduleRepository;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * P-BS-19 — un jeton de partage n'apparaît jamais en clair dans les journaux.
 *
 * <p>Le jeton est l'adresse de la page publique d'un créneau : l'écrire au journal
 * donnait à quiconque lit les journaux de quoi ouvrir des pages que personne ne
 * lui a partagées.
 */
@ExtendWith(OutputCaptureExtension.class)
class PublicSlotServiceTest {

    @Test
    void unComptagePerdu_neJournalisePasLeJetonEnClair(CapturedOutput sortie) {
        String jeton = "Qz7xW2pL9mN4vB8cR1tYk5";
        ScheduleRepository schedules = mock(ScheduleRepository.class);
        doThrow(new IllegalStateException("base indisponible"))
            .when(schedules).incrementPublicViewCount(anyString());

        new PublicSlotService(schedules, mock(SlotAudience.class))
            .countView(jeton, "Mozilla/5.0 (iPhone)");

        assertThat(sortie.getAll()).as("la perte est bien journalisée").contains("Comptage d'ouverture perdu");
        assertThat(sortie.getAll()).doesNotContain(jeton);
    }
}
