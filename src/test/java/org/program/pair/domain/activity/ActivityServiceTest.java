package org.program.pair.domain.activity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.activity.dto.UpsertUserActivityRequest;
import org.program.pair.domain.subscription.SubscriptionService;
import org.program.pair.domain.user.User;
import org.program.pair.repository.*;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActivityServiceTest {

    @Mock
    CategoryRepository categoryRepository;

    @Mock
    ActivityRepository activityRepository;

    @Mock
    UserActivityRepository userActivityRepository;

    @Mock
    ProgramRepository programRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    SubscriptionService subscriptionService;

    @Mock
    org.program.pair.shared.sanitizer.HtmlSanitizer sanitizer;

    @InjectMocks
    ActivityService activityService;

    @Test
    void addActivityToProfile_doitNotifierLesAbonnesDeLAuteurEtDeLaCategorie() {
        // Given
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);

        Category category = Category.builder().id(UUID.randomUUID()).name("Sports").build();
        Activity activity = Activity.builder().id(UUID.randomUUID()).name("Football").category(category).build();

        when(activityRepository.findById(activity.getId())).thenReturn(Optional.of(activity));
        when(userActivityRepository.existsByUserIdAndActivityId(userId, activity.getId())).thenReturn(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userActivityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(programRepository.findByUserActivityId(any())).thenReturn(java.util.List.of());

        UpsertUserActivityRequest request = new UpsertUserActivityRequest(
            activity.getId(), true, null, null, null);

        // When
        activityService.addActivityToProfile(userId, request);

        // Then
        ArgumentCaptor<UserActivity> captor = ArgumentCaptor.forClass(UserActivity.class);
        verify(subscriptionService).notifySubscribersOfNewUserActivity(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
        assertThat(captor.getValue().getActivity()).isEqualTo(activity);
    }

    @Test
    void updateUserActivity_doitNotifierLesAbonnesDeLActivite() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID userActivityId = UUID.randomUUID();

        User user = new User();
        user.setId(userId);

        Category category = Category.builder().id(UUID.randomUUID()).name("Sports").build();
        Activity activity = Activity.builder().id(UUID.randomUUID()).name("Football").category(category).build();

        UserActivity userActivity = new UserActivity();
        userActivity.setId(userActivityId);
        userActivity.setUser(user);
        userActivity.setActivity(activity);

        when(userActivityRepository.findByIdAndUserId(userActivityId, userId))
            .thenReturn(Optional.of(userActivity));
        when(userActivityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(programRepository.findByUserActivityId(any())).thenReturn(java.util.List.of());

        UpsertUserActivityRequest request = new UpsertUserActivityRequest(
            activity.getId(), null, "Nouvelle description", null, null);

        // When
        activityService.updateUserActivity(userId, userActivityId, request);

        // Then
        verify(subscriptionService).notifySubscribersOfUserActivityUpdate(userActivity);
    }
}
