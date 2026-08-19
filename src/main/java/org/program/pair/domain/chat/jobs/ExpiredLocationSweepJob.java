package org.program.pair.domain.chat.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.repository.MessageRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Efface les positions partagées dont l'échéance est passée.
 *
 * <p><b>Ce balayage n'est pas ce qui fait expirer un partage.</b> C'est la
 * lecture qui décide : {@code toMessageDto} ne sert jamais un point échu, y
 * compris dans la fenêtre qui sépare l'échéance du passage suivant. Supprimer ce
 * job ne rendrait donc aucune position à personne.
 *
 * <p>Ce qu'il fait est autre chose, et compte tout autant : sans lui, la base
 * accumulerait indéfiniment les positions passées de chacun. Un partage
 * « ponctuel » dont la trace reste en base est un historique de déplacements,
 * consultable par quiconque a accès à la base ou à une sauvegarde, et lisible par
 * n'importe quelle requête écrite plus tard sans connaître cette règle. Le
 * garde-fou n°4 ne serait alors vrai que du côté de l'API.
 *
 * <p>Le message, lui, reste dans le fil : il dit qu'une position a été partagée à
 * cet instant, ce qui est vrai et que personne n'a demandé à effacer. Seules les
 * coordonnées s'en vont.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpiredLocationSweepJob {

    private final MessageRepository messageRepository;

    /**
     * Toutes les dix minutes.
     *
     * <p>Le rythme n'a pas à suivre l'échéance à la seconde, la lecture s'en
     * chargeant déjà. Il borne seulement la durée pendant laquelle une
     * coordonnée échue traîne en base — au plus dix minutes de plus que les
     * trente autorisées.
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000, initialDelay = 60 * 1000)
    @Transactional
    public void sweep() {
        int erased = messageRepository.eraseExpiredLocations(Instant.now());
        if (erased > 0) {
            log.debug("Positions partagées échues effacées : {}", erased);
        }
    }
}
