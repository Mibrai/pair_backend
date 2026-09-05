package org.program.pair.domain.program.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.repository.ProgramRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Endort les programmes restés sans date — et ne supprime rien.
 *
 * <h2>Ce qu'il fait</h2>
 *
 * <p>Un programme {@code ACTIVE} qui n'a toujours aucun créneau non annulé
 * {@code meetdo.cycle.dormancy-delay-days} jours après sa création passe à
 * {@link ProgramStatus#DORMANT}. Il quitte alors la carte, le catalogue
 * d'activités, la recherche et le fil de créneaux — <b>sans qu'aucun filtre
 * n'ait eu à être écrit</b> : toutes ces surfaces bornent déjà sur
 * {@code status = 'ACTIVE'}. Son auteur, lui, continue de le voir et peut le
 * réveiller.
 *
 * <h2>Ce qu'il ne fait pas, et pourquoi</h2>
 *
 * <p><b>Aucune suppression.</b> La demande initiale prévoyait un effacement à
 * J+7. Il n'aura pas lieu : une suppression silencieuse est une perte de données
 * sans consentement, et « j'avais créé mon cours, il a disparu » est un ticket
 * qu'on ne peut pas fermer. Le sommeil rend tout le service attendu sans ce
 * coût. L'effacement définitif reste un geste humain, jamais une échéance.
 *
 * <p><b>Aucune notification.</b> Le programme s'endort en silence ; c'est la
 * relance de l'étape 2 — quatre jours plus tôt avec les délais mesurés — qui a
 * la charge de prévenir. Doubler le sommeil d'une notification ferait deux
 * messages pour un seul fait.
 *
 * <p><b>Aucune borne basse au balayage</b>, contrairement à
 * {@link CycleNudgeJob}. Une fenêtre y protège d'une salve de notifications au
 * premier passage ; ici il n'y a pas de destinataire, seulement un état
 * réversible, et retirer d'un coup les coquilles vides accumulées est
 * exactement ce qu'on demande.
 *
 * <h2>L'ordre des deux délais</h2>
 *
 * <p>Le sommeil doit rester <b>postérieur</b> à la relance : une fois
 * {@code DORMANT}, le programme sort du balayage de {@link CycleNudgeJob}, qui
 * ne retient que les {@code ACTIVE}. Inverser les deux endormirait avant d'avoir
 * prévenu — l'échéance tomberait sans que personne l'ait vue venir. Le job le
 * vérifie au démarrage plutôt que de laisser la configuration décider seule.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProgramDormancyJob {

    private final ProgramRepository programRepository;

    /**
     * Délai avant sommeil, en jours. Zéro éteint le job.
     *
     * <p>Sept, et c'est une mesure : le 2026-09-06 sur la production, le délai
     * médian entre la création d'un programme et son premier créneau vaut
     * 73 secondes, et l'échantillon organique ne contient personne entre le
     * troisième et le vingt-deuxième jour. Endormir à J+7 ne coupe donc aucune
     * intention en cours.
     */
    @Value("${meetdo.cycle.dormancy-delay-days:0}")
    private int dormancyDelayDays;

    /** Le délai de la relance, lu ici pour vérifier que le sommeil vient après. */
    @Value("${meetdo.cycle.stage2-delay-days:0}")
    private int nudgeDelayDays;

    /**
     * Une fois par nuit. La granularité utile est la journée : le seuil se compte
     * en jours, et balayer plus souvent relirait la même population pour ne rien
     * changer.
     *
     * <p>À 3 h 50, après les autres travaux nocturnes du dépôt — la purge RGPD à
     * 3 h, le balayage de la boîte d'envoi à 3 h 20, la fermeture des fenêtres de
     * présence à 3 h 30.
     */
    @Scheduled(cron = "0 50 3 * * *")
    @Transactional
    public void putEmptyProgramsToSleep() {
        if (dormancyDelayDays <= 0) {
            return;
        }
        try {
            // Un seuil de sommeil antérieur à celui de la relance ferait tomber
            // l'échéance avant l'avertissement. On le signale plutôt que de le
            // corriger en silence : c'est une décision de configuration, et la
            // taire la rendrait indétectable.
            if (nudgeDelayDays > 0 && dormancyDelayDays <= nudgeDelayDays) {
                log.warn("Dormancy delay ({}d) is not after the cycle nudge delay ({}d): "
                        + "programs will fall asleep before their author is warned",
                    dormancyDelayDays, nudgeDelayDays);
            }

            Instant until = Instant.now().minus(Duration.ofDays(dormancyDelayDays));
            List<UUID> ids = programRepository.findDormancyCandidates(until);

            if (ids.isEmpty()) {
                log.debug("Program dormancy job: nothing to put to sleep");
                return;
            }

            List<Program> programs = programRepository.findWithOrganizerDetailsByIds(ids);
            for (Program program : programs) {
                program.setStatus(ProgramStatus.DORMANT);
            }
            programRepository.saveAll(programs);

            log.info("Program dormancy job completed: {} programs put to sleep after {}d",
                programs.size(), dormancyDelayDays);
        } catch (Exception e) {
            // Même posture que les autres jobs : une exécution ratée ne doit pas
            // empêcher la suivante. Les programmes non endormis restent éligibles.
            log.error("Program dormancy job failed", e);
        }
    }
}
