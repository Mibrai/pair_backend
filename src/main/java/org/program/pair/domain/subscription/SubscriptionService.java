package org.program.pair.domain.subscription;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.subscription.dto.SubscriptionDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.CategoryRepository;
import org.program.pair.repository.SubscriptionRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final UserActivityRepository userActivityRepository;
    private final CategoryRepository categoryRepository;
    private final NotificationService notificationService;

    // --- CRUD abonnements ---

    public SubscriptionDto subscribeToAuthor(UUID subscriberId, UUID authorId) {
        if (subscriberId.equals(authorId)) {
            throw new ForbiddenException("Vous ne pouvez pas vous abonner à vous-même.");
        }
        if (subscriptionRepository.existsBySubscriberIdAndTargetAuthorId(subscriberId, authorId)) {
            throw new IllegalStateException("Vous êtes déjà abonné à cet utilisateur.");
        }

        User subscriber = userRepository.findById(subscriberId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));
        User author = userRepository.findById(authorId)
            .orElseThrow(() -> new ResourceNotFoundException("Auteur introuvable."));

        Subscription subscription = Subscription.builder()
            .subscriber(subscriber)
            .type(SubscriptionType.AUTHOR)
            .targetAuthor(author)
            .build();

        subscription = subscriptionRepository.save(subscription);

        notificationService.notify(authorId, NotificationType.NEW_FOLLOWER,
            NotificationPayload.empty()
                .with("subscriberId", subscriberId)
                .with("followerName", subscriber.getDisplayName())
                .build());

        return toDto(subscription);
    }

    public void unsubscribeFromAuthor(UUID subscriberId, UUID authorId) {
        Subscription subscription = subscriptionRepository
            .findBySubscriberIdAndTargetAuthorId(subscriberId, authorId)
            .orElseThrow(() -> new ResourceNotFoundException("Abonnement introuvable."));
        subscriptionRepository.delete(subscription);
    }

    public SubscriptionDto subscribeToUserActivity(UUID subscriberId, UUID userActivityId) {
        if (subscriptionRepository.existsBySubscriberIdAndTargetUserActivityId(subscriberId, userActivityId)) {
            throw new IllegalStateException("Vous êtes déjà abonné à cette activité.");
        }

        User subscriber = userRepository.findById(subscriberId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));
        UserActivity userActivity = userActivityRepository.findById(userActivityId)
            .orElseThrow(() -> new ResourceNotFoundException("Activité introuvable."));

        Subscription subscription = Subscription.builder()
            .subscriber(subscriber)
            .type(SubscriptionType.USER_ACTIVITY)
            .targetUserActivity(userActivity)
            .build();

        return toDto(subscriptionRepository.save(subscription));
    }

    public void unsubscribeFromUserActivity(UUID subscriberId, UUID userActivityId) {
        Subscription subscription = subscriptionRepository
            .findBySubscriberIdAndTargetUserActivityId(subscriberId, userActivityId)
            .orElseThrow(() -> new ResourceNotFoundException("Abonnement introuvable."));
        subscriptionRepository.delete(subscription);
    }

    public SubscriptionDto subscribeToCategory(UUID subscriberId, UUID categoryId) {
        if (subscriptionRepository.existsBySubscriberIdAndTargetCategoryId(subscriberId, categoryId)) {
            throw new IllegalStateException("Vous êtes déjà abonné à cette catégorie.");
        }

        User subscriber = userRepository.findById(subscriberId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));
        Category category = categoryRepository.findById(categoryId)
            .orElseThrow(() -> new ResourceNotFoundException("Catégorie introuvable."));

        Subscription subscription = Subscription.builder()
            .subscriber(subscriber)
            .type(SubscriptionType.CATEGORY)
            .targetCategory(category)
            .build();

        return toDto(subscriptionRepository.save(subscription));
    }

    public void unsubscribeFromCategory(UUID subscriberId, UUID categoryId) {
        Subscription subscription = subscriptionRepository
            .findBySubscriberIdAndTargetCategoryId(subscriberId, categoryId)
            .orElseThrow(() -> new ResourceNotFoundException("Abonnement introuvable."));
        subscriptionRepository.delete(subscription);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionDto> listMySubscriptions(UUID subscriberId) {
        return subscriptionRepository.findBySubscriberId(subscriberId).stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    // --- Fan-out de notifications ---

    public void notifySubscribersOfNewUserActivity(UserActivity userActivity) {
        UUID authorId = userActivity.getUser().getId();

        Map<String, Object> authorPayload = NotificationPayload.ofUserActivity(userActivity)
            .with("authorId", authorId)
            .with("authorName", userActivity.getUser().getDisplayName())
            .build();
        subscriptionRepository.findByTargetAuthorId(authorId).forEach(sub ->
            notificationService.notify(sub.getSubscriber().getId(),
                NotificationType.AUTHOR_NEW_ACTIVITY, authorPayload));

        UUID categoryId = userActivity.getActivity().getCategory().getId();
        Map<String, Object> categoryPayload = NotificationPayload.ofUserActivity(userActivity).build();
        subscriptionRepository.findByTargetCategoryId(categoryId).forEach(sub ->
            notificationService.notify(sub.getSubscriber().getId(),
                NotificationType.CATEGORY_NEW_ACTIVITY, categoryPayload));
    }

    public void notifySubscribersOfUserActivityUpdate(UserActivity userActivity) {
        Map<String, Object> payload = NotificationPayload.ofUserActivity(userActivity).build();
        subscriptionRepository.findByTargetUserActivityId(userActivity.getId()).forEach(sub ->
            notificationService.notify(sub.getSubscriber().getId(),
                NotificationType.ACTIVITY_UPDATED, payload));
    }

    /**
     * Annonce un nouveau programme à ses abonnés, <b>situé à son premier
     * créneau</b>.
     *
     * <p>Le paramètre est le créneau, pas le programme : {@code AUTHOR_NEW_PROGRAM}
     * et {@code ACTIVITY_NEW_PROGRAM} portent sur un programme mais doivent
     * annoncer une séance — date, lieu, et de quoi décompter jusqu'à elle. C'est
     * {@code ofSchedule} qui porte {@code scheduleId}, {@code sessionAt},
     * {@code placeName} et {@code endsAt} ; {@code ofProgram} ne les a jamais eus,
     * et l'annonce partait sans eux.
     *
     * <p>D'où l'appelant : {@code ProgramService.addSchedule} au premier créneau,
     * et non {@code createProgram} — un programme naît en brouillon et sans
     * créneau, il n'y avait donc rien à situer au moment où l'annonce partait.
     *
     * <p>{@code ofSchedule} rapatrie tout le contexte du programme (titre,
     * activité, catégorie, auteur avec le repli {@code organizerName} →
     * {@code displayName}) : rien à reposer ici, et le reposer ferait diverger le
     * nom de l'auteur d'avec celui de la fiche du programme.
     *
     * <p>Un seul payload pour les deux types : il est identique, et
     * {@code build()} rend une carte non modifiable — deux constructions
     * donneraient deux copies du même contenu.
     */
    public void notifySubscribersOfNewProgram(Schedule firstSlot) {
        Program program = firstSlot.getProgram();
        UserActivity userActivity = program.getUserActivity();
        UUID authorId = userActivity.getUser().getId();

        Map<String, Object> payload = NotificationPayload.ofSchedule(firstSlot).build();

        subscriptionRepository.findByTargetAuthorId(authorId).forEach(sub ->
            notificationService.notify(sub.getSubscriber().getId(),
                NotificationType.AUTHOR_NEW_PROGRAM, payload));

        subscriptionRepository.findByTargetUserActivityId(userActivity.getId()).forEach(sub ->
            notificationService.notify(sub.getSubscriber().getId(),
                NotificationType.ACTIVITY_NEW_PROGRAM, payload));
    }

    private SubscriptionDto toDto(Subscription s) {
        return new SubscriptionDto(
            s.getId(),
            s.getType().name(),
            s.getTargetAuthor() != null ? s.getTargetAuthor().getId() : null,
            s.getTargetAuthor() != null ? s.getTargetAuthor().getDisplayName() : null,
            s.getTargetUserActivity() != null ? s.getTargetUserActivity().getId() : null,
            s.getTargetUserActivity() != null ? s.getTargetUserActivity().getActivity().getName() : null,
            s.getTargetCategory() != null ? s.getTargetCategory().getId() : null,
            s.getTargetCategory() != null ? s.getTargetCategory().getName() : null,
            s.getCreatedAt()
        );
    }
}
