package org.program.pair.domain.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.user.User;
import org.program.pair.repository.CategoryRepository;
import org.program.pair.repository.SubscriptionRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.domain.subscription.dto.SubscriptionDto;
import org.program.pair.domain.subscription.dto.SubscriptionScopeRequest;
import org.program.pair.domain.subscription.dto.UpdateSubscriptionRequest;
import org.program.pair.domain.user.PrivacySettings;
import org.program.pair.domain.user.SubscriptionPermission;
import org.program.pair.shared.exception.ConflictException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;

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
            .isInstanceOf(ConflictException.class)
            .extracting(ex -> ((ConflictException) ex).getErrorCode())
            .isEqualTo(ErrorCode.ALREADY_SUBSCRIBED);
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

        assertThatThrownBy(() -> subscriptionService.subscribeToCategory(subscriberId, categoryId, null))
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

        var dto = subscriptionService.subscribeToCategory(subscriberId, category.getId(), null);

        assertThat(dto.type()).isEqualTo("CATEGORY");
        assertThat(dto.targetCategoryId()).isEqualTo(category.getId());
    }

    // --- Lot A : refus nommés, idempotence, niveau, portée ---

    @Test
    void subscribeToAuthor_profilFermeAuxAbonnements_doitEtreRefuse() {
        UUID subscriberId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        User subscriber = new User();
        subscriber.setId(subscriberId);
        User author = new User();
        author.setId(authorId);
        author.setPrivacySettings(PrivacySettings.builder()
            .allowSubscriptions(SubscriptionPermission.NOBODY)
            .build());

        when(subscriptionRepository.existsBySubscriberIdAndTargetAuthorId(subscriberId, authorId))
            .thenReturn(false);
        when(userRepository.findById(subscriberId)).thenReturn(Optional.of(subscriber));
        when(userRepository.findById(authorId)).thenReturn(Optional.of(author));

        assertThatThrownBy(() -> subscriptionService.subscribeToAuthor(subscriberId, authorId))
            .isInstanceOf(ForbiddenException.class)
            .extracting(ex -> ((ForbiddenException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SUBSCRIPTIONS_NOT_ALLOWED);

        verify(subscriptionRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    /**
     * Le réglage ne vaut que pour l'avenir : il refuse les nouveaux abonnements,
     * il ne supprime pas les existants et ne les fait pas taire.
     */
    @Test
    void unsubscribeFromAuthor_profilFerme_resteToujoursPossible() {
        UUID subscriberId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Subscription existing = Subscription.builder().id(UUID.randomUUID()).build();

        when(subscriptionRepository.findBySubscriberIdAndTargetAuthorId(subscriberId, authorId))
            .thenReturn(Optional.of(existing));

        subscriptionService.unsubscribeFromAuthor(subscriberId, authorId);

        verify(subscriptionRepository).delete(existing);
    }

    /**
     * Un retrait déjà effectué rend 204 et non 404 : le client fait un retrait
     * optimiste, et une erreur le ferait revenir en arrière à tort.
     */
    @Test
    void unsubscribe_sansAbonnement_doitResterSilencieux() {
        UUID subscriberId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(subscriptionRepository.findBySubscriberIdAndTargetAuthorId(subscriberId, targetId))
            .thenReturn(Optional.empty());
        when(subscriptionRepository.findBySubscriberIdAndTargetUserActivityId(subscriberId, targetId))
            .thenReturn(Optional.empty());
        when(subscriptionRepository.findBySubscriberIdAndTargetCategoryId(subscriberId, targetId))
            .thenReturn(Optional.empty());

        subscriptionService.unsubscribeFromAuthor(subscriberId, targetId);
        subscriptionService.unsubscribeFromUserActivity(subscriberId, targetId);
        subscriptionService.unsubscribeFromCategory(subscriberId, targetId);

        verify(subscriptionRepository, never()).delete(any());
    }

    @Test
    void subscribeToCategory_avecPortee_doitLaPersisterEnMetres() {
        UUID subscriberId = UUID.randomUUID();
        User subscriber = new User();
        subscriber.setId(subscriberId);
        Category category = Category.builder().id(UUID.randomUUID()).name("Yoga").build();

        when(subscriptionRepository.existsBySubscriberIdAndTargetCategoryId(subscriberId, category.getId()))
            .thenReturn(false);
        when(userRepository.findById(subscriberId)).thenReturn(Optional.of(subscriber));
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubscriptionDto dto = subscriptionService.subscribeToCategory(
            subscriberId, category.getId(),
            new SubscriptionScopeRequest(48.8566, 2.3522, 20_000));

        assertThat(dto.lat()).isEqualTo(48.8566);
        assertThat(dto.lng()).isEqualTo(2.3522);
        assertThat(dto.radiusMeters()).isEqualTo(20_000);
        assertThat(dto.level()).isEqualTo("ALL");
    }

    /** Un centre sans rayon ne décrit rien : les trois champs vont ensemble. */
    @Test
    void subscribeToCategory_porteeIncomplete_doitEchouer() {
        UUID subscriberId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        when(subscriptionRepository.existsBySubscriberIdAndTargetCategoryId(subscriberId, categoryId))
            .thenReturn(false);
        when(userRepository.findById(subscriberId)).thenReturn(Optional.of(new User()));
        when(categoryRepository.findById(categoryId))
            .thenReturn(Optional.of(Category.builder().id(categoryId).build()));

        assertThatThrownBy(() -> subscriptionService.subscribeToCategory(
            subscriberId, categoryId, new SubscriptionScopeRequest(48.85, null, 20_000)))
            .isInstanceOf(ValidationException.class);

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void updateCategorySubscription_doitChangerLeNiveauSansToucherLaPortee() {
        UUID subscriberId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        Subscription existing = categorySubscription(categoryId, 48.85, 2.35, 20_000);

        when(subscriptionRepository.findBySubscriberIdAndTargetCategoryId(subscriberId, categoryId))
            .thenReturn(Optional.of(existing));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubscriptionDto dto = subscriptionService.updateCategorySubscription(
            subscriberId, categoryId,
            new UpdateSubscriptionRequest("MUTED", null, null, null, null));

        assertThat(dto.level()).isEqualTo("MUTED");
        assertThat(dto.radiusMeters()).isEqualTo(20_000);
    }

    /**
     * Le retrait de portée passe par {@code clearScope} et non par trois
     * {@code null} : en JSON, un champ absent et un champ nul sont
     * indiscernables, et un changement de niveau ne doit pas effacer un rayon.
     */
    @Test
    void updateCategorySubscription_clearScope_doitRetirerLaPortee() {
        UUID subscriberId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        Subscription existing = categorySubscription(categoryId, 48.85, 2.35, 20_000);

        when(subscriptionRepository.findBySubscriberIdAndTargetCategoryId(subscriberId, categoryId))
            .thenReturn(Optional.of(existing));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubscriptionDto dto = subscriptionService.updateCategorySubscription(
            subscriberId, categoryId,
            new UpdateSubscriptionRequest(null, null, null, null, true));

        assertThat(dto.lat()).isNull();
        assertThat(dto.lng()).isNull();
        assertThat(dto.radiusMeters()).isNull();
    }

    @Test
    void updateCategorySubscription_clearScopeEtPortee_doitEchouer() {
        UUID subscriberId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        when(subscriptionRepository.findBySubscriberIdAndTargetCategoryId(subscriberId, categoryId))
            .thenReturn(Optional.of(categorySubscription(categoryId, null, null, null)));

        assertThatThrownBy(() -> subscriptionService.updateCategorySubscription(
            subscriberId, categoryId,
            new UpdateSubscriptionRequest(null, 48.85, 2.35, 20_000, true)))
            .isInstanceOf(ValidationException.class);
    }

    /** Un rayon sur un abonnement AUTHOR n'aurait aucun effet : il est refusé. */
    @Test
    void updateAuthorSubscription_avecPortee_doitEchouer() {
        UUID subscriberId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Subscription existing = Subscription.builder()
            .id(UUID.randomUUID())
            .type(SubscriptionType.AUTHOR)
            .build();

        when(subscriptionRepository.findBySubscriberIdAndTargetAuthorId(subscriberId, authorId))
            .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> subscriptionService.updateAuthorSubscription(
            subscriberId, authorId,
            new UpdateSubscriptionRequest(null, 48.85, 2.35, 20_000, null)))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void updateSubscription_abonnementInexistant_doitEtreIntrouvable() {
        UUID subscriberId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        when(subscriptionRepository.findBySubscriberIdAndTargetAuthorId(subscriberId, authorId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.updateAuthorSubscription(
            subscriberId, authorId, new UpdateSubscriptionRequest("MUTED", null, null, null, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- Le réglage « qui peut me suivre » vaut sur les deux chemins ---

    /**
     * Le contournement que corrige ce correctif : le réglage n'était vérifié que
     * sur le profil, et suivre l'activité de quelqu'un revient à le suivre —
     * l'abonné ainsi arrivé recevait bien ses nouveaux programmes.
     */
    @Test
    void sAbonnerALActiviteDUnProfilFerme_doitEtreRefuse() {
        UUID subscriberId = UUID.randomUUID();
        UUID userActivityId = UUID.randomUUID();

        User subscriber = new User();
        subscriber.setId(subscriberId);

        User auteurFerme = new User();
        auteurFerme.setId(UUID.randomUUID());
        auteurFerme.setPrivacySettings(PrivacySettings.builder()
            .allowSubscriptions(SubscriptionPermission.NOBODY)
            .build());

        UserActivity activite = new UserActivity();
        activite.setId(userActivityId);
        activite.setUser(auteurFerme);

        when(subscriptionRepository.existsBySubscriberIdAndTargetUserActivityId(subscriberId, userActivityId))
            .thenReturn(false);
        when(userRepository.findById(subscriberId)).thenReturn(Optional.of(subscriber));
        when(userActivityRepository.findById(userActivityId)).thenReturn(Optional.of(activite));

        assertThatThrownBy(() -> subscriptionService.subscribeToUserActivity(subscriberId, userActivityId))
            .isInstanceOf(ForbiddenException.class)
            .extracting(ex -> ((ForbiddenException) ex).getErrorCode())
            .isEqualTo(ErrorCode.SUBSCRIPTIONS_NOT_ALLOWED);

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void sAbonnerALActiviteDUnProfilOuvert_doitResterPossible() {
        UUID subscriberId = UUID.randomUUID();
        UUID userActivityId = UUID.randomUUID();

        User subscriber = new User();
        subscriber.setId(subscriberId);

        User auteur = new User();
        auteur.setId(UUID.randomUUID());

        Activity referentiel = Activity.builder().id(UUID.randomUUID()).name("Course").build();
        UserActivity activite = new UserActivity();
        activite.setId(userActivityId);
        activite.setUser(auteur);
        activite.setActivity(referentiel);

        when(subscriptionRepository.existsBySubscriberIdAndTargetUserActivityId(subscriberId, userActivityId))
            .thenReturn(false);
        when(userRepository.findById(subscriberId)).thenReturn(Optional.of(subscriber));
        when(userActivityRepository.findById(userActivityId)).thenReturn(Optional.of(activite));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(subscriptionService.subscribeToUserActivity(subscriberId, userActivityId).type())
            .isEqualTo("USER_ACTIVITY");
    }

    /**
     * La contrainte chk_subscription_not_self de la V36 ne couvre que la cible
     * AUTHOR : dire la même chose d'une activité supposerait de joindre
     * user_activities pour lire son propriétaire, ce qu'un CHECK ne sait pas
     * faire. Ce garde-fou applicatif est donc le seul.
     */
    @Test
    void sAbonnerASaPropreActivite_doitEtreInterdit() {
        UUID moi = UUID.randomUUID();
        UUID userActivityId = UUID.randomUUID();

        User subscriber = new User();
        subscriber.setId(moi);

        UserActivity maPropreActivite = new UserActivity();
        maPropreActivite.setId(userActivityId);
        maPropreActivite.setUser(subscriber);

        when(subscriptionRepository.existsBySubscriberIdAndTargetUserActivityId(moi, userActivityId))
            .thenReturn(false);
        when(userRepository.findById(moi)).thenReturn(Optional.of(subscriber));
        when(userActivityRepository.findById(userActivityId)).thenReturn(Optional.of(maPropreActivite));

        assertThatThrownBy(() -> subscriptionService.subscribeToUserActivity(moi, userActivityId))
            .isInstanceOf(ForbiddenException.class);

        verify(subscriptionRepository, never()).save(any());
    }

    private Subscription categorySubscription(UUID categoryId, Double lat, Double lng, Integer radius) {
        return Subscription.builder()
            .id(UUID.randomUUID())
            .type(SubscriptionType.CATEGORY)
            .targetCategory(Category.builder().id(categoryId).name("Yoga").build())
            .lat(lat)
            .lng(lng)
            .radiusMeters(radius)
            .build();
    }
}
