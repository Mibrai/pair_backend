package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.repository.ScheduleRepository;
import org.springframework.stereotype.Component;

/**
 * Le seul endroit qui écrit {@code schedules.participant_count}.
 *
 * <p><b>Pourquoi un composant plutôt que trois lignes recopiées.</b> Le compteur
 * est une valeur dénormalisée : la vérité est {@code countConfirmedParticipants},
 * qui agrège les deux sources de participation — {@code slot_participations}
 * confirmées et {@code user_programs} actives rattachées au créneau. Tant que
 * chaque chemin d'écriture recopiait son propre recalcul, il suffisait qu'un
 * chemin l'oublie pour que la colonne mente ; et deux l'avaient oublié
 * ({@code joinProgram}, {@code leaveProgram}), un troisième la remettait
 * franchement à zéro sans toucher aux inscrits (le rollover récurrent). Le relevé
 * de production du 01/09 est né de là : un créneau rendait
 * {@code participantCount: 0} pendant que {@code /participants} listait un
 * inscrit confirmé.
 *
 * <p>Ce n'est pas un détail d'affichage. Trois écrans décident sur ce chiffre —
 * dont le filtre « masquer les créneaux complets », qui compare le compteur à
 * {@code maxParticipants} : figé à zéro, il laissait s'inscrire au-delà du
 * plafond que l'organisateur avait lui-même posé.
 *
 * <p><b>Le statut suit le compteur, et seulement entre {@code OPEN} et
 * {@code FULL}.</b> {@code CANCELLED} et {@code PAST} disent quelque chose que le
 * nombre de places ne sait pas : un créneau annulé qui se viderait ne redeviendrait
 * pas ouvert. On ne touche donc qu'aux deux états que la capacité gouverne.
 */
@Component
@RequiredArgsConstructor
public class ParticipantCounter {

    private final ScheduleRepository scheduleRepository;

    /**
     * Relit le nombre de places prises et le réécrit sur le créneau, avec le
     * statut qui en découle. À appeler après toute écriture qui crée, confirme,
     * retire ou déplace une participation.
     *
     * <p>Ne sauvegarde pas : l'entité est gérée par la transaction appelante, qui
     * a le plus souvent posé un verrou pessimiste sur la ligne et doit rester la
     * seule à décider quand elle écrit.
     *
     * @return le nombre de places prises après recalcul.
     */
    public long refresh(Schedule slot) {
        long confirmed = scheduleRepository.countConfirmedParticipants(slot.getId());
        slot.setParticipantCount((int) confirmed);

        boolean complet = slot.getMaxParticipants() != null
            && confirmed >= slot.getMaxParticipants();

        if (complet && slot.getStatus() == SlotStatus.OPEN) {
            slot.setStatus(SlotStatus.FULL);
        } else if (!complet && slot.getStatus() == SlotStatus.FULL) {
            slot.setStatus(SlotStatus.OPEN);
        }
        return confirmed;
    }
}
