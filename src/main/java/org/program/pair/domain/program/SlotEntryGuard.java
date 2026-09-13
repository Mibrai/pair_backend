package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.block.BlockFilterService;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Qui peut entrer sur un créneau — la liste ordonnée des refus, écrite une fois.
 *
 * <p><b>Le défaut fermé ici.</b> Il y a deux portes d'entrée sur une même
 * séance : {@code POST /slots/{id}/join} ({@link SlotService#joinSlot}) et
 * {@code POST /programs/{id}/join} ({@link ProgramEnrollmentService#joinProgram}
 * avec un {@code scheduleId}). La première vérifiait le blocage, le créneau
 * annulé, la séance commencée et l'ouverture aux partenaires ; la seconde ne
 * vérifiait aucun des quatre — le mot « blocage » n'apparaissait pas une fois
 * dans son fichier. Une personne bloquée par l'organisateur entrait donc par la
 * porte programme sur le créneau même dont le blocage l'avait écartée, et une
 * séance annulée ou déjà commencée continuait d'accepter des inscriptions.
 *
 * <p>Le commentaire de {@code joinSlot} et l'OpenAPI de {@code SlotController}
 * affirmaient tous deux « même règle que l'autre porte » depuis des mois : la
 * règle était bien écrite deux fois, mais pas au même endroit, et l'une des deux
 * copies était vide. Deux définitions de « qui peut entrer » ne se manifestent
 * pas par une erreur — elles se manifestent par quelqu'un qui passe par l'autre
 * chemin. Même raison d'être que {@link SlotAddressVisibility},
 * {@link SlotAudience} et {@link SlotTiming}.
 *
 * <p><b>L'ordre est la règle, pas un détail.</b> Le blocage vient en tête : les
 * refus qui suivent nomment précisément ce qui cloche, et l'un d'eux rendu à une
 * personne bloquée lui apprendrait que le créneau existe, qu'il est ouvert, et
 * qu'il a de la place.
 */
@Component
@RequiredArgsConstructor
public class SlotEntryGuard {

    /**
     * Par quelle porte on entre. Une seule chose en dépend aujourd'hui —
     * {@code isOpenToPartners}, voir {@link #assertMayEnter} — et c'est
     * précisément pour que cette différence soit nommée quelque part plutôt que
     * de vivre dans l'absence d'un contrôle.
     */
    public enum Door {
        /** {@code POST /api/slots/{id}/join}. */
        SLOT,
        /** {@code POST /api/programs/{id}/join} avec un {@code scheduleId}. */
        PROGRAM
    }

    private final BlockFilterService blockFilterService;
    private final ScheduleRepository scheduleRepository;

    /**
     * Peut-on entrer sur ce créneau ? Lève le premier refus applicable.
     *
     * <p>Dans l'ordre : blocage (ses deux formes), son propre créneau,
     * l'ouverture aux partenaires (porte créneau seulement, voir plus bas), le
     * statut, la séance commencée. <b>La capacité n'en fait pas partie</b> :
     * elle est vérifiée par {@link #assertHasRoom}, plus tard, et la raison est
     * dans sa javadoc.
     *
     * <p><b>{@code isOpenToPartners} n'est vérifié que sur la porte créneau, et
     * c'est délibérément l'état actuel plutôt qu'une décision.</b> Le drapeau
     * sert peut-être à des inscriptions « programme » légitimes — un créneau
     * réservé aux inscrits du programme, fermé aux passants du fil. Durcir la
     * porte programme fermerait alors un usage voulu, et le laisser ouvert
     * laisse un contournement possible : les deux erreurs coûtent, et aucune ne
     * se répare par la lecture du code. La question est posée au produit
     * (P-BL-09) et le contrôle reste où il était en attendant.
     *
     * @param now l'instant de référence, passé plutôt que lu ici pour que la
     *            décision et la transaction qui l'entoure parlent du même moment
     */
    public void assertMayEnter(UUID userId, Schedule slot, Instant now, Door door) {
        User host = slot.getProgram().getUserActivity().getUser();

        assertNotBlocked(userId, host.getId());

        if (host.getId().equals(userId)) {
            throw new ValidationException(ErrorCode.SLOT_OWN_SLOT,
                "Vous ne pouvez pas rejoindre votre propre créneau.");
        }

        // TODO P-BL-09 : isOpenToPartners=false sert-il, en pratique, à des
        // inscriptions « programme » légitimes (créneau réservé aux inscrits du
        // programme) ? Tant que le produit n'a pas répondu, la porte programme
        // n'applique pas ce refus — elle ne l'appliquait pas davantage avant ce
        // lot. Si la réponse est « non », retirer la condition sur la porte ;
        // si elle est « oui », ce commentaire devient la règle et le TODO s'en
        // va.
        if (door == Door.SLOT && !Boolean.TRUE.equals(slot.getIsOpenToPartners())) {
            throw new ValidationException(ErrorCode.SLOT_NOT_OPEN_TO_PARTNERS,
                "Ce créneau n'est pas ouvert aux partenaires.");
        }

        // CANCELLED et PAST passent tous deux par ici, sous le même code : ce
        // n'est pas un créneau qui accepte encore du monde. Le client sait
        // lequel des deux par le champ status du créneau (P-BL-08), qu'il n'avait
        // pas avant ce lot.
        if (slot.getStatus() != SlotStatus.OPEN) {
            throw new ValidationException(ErrorCode.SLOT_NOT_ACCEPTING_PARTICIPANTS,
                "Ce créneau n'accepte plus de participants.");
        }

        assertNotStarted(slot, now);
    }

    /**
     * Peut-on se mettre en <b>liste d'attente</b> sur ce créneau ?
     *
     * <p>La même chaîne, à deux différences près, et les deux sont le sens même
     * de la file :
     *
     * <ul>
     *   <li>un créneau {@code FULL} est accepté — c'est exactement celui pour
     *       lequel la file existe, là où {@link #assertMayEnter} le refuse ;</li>
     *   <li>un créneau annulé rend <b>introuvable</b> plutôt que « n'accepte
     *       plus de participants » : entrer dans la file d'une séance annulée
     *       n'a aucun sens, et il n'y a rien à attendre d'un créneau qui
     *       n'aura pas lieu.</li>
     * </ul>
     *
     * <p>La condition « réellement complet » est, elle, dans
     * {@link #assertFull}, appelée plus tard pour la même raison que
     * {@link #assertHasRoom}.
     */
    public void assertMayWait(UUID userId, Schedule slot, Instant now) {
        User host = slot.getProgram().getUserActivity().getUser();

        assertNotBlocked(userId, host.getId());

        if (host.getId().equals(userId)) {
            throw new ValidationException(ErrorCode.SLOT_OWN_SLOT,
                "Vous ne pouvez pas vous mettre en attente de votre propre créneau.");
        }

        if (!Boolean.TRUE.equals(slot.getIsOpenToPartners())) {
            throw new ValidationException(ErrorCode.SLOT_NOT_OPEN_TO_PARTNERS,
                "Ce créneau n'est pas ouvert aux partenaires.");
        }

        if (slot.getStatus() == SlotStatus.CANCELLED) {
            throw new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable.");
        }

        assertNotStarted(slot, now);
    }

    /**
     * Le refus de blocage, sous ses deux formes — utilisable seul.
     *
     * <p>{@code POST /programs/{id}/join} <b>sans</b> {@code scheduleId} inscrit
     * à tout le programme : il n'y a pas de créneau à confronter, mais il y a un
     * organisateur, et le blocage doit valoir là aussi. C'est le seul appelant
     * qui a besoin de cette moitié-là séparément.
     *
     * <p>Deux formes, deux réponses opposées, et c'est voulu : celui qui a
     * bloqué reçoit un refus nommé — il sait pourquoi, c'est sa décision —, celui
     * qui est bloqué reçoit un 404, parce que le créneau a déjà disparu de son
     * fil et ne doit pas réapparaître par son identifiant.
     */
    public void assertNotBlocked(UUID userId, UUID hostId) {
        if (blockFilterService.blockedBy(userId, hostId)) {
            throw new ValidationException(ErrorCode.USER_BLOCKED,
                "Vous avez bloqué l'organisateur de ce créneau.");
        }
        if (blockFilterService.blocked(userId, hostId)) {
            throw new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable.");
        }
    }

    /**
     * Reste-t-il une place ? {@code SLOT_FULL} sinon.
     *
     * <p><b>Pourquoi cette vérification est hors de {@link #assertMayEnter}.</b>
     * Elle doit venir <i>après</i> les réponses « vous y êtes déjà » et « vous
     * êtes déjà en attente ». Un créneau à une place rejoint par une personne est
     * complet <b>à cause d'elle</b> : la placer en tête de chaîne ferait répondre
     * « ce créneau est complet » à celle qui l'occupe, au lieu de lui dire
     * qu'elle y est inscrite. Les deux portes l'appellent donc à l'endroit où
     * elles connaissent déjà l'état de l'inscription de l'appelant.
     *
     * <p>Le compte est celui de {@code countConfirmedParticipants} — les deux
     * sources d'inscription confondues —, jamais {@code participantCount} qui est
     * un cache.
     */
    public void assertHasRoom(Schedule slot) {
        if (isFull(slot)) {
            throw new ValidationException(ErrorCode.SLOT_FULL, "Ce créneau est complet.");
        }
    }

    /**
     * Le créneau est-il <b>réellement</b> complet ? {@code SLOT_NOT_FULL} sinon.
     *
     * <p>Le miroir d'{@link #assertHasRoom}, pour la file d'attente : on n'entre
     * en file que sur un créneau qui n'a plus de place. {@code joinWaitlist}
     * n'en vérifiait rien — on pouvait attendre derrière un créneau vide, et
     * rester derrière une place libre jusqu'à ce qu'un désistement déclenche une
     * promotion qui n'avait jamais eu lieu d'être attendue.
     *
     * <p>Ce n'est pas une impasse mais un renvoi : le message dit de rejoindre
     * directement, et c'est ce que le client doit faire du code.
     *
     * <p>Un créneau sans {@code maxParticipants} n'est jamais complet : sa file
     * n'a donc pas d'objet, et ce refus est la bonne réponse.
     */
    public void assertFull(Schedule slot) {
        if (!isFull(slot)) {
            throw new ValidationException(ErrorCode.SLOT_NOT_FULL,
                "Ce créneau n'est pas complet : rejoignez-le directement.");
        }
    }

    private boolean isFull(Schedule slot) {
        return slot.getMaxParticipants() != null
            && scheduleRepository.countConfirmedParticipants(slot.getId()) >= slot.getMaxParticipants();
    }

    /**
     * La frontière du « trop tard » est le <b>début</b> de la séance, et pas sa
     * fin — contrairement à « mes créneaux à venir », qui se mesure sur la fin
     * (voir {@link SlotService#getMySlots}). Les deux sont justes : on peut avoir
     * besoin de l'adresse d'une séance commencée, on ne s'y inscrit plus.
     */
    private static void assertNotStarted(Schedule slot, Instant now) {
        if (slot.getStartsAt().isBefore(now)) {
            throw new ValidationException(ErrorCode.SLOT_ALREADY_STARTED, "Ce créneau est déjà passé.");
        }
    }
}
