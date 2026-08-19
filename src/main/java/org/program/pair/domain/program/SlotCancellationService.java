package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.dto.CancelSlotRequest;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserProgramRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Annuler une séance, et le dire à tout le monde tout de suite.
 *
 * <p>Il existait déjà une suppression de créneau : elle basculait le statut et
 * prévenait les inscrits, sans garder trace de quoi que ce soit — ni motif, ni
 * date, ni auteur. Le participant recevait un fait brut, et rien ne permettait
 * plus tard de savoir qu'une séance avait été annulée trois heures avant.
 *
 * <p><b>Ce qui distingue une annulation du reste des notifications</b> : ne pas
 * la recevoir coûte un déplacement pour rien. C'est la raison pour laquelle
 * elle part aussi par e-mail, et la seule pour laquelle le double canal se
 * justifie ici alors qu'il serait envahissant partout ailleurs.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SlotCancellationService {

    /** Au-delà, on ne propose plus : ce n'est plus « à la place », c'est « plus tard ». */
    private static final int ALTERNATIVES_WINDOW_DAYS = 14;
    private static final int ALTERNATIVES_RADIUS_METERS = 25_000;
    private static final int MAX_ALTERNATIVES = 3;

    private final ScheduleRepository scheduleRepository;
    private final SlotParticipationRepository participationRepository;
    private final UserProgramRepository userProgramRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final HtmlSanitizer sanitizer;

    public void cancel(UUID userId, UUID scheduleId, CancelSlotRequest request) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        // 404 et non 403 : la suppression historique rend un 403, mais confirmer
        // l'existence d'un créneau qu'on n'organise pas n'a aucune raison d'être.
        if (!slot.getProgram().getUserActivity().getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Créneau introuvable.");
        }

        if (slot.getStatus() == SlotStatus.CANCELLED) {
            throw new ValidationException("Ce créneau est déjà annulé.");
        }

        String reason = request == null || request.reason() == null
            ? null
            : sanitizer.sanitize(request.reason()).strip();

        slot.setStatus(SlotStatus.CANCELLED);
        slot.setCancelledAt(Instant.now());
        slot.setCancelledBy(userRepository.getReferenceById(userId));
        slot.setCancellationReason(reason == null || reason.isBlank() ? null : reason);
        scheduleRepository.save(slot);

        notifyEveryone(slot, userId, reason);
    }

    /**
     * Tous ceux que cette séance concernait — <b>y compris la liste d'attente</b>.
     *
     * <p>Quelqu'un qui attendait une place a organisé sa journée autour de ce
     * créneau autant qu'un inscrit, et ne rien lui dire le laisserait attendre
     * une promotion qui n'arrivera jamais.
     */
    private void notifyEveryone(Schedule slot, UUID cancellerId, String reason) {
        Set<UUID> recipients = new LinkedHashSet<>();

        participationRepository.findByScheduleId(slot.getId()).stream()
            .filter(p -> p.getStatus() == ParticipationStatus.CONFIRMED
                || p.getStatus() == ParticipationStatus.INTERESTED
                || p.getStatus() == ParticipationStatus.WAITLISTED)
            .map(p -> p.getUser().getId())
            .forEach(recipients::add);

        userProgramRepository
            .findByProgramIdAndStatus(slot.getProgram().getId(), UserProgramStatus.ACTIVE).stream()
            .filter(up -> up.getSchedule() != null && up.getSchedule().getId().equals(slot.getId()))
            .map(up -> up.getUser().getId())
            .forEach(recipients::add);

        recipients.remove(cancellerId);
        if (recipients.isEmpty()) {
            return;
        }

        Map<String, Object> payload = payloadFor(slot, reason);
        for (UUID recipientId : recipients) {
            notificationService.notify(recipientId, cancellerId, NotificationType.SLOT_CANCELLED, payload);
        }
    }

    /**
     * La charge utile, avec de quoi rebondir.
     *
     * <p>Une annulation sans alternative laisse la personne devant rien. Le
     * payload porte donc le nombre de créneaux de la même activité qui ont lieu
     * à proximité dans les deux semaines — un nombre, pas la liste : il est
     * composé <b>une fois pour tous les destinataires</b>, et une liste de
     * créneaux détaillée y ferait voyager des adresses résolues pour personne en
     * particulier. Le client va les chercher lui-même, avec sa position à lui.
     *
     * <p>Les créneaux complets sont exclus : proposer un cul-de-sac à quelqu'un
     * qui vient de perdre sa place serait pire que ne rien proposer.
     */
    private Map<String, Object> payloadFor(Schedule slot, String reason) {
        NotificationPayload payload = NotificationPayload.ofSchedule(slot);

        if (reason != null && !reason.isBlank()) {
            payload = payload.with("cancellationReason", reason);
        }

        payload = payload.with("alternativesCount", countAlternatives(slot));
        return payload.build();
    }

    private int countAlternatives(Schedule slot) {
        if (slot.getLocation() == null) {
            // Un créneau en ligne n'a pas de voisinage : proposer « près de chez
            // vous » n'aurait aucun sens.
            return 0;
        }

        Instant from = Instant.now();
        Instant to = from.plus(ALTERNATIVES_WINDOW_DAYS, ChronoUnit.DAYS);
        UUID activityId = slot.getProgram().getUserActivity().getActivity().getId();

        List<Schedule> nearby = scheduleRepository.findOpenSlotsInRadius(
            slot.getLocation().getY(), slot.getLocation().getX(), ALTERNATIVES_RADIUS_METERS,
            from, to, activityId, false, ScheduleRepository.NO_CATEGORY_FILTER, null,
            MAX_ALTERNATIVES + 1,
            // Pas d'appelant : la charge utile est composée une fois pour tous,
            // et un filtre de blocage résolu pour l'un s'appliquerait aux autres.
            null,
            false, ScheduleRepository.NO_LANGUAGE_FILTER,
            false, ScheduleRepository.NO_TAG_FILTER, 0L);

        return (int) nearby.stream()
            .filter(candidate -> !candidate.getId().equals(slot.getId()))
            .filter(candidate -> candidate.getStatus() == SlotStatus.OPEN)
            .limit(MAX_ALTERNATIVES)
            .count();
    }
}
