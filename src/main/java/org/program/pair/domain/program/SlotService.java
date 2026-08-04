package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.chat.ChatService;
import org.program.pair.domain.chat.dto.CreateConversationRequest;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.dto.JoinSlotRequest;
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
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
    private final HtmlSanitizer sanitizer;

    @Transactional(readOnly = true)
    public List<SlotFeedItemDto> getSlotFeed(SlotFeedRequest request, UUID requesterId) {
        Instant from = request.from() != null ? request.from() : Instant.now();
        Instant to = request.to() != null ? request.to() : Instant.now().plus(7, ChronoUnit.DAYS);

        List<Schedule> slots = scheduleRepository.findOpenSlotsInRadius(
            request.lat(), request.lng(), request.radiusMeters(),
            from, to, request.activityId(), request.categoryId(), 100);

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

        // Ouvrir la conversation contextualisée (respecte receiveMessages de l'hôte)
        if (Boolean.TRUE.equals(host.getReceiveMessages())) {
            chatService.createConversation(userId, new CreateConversationRequest(
                host.getId(),
                slot.getProgram().getUserActivity().getActivity().getId()
            ));
        }

        notificationService.notify(host.getId(), NotificationType.SLOT_JOINED, Map.of(
            "scheduleId", scheduleId.toString(),
            "programTitle", slot.getProgram().getTitle(),
            "participantName", userRepository.findById(userId)
                .map(User::getDisplayName).orElse("Quelqu'un")
        ));

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
            activity.getName(),
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
            slot.getMaxParticipants(),
            slot.getParticipantCount(),
            slot.getIsOpenToPartners(),
            slot.getWelcomeNote(),
            myParticipationStatus
        );
    }
}
