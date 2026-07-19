package org.program.pair.domain.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.user.User;
import org.program.pair.repository.CategoryRepository;
import org.program.pair.repository.SubscriptionRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    SubscriptionRepository subscriptionRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    UserActivityRepository userActivityRepository;

    @Mock
    CategoryRepository categoryRepository;

    @Mock
    NotificationService notificationService;

    @InjectMocks
    SubscriptionService subscriptionService;

    @Test
    void subscribeToAuthor_soiMeme_doitEtreInterdit() {
        UUID userId = UUID.randomUUID();

        assertThatThrownBy(() -> subscriptionService.subscribeToAuthor(userId, userId))
            .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(notificationService);
    }

    @Test
    void subscribeToAuthor_dejaAbonne_doitEchouer() {
        UUID subscriberId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        when(subscriptionRepository.existsBySubscriberIdAndTargetAuthorId(subscriberId, authorId))
            .thenReturn(true);

        assertThatThrownBy(() -> subscriptionService.subscribeToAuthor(subscriberId, authorId))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void subscribeToAuthor_doitNotifierLAuteurAvecNewFollower() {
        UUID subscriberId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        User subscriber = new User();
        subscriber.setId(subscriberId);
        subscriber.setDisplayName("Bob");
        User author = new User();
        author.setId(authorId);

        when(subscriptionRepository.existsBySubscriberIdAndTargetAuthorId(subscriberId, authorId))
            .thenReturn(false);
        when(userRepository.findById(subscriberId)).thenReturn(Optional.of(subscriber));
        when(userRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        subscriptionService.subscribeToAuthor(subscriberId, authorId);

        verify(notificationService).notify(eq(authorId), eq(NotificationType.NEW_FOLLOWER), any());
    }

    @Test
    void subscribeToCategory_categorieIntrouvable_doitEchouer() {
        UUID subscriberId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        when(subscriptionRepository.existsBySubscriberIdAndTargetCategoryId(subscriberId, categoryId))
            .thenReturn(false);
        when(userRepository.findById(subscriberId)).thenReturn(Optional.of(new User()));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.subscribeToCategory(subscriberId, categoryId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void subscribeToCategory_doitPersisterLAbonnement() {
        UUID subscriberId = UUID.randomUUID();
        User subscriber = new User();
        subscriber.setId(subscriberId);
        Category category = Category.builder().id(UUID.randomUUID()).name("Sports").build();

        when(subscriptionRepository.existsBySubscriberIdAndTargetCategoryId(subscriberId, category.getId()))
            .thenReturn(false);
        when(userRepository.findById(subscriberId)).thenReturn(Optional.of(subscriber));
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = subscriptionService.subscribeToCategory(subscriberId, category.getId());

        assertThat(dto.type()).isEqualTo("CATEGORY");
        assertThat(dto.targetCategoryId()).isEqualTo(category.getId());
    }
}
