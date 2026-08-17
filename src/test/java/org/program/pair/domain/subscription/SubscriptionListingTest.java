package org.program.pair.domain.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.subscription.dto.SubscriberDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.CategoryRepository;
import org.program.pair.repository.SubscriptionRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Les règles de visibilité des deux listes du lot C.
 *
 * <p>Ce qui se joue ici n'est pas le contenu des pages — c'est une base réelle
 * qui le dit, voir {@code SubscriptionListingIntegrationTest} — mais <b>qui a le
 * droit de les demander</b>.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionListingTest {

    @Mock SubscriptionRepository subscriptionRepository;
    @Mock UserRepository userRepository;
    @Mock UserActivityRepository userActivityRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock NotificationService notificationService;

    @InjectMocks SubscriptionService subscriptionService;

    private final Pageable page = PageRequest.of(0, 20);

    /**
     * Une catégorie n'appartient à personne : {@code Category} ne porte ni
     * propriétaire ni créateur. Il n'existe donc aucun appelant à qui cette
     * liste pourrait légitimement revenir.
     */
    @Test
    void listerLesAbonnesDUneCategorie_doitEtreRefuseATous() {
        assertThatThrownBy(() -> subscriptionService.listMySubscribers(
            UUID.randomUUID(), SubscriptionType.CATEGORY, null, page))
            .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    void listerLesAbonnesDUneActiviteDAutrui_doitEtreRefuse() {
        UUID appelant = UUID.randomUUID();
        UUID activiteId = UUID.randomUUID();

        User autre = new User();
        autre.setId(UUID.randomUUID());
        UserActivity activite = new UserActivity();
        activite.setId(activiteId);
        activite.setUser(autre);

        when(userActivityRepository.findById(activiteId)).thenReturn(Optional.of(activite));

        assertThatThrownBy(() -> subscriptionService.listMySubscribers(
            appelant, null, activiteId, page))
            .isInstanceOf(ForbiddenException.class);

        verify(subscriptionRepository, never()).findMySubscribers(any(), any(), any(), any());
    }

    @Test
    void listerLesAbonnesDUneActiviteInexistante_doitEtreIntrouvable() {
        UUID activiteId = UUID.randomUUID();
        when(userActivityRepository.findById(activiteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.listMySubscribers(
            UUID.randomUUID(), null, activiteId, page))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listerLesAbonnesDeSaPropreActivite_doitEtreAutorise() {
        UUID appelant = UUID.randomUUID();
        UUID activiteId = UUID.randomUUID();

        User proprietaire = new User();
        proprietaire.setId(appelant);
        UserActivity activite = new UserActivity();
        activite.setId(activiteId);
        activite.setUser(proprietaire);

        when(userActivityRepository.findById(activiteId)).thenReturn(Optional.of(activite));
        when(subscriptionRepository.findMySubscribers(eq(appelant), isNull(), eq(activiteId), any()))
            .thenReturn(Page.empty(page));

        assertThat(subscriptionService.listMySubscribers(appelant, null, activiteId, page))
            .isEmpty();
    }

    /** Un abonné arrivé par le profil ne désigne aucune activité. */
    @Test
    void abonneParLeProfil_doitAvoirUneCibleNulle() {
        UUID appelant = UUID.randomUUID();

        User abonne = new User();
        abonne.setId(UUID.randomUUID());
        abonne.setDisplayName("Bob");
        abonne.setAvatarUrl("https://example.test/bob.png");

        Subscription sub = Subscription.builder()
            .id(UUID.randomUUID())
            .type(SubscriptionType.AUTHOR)
            .subscriber(abonne)
            .build();

        when(subscriptionRepository.findMySubscribers(eq(appelant), isNull(), isNull(), any()))
            .thenReturn(new PageImpl<>(List.of(sub), page, 1));

        SubscriberDto dto = subscriptionService
            .listMySubscribers(appelant, null, null, page)
            .getContent().get(0);

        assertThat(dto.type()).isEqualTo("AUTHOR");
        assertThat(dto.targetId()).isNull();
        assertThat(dto.targetName()).isNull();
        assertThat(dto.displayName()).isEqualTo("Bob");
    }

    @Test
    void abonneParUneActivite_doitNommerLActivite() {
        UUID appelant = UUID.randomUUID();

        User abonne = new User();
        abonne.setId(UUID.randomUUID());
        abonne.setDisplayName("Ana");

        Activity referentiel = Activity.builder().id(UUID.randomUUID()).name("Course").build();
        UserActivity activite = new UserActivity();
        activite.setId(UUID.randomUUID());
        activite.setActivity(referentiel);

        Subscription sub = Subscription.builder()
            .id(UUID.randomUUID())
            .type(SubscriptionType.USER_ACTIVITY)
            .subscriber(abonne)
            .targetUserActivity(activite)
            .build();

        when(subscriptionRepository.findMySubscribers(eq(appelant), isNull(), isNull(), any()))
            .thenReturn(new PageImpl<>(List.of(sub), page, 1));

        SubscriberDto dto = subscriptionService
            .listMySubscribers(appelant, null, null, page)
            .getContent().get(0);

        assertThat(dto.targetId()).isEqualTo(activite.getId());
        assertThat(dto.targetName()).isEqualTo("Course");
    }
}
