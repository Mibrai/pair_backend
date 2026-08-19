package org.program.pair.domain.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityAlertRepository;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.UserRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityAlertServiceTest {

    @Mock ActivityAlertRepository alertRepository;
    @Mock ActivityRepository activityRepository;
    @Mock UserRepository userRepository;
    @Mock NotificationService notificationService;

    @InjectMocks
    ActivityAlertService activityAlertService;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void evaluateAndNotify_neDoitJamaisNotifierLHote() {
        UUID hostId = UUID.randomUUID();
        Schedule slot = buildOpenSlot(hostId);

        ActivityAlert hostsOwnAlert = buildAlert(hostId);
        when(alertRepository.findMatchingAlerts(any(), anyDouble(), anyDouble(), any()))
            .thenReturn(List.of(hostsOwnAlert));

        activityAlertService.evaluateAndNotify(slot);

        verify(notificationService, never()).notify(eq(hostId), any(), any(), any());
    }

    @Test
    void evaluateAndNotify_devraitNotifierLesAutresUtilisateursCorrespondants() {
        UUID hostId = UUID.randomUUID();
        UUID watcherId = UUID.randomUUID();
        Schedule slot = buildOpenSlot(hostId);

        ActivityAlert alert = buildAlert(watcherId);
        when(alertRepository.findMatchingAlerts(any(), anyDouble(), anyDouble(), any()))
            .thenReturn(List.of(alert));
        when(alertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        activityAlertService.evaluateAndNotify(slot);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).notify(eq(watcherId), any(), eq(NotificationType.ACTIVITY_ALERT_MATCH), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("scheduleId", slot.getId().toString());
        assertThat(alert.getLastTriggeredAt()).isNotNull();
    }

    @Test
    void evaluateAndNotify_neDoitRienFaire_siCreneauFermeAuxPartenaires() {
        UUID hostId = UUID.randomUUID();
        Schedule slot = buildOpenSlot(hostId);
        slot.setIsOpenToPartners(false);

        activityAlertService.evaluateAndNotify(slot);

        verifyNoInteractions(alertRepository, notificationService);
    }

    private Schedule buildOpenSlot(UUID hostId) {
        User host = new User();
        host.setId(hostId);

        Activity activity = Activity.builder().id(UUID.randomUUID()).name("Escalade").build();
        UserActivity ua = new UserActivity();
        ua.setUser(host);
        ua.setActivity(activity);

        Program program = new Program();
        program.setId(UUID.randomUUID());
        program.setUserActivity(ua);

        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setProgram(program);
        schedule.setPlaceName("Salle d'escalade");
        schedule.setStartsAt(Instant.now().plus(1, ChronoUnit.DAYS));
        schedule.setIsOpenToPartners(true);
        schedule.setLocation(geometryFactory.createPoint(new Coordinate(2.35, 48.85)));
        return schedule;
    }

    private ActivityAlert buildAlert(UUID userId) {
        User user = new User();
        user.setId(userId);
        return ActivityAlert.builder()
            .id(UUID.randomUUID())
            .user(user)
            .radiusMeters(10000)
            .isActive(true)
            .build();
    }
}
