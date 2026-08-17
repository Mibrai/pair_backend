package org.program.pair.domain.subscription;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.subscription.dto.SubscriberDto;
import org.program.pair.domain.subscription.dto.SubscriptionDto;
import org.program.pair.domain.subscription.dto.SubscriptionScopeRequest;
import org.program.pair.domain.subscription.dto.UpdateSubscriptionRequest;
import org.program.pair.domain.user.PrivacySettings;
import org.program.pair.domain.user.SubscriptionPermission;
import org.program.pair.domain.user.User;
import org.program.pair.repository.CategoryRepository;
import org.program.pair.repository.SubscriptionRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ConflictException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
            throw alreadySubscribed("Vous êtes déjà abonné à cet utilisateur.");
        }

        User subscriber = userRepository.findById(subscriberId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));
        User author = userRepository.findById(authorId)
            .orElseThrow(() -> new ResourceNotFoundException("Auteur introuvable."));

        requireOpenToSubscriptions(author);

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
        subscriptionRepository
            .findBySubscriberIdAndTargetAuthorId(subscriberId, authorId)
            .ifPresent(subscriptionRepository::delete);
    }

    public SubscriptionDto subscribeToUserActivity(UUID subscriberId, UUID userActivityId) {
        if (subscriptionRepository.existsBySubscriberIdAndTargetUserActivityId(subscriberId, userActivityId)) {
            throw alreadySubscribed("Vous êtes déjà abonné à cette activité.");
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
        subscriptionRepository
            .findBySubscriberIdAndTargetUserActivityId(subscriberId, userActivityId)
            .ifPresent(subscriptionRepository::delete);
    }

    /**
     * Abonnement à une catégorie, avec une portée géographique facultative.
     *
     * <p>Les catégories sont un référentiel <b>mondial</b> : sans portée, un
     * abonnement « yoga » notifie un Parisien d'une séance créée à Berlin. Le
     * corps absent conserve ce comportement — c'est celui d'avant, et le retirer
     * changerait le sens d'abonnements existants.
     */
    public SubscriptionDto subscribeToCategory(UUID subscriberId, UUID categoryId,
                                               SubscriptionScopeRequest scope) {
        if (subscriptionRepository.existsBySubscriberIdAndTargetCategoryId(subscriberId, categoryId)) {
            throw alreadySubscribed("Vous êtes déjà abonné à cette catégorie.");
        }

        User subscriber = userRepository.findById(subscriberId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));
        Category category = categoryRepository.findById(categoryId)
            .orElseThrow(() -> new ResourceNotFoundException("Catégorie introuvable."));

        Subscription.SubscriptionBuilder builder = Subscription.builder()
            .subscriber(subscriber)
            .type(SubscriptionType.CATEGORY)
            .targetCategory(category);

        if (scope != null && !scope.isEmpty()) {
            requireCompleteScope(scope);
            builder.lat(scope.lat()).lng(scope.lng()).radiusMeters(scope.radiusMeters());
        }

        return toDto(subscriptionRepository.save(builder.build()));
    }

    public void unsubscribeFromCategory(UUID subscriberId, UUID categoryId) {
        subscriptionRepository
            .findBySubscriberIdAndTargetCategoryId(subscriberId, categoryId)
            .ifPresent(subscriptionRepository::delete);
    }

    /**
     * Mes abonnements, paginés.
     *
     * <p>La route rendait tout d'un coup. L'enveloppe {@code Page} est une
     * rupture de contrat assumée, et elle n'était sûre qu'une fois
     * {@code subscribed} servi sur les DTO de cible (lot A) : livrée avant, elle
     * aurait fait basculer à tort sur « S'abonner » tous les boutons dont la
     * cible ne figurait pas dans la première page.
     *
     * @param type filtre facultatif ; {@code null} rend les trois types
     */
    @Transactional(readOnly = true)
    public Page<SubscriptionDto> listMySubscriptions(UUID subscriberId, SubscriptionType type,
                                                     Pageable pageable) {
        return subscriptionRepository.findMySubscriptions(subscriberId, type, pageable)
            .map(this::toDto);
    }

    /**
     * Mes abonnés — les personnes qui me suivent, moi ou l'une de mes activités.
     *
     * <p><b>C'est la liste de l'appelant, et de personne d'autre.</b> Aucune
     * route ne permet de savoir qui suit un tiers : le paramètre
     * {@code targetId} ne desserre pas cette règle, il la resserre.
     *
     * @param type     filtre facultatif sur le chemin d'arrivée
     * @param targetId activité précise ; l'appelant doit en être l'auteur
     */
    @Transactional(readOnly = true)
    public Page<SubscriberDto> listMySubscribers(UUID ownerId, SubscriptionType type,
                                                 UUID targetId, Pageable pageable) {
        requireListableType(type);
        requireOwnedTarget(ownerId, targetId);

        return subscriptionRepository.findMySubscribers(ownerId, type, targetId, pageable)
            .map(this::toSubscriberDto);
    }

    /**
     * Les abonnés d'une catégorie ne se listent pas, par personne.
     *
     * <p>La demande client mentionne {@code CATEGORY} parmi les valeurs du filtre,
     * mais son propre chapitre sur la confidentialité interdit exactement cette
     * exposition : « suivre une catégorie n'est pas un acte neutre — selon le
     * référentiel, c'est une donnée de santé ou de situation personnelle ». Les
     * deux paragraphes se contredisent, et c'est la confidentialité qui gagne.
     *
     * <p>S'y ajoute un fait de modèle : {@code Category} ne porte ni
     * propriétaire ni créateur, c'est un référentiel partagé. Aucune catégorie
     * n'appartenant à personne, il n'existe personne à qui cette liste pourrait
     * légitimement revenir.
     *
     * <p>Un refus plutôt qu'une page vide : une page vide répondrait « vous
     * n'avez aucun abonné par catégorie » à une question qui n'a de réponse pour
     * personne.
     */
    private void requireListableType(SubscriptionType type) {
        if (type == SubscriptionType.CATEGORY) {
            throw new ForbiddenException(
                "Les abonnés d'une catégorie ne sont listables par personne : "
                    + "une catégorie n'appartient à aucun utilisateur.");
        }
    }

    private void requireOwnedTarget(UUID ownerId, UUID targetId) {
        if (targetId == null) {
            return;
        }
        UserActivity target = userActivityRepository.findById(targetId)
            .orElseThrow(() -> new ResourceNotFoundException("Activité introuvable."));
        if (target.getUser() == null || !ownerId.equals(target.getUser().getId())) {
            throw new ForbiddenException(
                "Vous ne pouvez lister que les abonnés de vos propres activités.");
        }
    }

    private SubscriberDto toSubscriberDto(Subscription s) {
        User subscriber = s.getSubscriber();
        UserActivity target = s.getTargetUserActivity();
        return new SubscriberDto(
            subscriber.getId(),
            subscriber.getDisplayName(),
            subscriber.getAvatarUrl(),
            s.getType().name(),
            target != null ? target.getId() : null,
            target != null && target.getActivity() != null ? target.getActivity().getName() : null,
            s.getCreatedAt()
        );
    }

    // --- Réglage d'un abonnement existant ---

    public SubscriptionDto updateAuthorSubscription(UUID subscriberId, UUID authorId,
                                                    UpdateSubscriptionRequest request) {
        return applyUpdate(subscriptionRepository
            .findBySubscriberIdAndTargetAuthorId(subscriberId, authorId)
            .orElseThrow(() -> noSubscription("cet utilisateur")), request);
    }

    public SubscriptionDto updateUserActivitySubscription(UUID subscriberId, UUID userActivityId,
                                                          UpdateSubscriptionRequest request) {
        return applyUpdate(subscriptionRepository
            .findBySubscriberIdAndTargetUserActivityId(subscriberId, userActivityId)
            .orElseThrow(() -> noSubscription("cette activité")), request);
    }

    public SubscriptionDto updateCategorySubscription(UUID subscriberId, UUID categoryId,
                                                      UpdateSubscriptionRequest request) {
        return applyUpdate(subscriptionRepository
            .findBySubscriberIdAndTargetCategoryId(subscriberId, categoryId)
            .orElseThrow(() -> noSubscription("cette catégorie")), request);
    }

    /**
     * Modification partielle : seuls les champs présents sont appliqués.
     *
     * <p>La portée fait exception à la règle du « champ par champ » — elle
     * s'applique en bloc, parce qu'un centre sans rayon ne décrit rien. Et son
     * retrait passe par {@code clearScope} plutôt que par trois {@code null} :
     * en JSON, un champ absent et un champ nul arrivent tous deux à
     * {@code null}, et un {@code PATCH} qui remplacerait la portée en bloc à
     * chaque appel ferait qu'un simple changement de niveau efface
     * silencieusement un rayon réglé.
     */
    private SubscriptionDto applyUpdate(Subscription subscription, UpdateSubscriptionRequest request) {
        if (request.clearsScope() && request.mentionsScope()) {
            throw new ValidationException(
                "clearScope et lat/lng/radiusMeters ne peuvent pas être demandés ensemble.");
        }

        if (request.level() != null) {
            subscription.setLevel(SubscriptionLevel.valueOf(request.level()));
        }

        if (request.clearsScope()) {
            subscription.setLat(null);
            subscription.setLng(null);
            subscription.setRadiusMeters(null);
        } else if (request.mentionsScope()) {
            requireCategoryScope(subscription);
            if (!request.setsScope()) {
                throw new ValidationException(
                    "lat, lng et radiusMeters vont ensemble : les trois sont requis pour "
                        + "poser une portée, ou clearScope pour la retirer.");
            }
            subscription.setLat(request.lat());
            subscription.setLng(request.lng());
            subscription.setRadiusMeters(request.radiusMeters());
        }

        return toDto(subscriptionRepository.save(subscription));
    }

    // --- Compteurs et état d'abonnement, pour les DTO de cible ---
    //
    // Deux requêtes bornées à la page, jamais une par entrée : le coût est
    // constant quelle que soit la taille du catalogue.

    @Transactional(readOnly = true)
    public long countAuthorSubscribers(UUID authorId) {
        return subscriptionRepository.countByTargetAuthorId(authorId);
    }

    @Transactional(readOnly = true)
    public boolean isSubscribedToAuthor(UUID subscriberId, UUID authorId) {
        return subscriberId != null
            && subscriptionRepository.existsBySubscriberIdAndTargetAuthorId(subscriberId, authorId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Long> countUserActivitySubscribers(Collection<UUID> userActivityIds) {
        return toCountMap(userActivityIds, subscriptionRepository::countByTargetUserActivityIds);
    }

    @Transactional(readOnly = true)
    public Set<UUID> subscribedUserActivityIds(UUID subscriberId, Collection<UUID> userActivityIds) {
        if (subscriberId == null || userActivityIds == null || userActivityIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(
            subscriptionRepository.findSubscribedUserActivityIds(subscriberId, userActivityIds));
    }

    @Transactional(readOnly = true)
    public Map<UUID, Long> countAuthorSubscribers(Collection<UUID> authorIds) {
        return toCountMap(authorIds, subscriptionRepository::countByTargetAuthorIds);
    }

    @Transactional(readOnly = true)
    public Set<UUID> subscribedAuthorIds(UUID subscriberId, Collection<UUID> authorIds) {
        if (subscriberId == null || authorIds == null || authorIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(subscriptionRepository.findSubscribedAuthorIds(subscriberId, authorIds));
    }

    /** Compteurs de toutes les catégories : le référentiel est court et rendu en entier. */
    @Transactional(readOnly = true)
    public Map<UUID, Long> countAllCategorySubscribers() {
        return asCountMap(subscriptionRepository.countAllByTargetCategory());
    }

    @Transactional(readOnly = true)
    public Set<UUID> subscribedCategoryIds(UUID subscriberId) {
        if (subscriberId == null) {
            return Set.of();
        }
        return new HashSet<>(subscriptionRepository.findSubscribedCategoryIds(subscriberId));
    }

    private Map<UUID, Long> toCountMap(Collection<UUID> ids,
                                       Function<Collection<UUID>, List<Object[]>> query) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return asCountMap(query.apply(ids));
    }

    private Map<UUID, Long> asCountMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(
            row -> (UUID) row[0],
            row -> (Long) row[1]));
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

    // --- Refus nommés ---

    /**
     * {@code 409 ALREADY_SUBSCRIBED} plutôt que le {@code CONFLICT} générique :
     * le client le traite comme un succès — l'état voulu est en base — et
     * stabilise l'affichage sur « Abonné » sans message d'erreur. Un code partagé
     * avec tous les autres conflits de l'API ne permettait pas cette distinction.
     */
    private ConflictException alreadySubscribed(String message) {
        return new ConflictException(ErrorCode.ALREADY_SUBSCRIBED, message);
    }

    private ResourceNotFoundException noSubscription(String target) {
        return new ResourceNotFoundException("Vous n'êtes pas abonné à " + target + ".");
    }

    private void requireOpenToSubscriptions(User author) {
        PrivacySettings settings = author.getPrivacySettings();
        if (settings != null && settings.getAllowSubscriptions() == SubscriptionPermission.NOBODY) {
            throw new ForbiddenException(ErrorCode.SUBSCRIPTIONS_NOT_ALLOWED,
                "Cette personne n'accepte pas de nouveaux abonnés.");
        }
    }

    private void requireCompleteScope(SubscriptionScopeRequest scope) {
        if (!scope.isComplete()) {
            throw new ValidationException(
                "lat, lng et radiusMeters vont ensemble : les trois sont requis, ou aucun.");
        }
    }

    private void requireCategoryScope(Subscription subscription) {
        if (subscription.getType() != SubscriptionType.CATEGORY) {
            throw new ValidationException(
                "Une portée géographique ne s'applique qu'aux abonnements de type CATEGORY.");
        }
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
            s.getLevel() != null ? s.getLevel().name() : SubscriptionLevel.ALL.name(),
            s.getLat(),
            s.getLng(),
            s.getRadiusMeters(),
            s.getCreatedAt()
        );
    }
}
