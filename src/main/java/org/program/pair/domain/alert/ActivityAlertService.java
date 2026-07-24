package org.program.pair.domain.alert;

import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.alert.dto.ActivityAlertDto;
import org.program.pair.domain.alert.dto.CreateActivityAlertRequest;
import org.program.pair.domain.alert.dto.UpdateActivityAlertRequest;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.Schedule;
import org.program.pair.repository.ActivityAlertRepository;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Alertes "préviens-moi quand quelqu'un pratique cette activité près de moi" —
 * réponse anti-carte-vide. Le déclenchement se fait par appel direct depuis
 * ProgramService.addSchedule (pas d'event Spring : aucun bus d'événements
 * n'existe ailleurs dans ce code, on reste cohérent avec le style existant).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ActivityAlertService {

    private final ActivityAlertRepository alertRepository;
    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Transactional(readOnly = true)
    public List<ActivityAlertDto> getMyAlerts(UUID userId) {
        return alertRepository.findByUserId(userId).stream().map(this::toDto).toList();
    }

    public ActivityAlertDto createAlert(UUID userId, CreateActivityAlertRequest request) {
        Activity activity = activityRepository.findById(request.activityId())
            .orElseThrow(() -> new ResourceNotFoundException("Activité introuvable."));

        if (alertRepository.existsByUserIdAndActivityId(userId, request.activityId())) {
            throw new BusinessException("Vous avez déjà une alerte pour cette activité.");
        }

        ActivityAlert alert = ActivityAlert.builder()
            .user(userRepository.getReferenceById(userId))
            .activity(activity)
            .location(geometryFactory.createPoint(new Coordinate(request.lng(), request.lat())))
            .radiusMeters(request.radiusMeters() != null ? request.radiusMeters() : 10000)
            .isActive(true)
            .build();

        return toDto(alertRepository.save(alert));
    }

    public ActivityAlertDto updateAlert(UUID userId, UUID alertId, UpdateActivityAlertRequest request) {
        ActivityAlert alert = alertRepository.findById(alertId)
            .orElseThrow(() -> new ResourceNotFoundException("Alerte introuvable."));

        if (!alert.getUser().getId().equals(userId)) {
            throw new ForbiddenException("Vous ne pouvez pas modifier cette alerte.");
        }

        if (request.isActive() != null) {
            alert.setIsActive(request.isActive());
        }

        return toDto(alertRepository.save(alert));
    }

    public void deleteAlert(UUID userId, UUID alertId) {
        ActivityAlert alert = alertRepository.findById(alertId)
            .orElseThrow(() -> new ResourceNotFoundException("Alerte introuvable."));

        if (!alert.getUser().getId().equals(userId)) {
            throw new ForbiddenException("Vous ne pouvez pas supprimer cette alerte.");
        }

        alertRepository.delete(alert);
    }

    /**
     * Déclenché quand un nouveau créneau ouvert est créé. Notifie les
     * utilisateurs qui attendaient cette activité dans la zone.
     */
    @Async
    public void evaluateAndNotify(Schedule slot) {
        if (!Boolean.TRUE.equals(slot.getIsOpenToPartners()) || slot.getLocation() == null) return;

        UUID activityId = slot.getProgram().getUserActivity().getActivity().getId();
        UUID hostId = slot.getProgram().getUserActivity().getUser().getId();

        Instant cooldown = Instant.now().minus(7, ChronoUnit.DAYS);

        List<ActivityAlert> matching = alertRepository.findMatchingAlerts(
            activityId,
            slot.getLocation().getY(),
            slot.getLocation().getX(),
            cooldown
        );

        for (ActivityAlert alert : matching) {
            if (alert.getUser().getId().equals(hostId)) continue;

            notificationService.notify(alert.getUser().getId(), NotificationType.ACTIVITY_ALERT_MATCH, Map.of(
                "activityName", slot.getProgram().getUserActivity().getActivity().getName(),
                "scheduleId", slot.getId().toString(),
                "placeName", slot.getPlaceName(),
                "startsAt", slot.getStartsAt().toString()
            ));

            alert.setLastTriggeredAt(Instant.now());
            alertRepository.save(alert);
        }
    }

    private ActivityAlertDto toDto(ActivityAlert alert) {
        return new ActivityAlertDto(
            alert.getId(),
            alert.getActivity().getId(),
            alert.getActivity().getName(),
            alert.getLocation().getY(),
            alert.getLocation().getX(),
            alert.getRadiusMeters(),
            alert.getIsActive(),
            alert.getLastTriggeredAt(),
            alert.getCreatedAt()
        );
    }
}
