package org.program.pair.domain.attendance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Recalcul unique des compteurs de pratique de tous les comptes (P-BL-16, étape 3).
 *
 * <p>Le dénominateur du signal de fiabilité a changé d'unité — des séances au lieu
 * de créneaux. Les compteurs existants restent faux jusqu'à la prochaine
 * confirmation de présence de chacun, qui peut ne jamais venir. Ce lanceur les
 * reconstruit tous, une fois, au démarrage où
 * {@code pair.fiabilite.recalcul-au-demarrage=true} est posé.
 *
 * <p><b>Idempotent</b> : {@link PracticeStatsService#recalculateFor} reconstruit
 * sans incrémenter. Relancer par erreur ne change rien. Chaque compte est sa
 * propre transaction : un échec n'emporte pas les autres, il est journalisé et
 * compté. Éteint par défaut — à allumer le temps d'un déploiement, puis retirer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "pair.fiabilite.recalcul-au-demarrage", havingValue = "true")
public class RecalculFiabiliteRunner implements ApplicationRunner {

    static final int TAILLE_DU_LOT = 200;

    private final UserRepository userRepository;
    private final PracticeStatsService practiceStatsService;

    @Override
    public void run(ApplicationArguments args) {
        Bilan bilan = recalculerTout();
        log.info("Recalcul de fiabilité : {} compte(s) recalculé(s), {} échec(s)",
            bilan.recalcules(), bilan.echecs());
    }

    Bilan recalculerTout() {
        int recalcules = 0;
        int echecs = 0;
        int page = 0;
        List<UUID> lot;
        do {
            lot = userRepository.findAllIds(PageRequest.of(page++, TAILLE_DU_LOT));
            for (UUID userId : lot) {
                try {
                    practiceStatsService.recalculateFor(userId);
                    recalcules++;
                } catch (RuntimeException e) {
                    echecs++;
                    log.warn("Recalcul de fiabilité en échec pour le compte {}", userId, e);
                }
            }
        } while (lot.size() == TAILLE_DU_LOT);
        return new Bilan(recalcules, echecs);
    }

    record Bilan(int recalcules, int echecs) {}
}
