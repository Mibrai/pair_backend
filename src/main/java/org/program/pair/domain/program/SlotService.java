package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.chat.ChatService;
import org.program.pair.domain.chat.dto.CreateConversationRequest;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.program.dto.ScheduleConflictDto;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.program.dto.SlotFeedRequest;
import org.program.pair.domain.program.dto.SlotParticipantDto;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.UserService;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.GeoUtils;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ScheduleConflictException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Rejoindre un créneau ouvert — le coeur du produit meetDo. Distinct de
 * ProgramEnrollmentService (inscription à un programme structuré multi-semaines) :
 * ici on parle d'un RSVP léger sur UNE occurrence précise. Les deux mécanismes
 * partagent la même capacité (Schedule.maxParticipants), voir
 * ScheduleRepository.countConfirmedParticipants / lockById.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SlotService {

    private final ScheduleRepository scheduleRepository;
    private final SlotParticipationRepository participationRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ChatService chatService;
    private final NotificationService notificationService;
    private final ScheduleConflictDetector conflictDetector;
    private final HtmlSanitizer sanitizer;

    @Transactional(readOnly = true)
    public List<SlotFeedItemDto> getSlotFeed(SlotFeedRequest request, UUID requesterId) {
        Instant from = request.from() != null ? request.from() : Instant.now();
        Instant to = request.to() != null ? request.to() : Instant.now().plus(7, ChronoUnit.DAYS);

        // Hibernate ne sait pas lier une liste vide dans un IN : quand aucune
        // catégorie n'est demandée, on passe le drapeau à faux et une liste
        // factice non vide, que la requête ne regarde alors pas.
        Set<UUID> categoryIds = request.effectiveCategoryIds();
        boolean filterByCategory = !categoryIds.isEmpty();

        List<Schedule> slots = scheduleRepository.findOpenSlotsInRadius(
            request.lat(), request.lng(), request.radiusMeters(),
            from, to, request.activityId(),
            filterByCategory, filterByCategory ? categoryIds : ScheduleRepository.NO_CATEGORY_FILTER,
            request.createdSince(), 100);

        return slots.stream()
            .filter(s -> !s.getProgram().getUserActivity().getUser().getId().equals(requesterId))
            .map(s -> toFeedItem(s, request.lat(), request.lng(), requesterId))
            .toList();
    }

    @Transactional(readOnly = true)
    public SlotFeedItemDto getSlot(UUID scheduleId, UUID requesterId) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));
        return toFeedItem(slot, null, null, requesterId);
    }

    /**
     * Rejoindre un créneau. Effet de bord clé : ouvre automatiquement une
     * conversation avec l'hôte, contextualisée par l'activité.
     */
    public SlotFeedItemDto joinSlot(UUID userId, UUID scheduleId, JoinSlotRequest request) {
        // Verrou pessimiste : même ligne que ProgramEnrollmentService.joinProgram
        // pour empêcher un dépassement de maxParticipants par les deux chemins à la fois.
        Schedule slot = scheduleRepository.lockById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        User host = slot.getProgram().getUserActivity().getUser();

        if (host.getId().equals(userId)) {
            throw new ValidationException(ErrorCode.SLOT_OWN_SLOT, "Vous ne pouvez pas rejoindre votre propre créneau.");
        }
        if (!Boolean.TRUE.equals(slot.getIsOpenToPartners())) {
            throw new ValidationException(ErrorCode.SLOT_NOT_OPEN_TO_PARTNERS, "Ce créneau n'est pas ouvert aux partenaires.");
        }
        if (slot.getStatus() != SlotStatus.OPEN) {
            throw new ValidationException(ErrorCode.SLOT_NOT_ACCEPTING_PARTICIPANTS, "Ce créneau n'accepte plus de participants.");
        }
        if (slot.getStartsAt().isBefore(Instant.now())) {
            throw new ValidationException(ErrorCode.SLOT_ALREADY_STARTED, "Ce créneau est déjà passé.");
        }
        if (participationRepository.existsByScheduleIdAndUserId(scheduleId, userId)) {
            throw new BusinessException(ErrorCode.SLOT_ALREADY_JOINED, "Vous avez déjà rejoint ce créneau.");
        }
        if (slot.getMaxParticipants() != null
                && scheduleRepository.countConfirmedParticipants(scheduleId) >= slot.getMaxParticipants()) {
            throw new ValidationException(ErrorCode.SLOT_FULL, "Ce créneau est complet.");
        }

        // Même règle et même enveloppe que POST /programs/{id}/join : le chemin
        // d'entrée ne doit pas changer ce qui est autorisé. Vérifiée en dernier,
        // sous le verrou pessimiste posé plus haut — c'est ce qui empêche deux
        // appareils de s'inscrire en parallèle sur deux créneaux qui se chevauchent.
        List<ScheduleConflictDto> conflicts = conflictDetector.detect(userId, List.of(slot));
        if (!conflicts.isEmpty()) {
            throw new ScheduleConflictException(
                "Ce créneau chevauche un engagement que vous avez déjà pris.", conflicts);
        }

        SlotParticipation participation = new SlotParticipation();
        participation.setSchedule(slot);
        participation.setUser(userRepository.getReferenceById(userId));
        participation.setStatus(ParticipationStatus.CONFIRMED);
        if (request.joinMessage() != null) {
            participation.setJoinMessage(sanitizer.sanitize(request.joinMessage()).strip());
        }
        participationRepository.save(participation);

        long confirmed = scheduleRepository.countConfirmedParticipants(scheduleId);
        slot.setParticipantCount((int) confirmed);
        if (slot.getMaxParticipants() != null && confirmed >= slot.getMaxParticipants()) {
            slot.setStatus(SlotStatus.FULL);
        }
        scheduleRepository.save(slot);

        // Ouvrir la conversation contextualisée (respecte receiveMessages de l'hôte).
        //
        // Le contexte est celui du créneau, pas seulement celui de l'activité :
        // c'est cette séance-là qui lie les deux personnes, et c'est sa date que
        // le client compare à maintenant pour griser le fil une fois passée.
        // L'activité seule ne désignerait pas la bonne séance dès que quelqu'un
        // suit deux programmes de la même activité.
        //
        // Deux réglages, deux portées : receiveMessages est celui de la personne,
        // allowParticipantMessages celui de ce programme-là. Un refus fait sauter
        // l'ouverture du fil, jamais l'inscription au créneau — rejoindre et
        // écrire sont deux choses, et fermer sa messagerie ne ferme pas ses
        // créneaux.
        if (Boolean.TRUE.equals(host.getReceiveMessages())
                && Boolean.TRUE.equals(slot.getProgram().getAllowParticipantMessages())) {
            chatService.createConversation(
                userId,
                new CreateConversationRequest(
                    host.getId(),
                    slot.getProgram().getUserActivity().getActivity().getId(),
                    slot.getProgram().getId()),
                slot.getProgram().getId(),
                slot.getId());
        }

        notificationService.notify(host.getId(), NotificationType.SLOT_JOINED,
            NotificationPayload.ofSchedule(slot)
                .with("participantId", userId)
                .with("participantName", userRepository.findById(userId)
                    .map(User::getDisplayName).orElse("Quelqu'un"))
                .build());

        return toFeedItem(slot, null, null, userId);
    }

    public void leaveSlot(UUID userId, UUID scheduleId) {
        SlotParticipation participation = participationRepository
            .findByScheduleIdAndUserId(scheduleId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Participation introuvable."));

        participation.setStatus(ParticipationStatus.WITHDRAWN);
        participationRepository.save(participation);

        // Verrou pessimiste pour recalculer participantCount/status en cohérence
        // avec un join concurrent sur le même créneau.
        Schedule slot = scheduleRepository.lockById(participation.getSchedule().getId())
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));
        long confirmed = scheduleRepository.countConfirmedParticipants(slot.getId());
        slot.setParticipantCount((int) confirmed);
        if (slot.getStatus() == SlotStatus.FULL
                && (slot.getMaxParticipants() == null || confirmed < slot.getMaxParticipants())) {
            slot.setStatus(SlotStatus.OPEN);
        }
        scheduleRepository.save(slot);
    }

    @Transactional(readOnly = true)
    public List<SlotFeedItemDto> getMySlots(UUID userId, boolean upcomingOnly) {
        List<Schedule> hosted = scheduleRepository.findHostedOpenSlots(userId);

        List<Schedule> joined = participationRepository
            .findByUserIdAndStatusIn(userId, List.of(ParticipationStatus.INTERESTED, ParticipationStatus.CONFIRMED))
            .stream()
            .map(SlotParticipation::getSchedule)
            .toList();

        return java.util.stream.Stream.concat(hosted.stream(), joined.stream())
            .distinct()
            .filter(s -> !upcomingOnly || s.getStartsAt().isAfter(Instant.now()))
            .sorted(java.util.Comparator.comparing(Schedule::getStartsAt))
            .map(s -> toFeedItem(s, null, null, userId))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<SlotParticipantDto> getParticipants(UUID userId, UUID scheduleId) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        UUID hostId = slot.getProgram().getUserActivity().getUser().getId();
        if (!hostId.equals(userId)) {
            throw new ForbiddenException(ErrorCode.SLOT_PARTICIPANTS_HOST_ONLY, "Seul l'hôte peut voir les participants.");
        }

        return participationRepository.findByScheduleId(scheduleId).stream()
            .map(p -> new SlotParticipantDto(
                p.getId(),
                userService.getPublicProfile(p.getUser().getId(), userId),
                p.getStatus().name(),
                p.getJoinMessage(),
                p.getCreatedAt()
            ))
            .toList();
    }

    private SlotFeedItemDto toFeedItem(Schedule slot, Double viewerLat, Double viewerLng, UUID requesterId) {
        Program program = slot.getProgram();
        UserActivity userActivity = program.getUserActivity();
        Activity activity = userActivity.getActivity();
        Category category = activity.getCategory();

        SlotAddressVisibility.Resolved place = SlotAddressVisibility.resolve(slot, requesterId, participationRepository);

        Double distanceMeters = null;
        if (viewerLat != null && viewerLng != null && slot.getLocation() != null) {
            distanceMeters = GeoUtils.haversineMeters(viewerLat, viewerLng,
                slot.getLocation().getY(), slot.getLocation().getX());
        }

        String myParticipationStatus = requesterId == null ? null : participationRepository
            .findByScheduleIdAndUserId(slot.getId(), requesterId)
            .map(p -> p.getStatus().name())
            .orElse(null);

        return new SlotFeedItemDto(
            slot.getId(),
            program.getId(),
            program.getTitle(),
            activity.getId(),
            activity.getName(),
            category != null ? category.getId() : null,
            category != null ? category.getColorRamp() : null,
            userActivity.getLevel() != null ? userActivity.getLevel().name() : null,
            userActivity.getFormat() != null ? userActivity.getFormat().name() : null,
            userService.getPublicProfile(userActivity.getUser().getId(), requesterId),
            slot.getPlaceName(),
            place.displayAddress(),
            place.lat(),
            place.lng(),
            distanceMeters,
            slot.getStartsAt(),
            slot.getEndsAt(),
            slot.getRecurrenceRule(),
            sessionDurationMinutes(slot, program),
            slot.getCreatedAt(),
            slot.getMaxParticipants(),
            slot.getParticipantCount(),
            slot.getIsOpenToPartners(),
            slot.getWelcomeNote(),
            myParticipationStatus
        );
    }

    /**
     * Durée d'une séance, mesurée si possible, déclarée sinon, jamais devinée.
     *
     * <p>{@code endsAt} est nullable en base ; quand il manque, la durée déclarée
     * sur le programme est une meilleure réponse que rien. Quand les deux manquent,
     * on rend {@code null} plutôt qu'une convention : c'est à l'appelant de savoir
     * qu'il ne sait pas. La convention, elle, n'existe qu'à l'endroit où il faut
     * bien trancher — {@link ScheduleConflictDetector}.
     */
    private Integer sessionDurationMinutes(Schedule slot, Program program) {
        if (slot.getStartsAt() != null && slot.getEndsAt() != null
                && slot.getEndsAt().isAfter(slot.getStartsAt())) {
            return (int) Duration.between(slot.getStartsAt(), slot.getEndsAt()).toMinutes();
        }
        return program != null ? program.getSessionDurationMinutes() : null;
    }
}
