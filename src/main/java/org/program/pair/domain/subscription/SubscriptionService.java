package org.program.pair.domain.subscription;

import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Point;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.LocationType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.subscription.dto.SubscriberDto;
import org.program.pair.domain.subscription.dto.SubscriptionDto;
import org.program.pair.domain.subscription.dto.SubscriptionScopeRequest;
import org.program.pair.domain.subscription.dto.UpdateSubscriptionRequest;
import org.program.pair.domain.user.PrivacySettings;
import org.program.pair.domain.user.SubscriptionPermission;
import org.program.pair.domain.block.BlockFilterService;
import org.program.pair.domain.user.User;
import org.program.pair.repository.CategoryRepository;
import org.program.pair.repository.SubscriptionRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.GeoUtils;
import org.program.pair.shared.exception.ConflictException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final BlockFilterService blockFilterService;
    private final UserRepository userRepository;
    private final UserActivityRepository userActivityRepository;
    private final CategoryRepository categoryRepository;
    private final NotificationService notificationService;

    // --- CRUD abonnements ---

    public SubscriptionDto subscribeToAuthor(UUID subscriberId, UUID authorId) {
        requireNotSelf(subscriberId, authorId);
        if (subscriptionRepository.existsBySubscriberIdAndTargetAuthorId(subscriberId, authorId)) {
            throw alreadySubscribed("Vous êtes déjà abonné à cet utilisateur.");
        }

        User subscriber = userRepository.findById(subscriberId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));
        User author = userRepository.findById(authorId)
            .orElseThrow(() -> new ResourceNotFoundException("Auteur introuvable."));

        requireNotBlocked(subscriberId, author.getId());
        requireOpenToSubscriptions(author);

        Subscription subscription = Subscription.builder()
            .subscriber(subscriber)
            .type(SubscriptionType.AUTHOR)
            .targetAuthor(author)
            .build();

        subscription = subscriptionRepository.save(subscription);

        notificationService.notify(authorId, subscriberId, NotificationType.NEW_FOLLOWER,
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

    /**
     * Abonnement à l'activité d'une personne.
     *
     * <p>Les deux refus qui suivent le chargement portent sur <b>l'auteur de
     * l'activité</b>, et non sur l'activité elle-même : suivre ce que quelqu'un
     * propose, c'est le suivre. Sans eux, « qui peut me suivre » se contournait
     * par n'importe laquelle des activités de la personne, et l'on pouvait
     * s'abonner à soi-même par un chemin détourné.
     */
    public SubscriptionDto subscribeToUserActivity(UUID subscriberId, UUID userActivityId) {
        if (subscriptionRepository.existsBySubscriberIdAndTargetUserActivityId(subscriberId, userActivityId)) {
            throw alreadySubscribed("Vous êtes déjà abonné à cette activité.");
        }

        User subscriber = userRepository.findById(subscriberId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));
        UserActivity userActivity = userActivityRepository.findById(userActivityId)
            .orElseThrow(() -> new ResourceNotFoundException("Activité introuvable."));

        User author = userActivity.getUser();
        if (author != null) {
            requireNotSelf(subscriberId, author.getId());
            requireNotBlocked(subscriberId, author.getId());
            requireOpenToSubscriptions(author);
        }

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
    //
    // Un fait, une notification par destinataire. Trois règles s'appliquent
    // dans cet ordre, et l'ordre est le fond du sujet :
    //
    //   1. le NIVEAU de chaque abonnement décide si un envoi existe ;
    //   2. la PORTÉE géographique écarte ce qui est hors zone ;
    //   3. la DÉDUPLICATION ne garde, par destinataire, que l'envoi le plus
    //      délibéré.
    //
    // Filtrer avant de dédupliquer, jamais l'inverse : quelqu'un dont
    // l'abonnement à l'auteur est en sourdine mais dont l'abonnement à
    // l'activité est actif doit recevoir l'annonce par l'activité. Dédupliquer
    // d'abord ferait gagner la branche prioritaire, qui se tairait ensuite — et
    // la personne ne recevrait rien, alors qu'elle avait demandé à savoir.

    /**
     * Un envoi possible : qui le reçoit, par quel abonnement, et sous quel type.
     *
     * <p>{@code source} peut être nul : la proximité géographique n'est pas un
     * abonnement, un candidat {@code NEARBY_PROGRAM} n'a donc aucune ligne à
     * nommer. La provenance est alors simplement absente du payload.
     */
    private record Candidate(UUID recipientId, Subscription source, NotificationType type) {}

    /**
     * Qui gagne quand un même fait atteint deux fois la même personne.
     *
     * <p>Plus le lien est délibéré, plus il doit gagner : suivre une personne
     * est un acte, suivre l'une de ses activités en est un autre, être
     * géographiquement à proximité n'en est pas un. C'est la raison de cet
     * ordre, et elle mérite d'être écrite : un lecteur futur l'inverserait par
     * bon sens apparent — « le plus précis d'abord » — et personne ne verrait la
     * régression, puisque le nombre de notifications ne changerait pas.
     */
    private static final List<NotificationType> EMISSION_PRIORITY = List.of(
        NotificationType.AUTHOR_NEW_PROGRAM,
        NotificationType.ACTIVITY_NEW_PROGRAM,
        NotificationType.NEARBY_PROGRAM,
        NotificationType.AUTHOR_NEW_ACTIVITY,
        NotificationType.CATEGORY_NEW_ACTIVITY);

    /**
     * Annonce une activité fraîchement déclarée aux abonnés de son auteur.
     *
     * <p>Les abonnés de la <b>catégorie</b> ne sont pas prévenus ici : à sa
     * création une activité n'a aucun créneau, donc aucune position, et leur
     * rayon n'aurait rien à filtrer. Voir
     * {@link #notifyCategorySubscribersIfFirstLocatedSlot(Schedule)}.
     */
    public void notifySubscribersOfNewUserActivity(UserActivity userActivity) {
        UUID authorId = userActivity.getUser().getId();

        List<Candidate> candidates = new ArrayList<>();
        collect(candidates, subscriptionRepository.findByTargetAuthorId(authorId),
            NotificationType.AUTHOR_NEW_ACTIVITY);

        emit(candidates, userActivity.getUser().getId(), activityContext(userActivity));
    }

    /**
     * Annonce l'activité aux abonnés de sa catégorie, au premier créneau
     * localisé — le premier instant où elle est quelque part.
     *
     * <p>Une activité n'a pas de position propre : elle emprunte celle de ses
     * créneaux. Annoncée à sa création, elle n'était nulle part, et le rayon des
     * abonnements {@code CATEGORY} ne pouvait rien écarter — la règle « pas de
     * coordonnée, on notifie toujours » aurait été vraie à chaque fois.
     *
     * <p>{@code categoryNotifiedAt} porte l'unicité, et non un décompte : le
     * premier créneau supprimé puis reposé ferait du suivant « le premier » une
     * seconde fois.
     *
     * <p>Conséquence assumée : une activité qui n'obtient jamais de programme
     * n'atteint jamais les abonnés de sa catégorie. C'est cohérent avec ce
     * qu'ils demandent — être prévenus de ce qui se passe près d'eux, et une
     * activité sans séance n'est pas quelque chose à quoi se rendre.
     */
    public void notifyCategorySubscribersIfFirstLocatedSlot(Schedule firstSlot) {
        Program program = firstSlot.getProgram();
        if (program == null || program.getUserActivity() == null) {
            return;
        }
        UserActivity userActivity = program.getUserActivity();
        if (userActivity.getCategoryNotifiedAt() != null) {
            return;
        }
        Activity activity = userActivity.getActivity();
        if (activity == null || activity.getCategory() == null) {
            return;
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Subscription sub : subscriptionRepository
                .findByTargetCategoryId(activity.getCategory().getId())) {
            if (allows(sub, NotificationType.CATEGORY_NEW_ACTIVITY)
                    && withinScope(sub, program, firstSlot)) {
                candidates.add(new Candidate(sub.getSubscriber().getId(), sub,
                    NotificationType.CATEGORY_NEW_ACTIVITY));
            }
        }

        emit(candidates, userActivity.getUser().getId(), activityContext(userActivity));

        userActivity.setCategoryNotifiedAt(Instant.now());
        userActivityRepository.save(userActivity);
    }

    /**
     * Annonce une modification d'activité à ses abonnés.
     *
     * <p>Seul type retenu par {@code NEW_ONLY} : c'est une mise à jour, pas une
     * création. Aucune déduplication à faire — un seul abonnement peut le
     * produire — mais l'envoi passe par le même chemin, pour que la provenance
     * y soit posée de la même façon.
     */
    public void notifySubscribersOfUserActivityUpdate(UserActivity userActivity) {
        List<Candidate> candidates = new ArrayList<>();
        collect(candidates, subscriptionRepository.findByTargetUserActivityId(userActivity.getId()),
            NotificationType.ACTIVITY_UPDATED);

        emit(candidates, userActivity.getUser().getId(),
            NotificationPayload.ofUserActivity(userActivity).build());
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
     * <p>Un seul contexte pour les deux types : il est identique. Seule la
     * provenance en diffère, et elle se pose par destinataire au dernier moment
     * — voir {@link #withProvenance(Map, Subscription)}.
     *
     * <p>Un abonné à la fois de l'auteur et de l'activité ne reçoit qu'une
     * annonce, celle de l'auteur : voir {@link #EMISSION_PRIORITY}.
     */
    public void notifySubscribersOfNewProgram(Schedule firstSlot) {
        Program program = firstSlot.getProgram();
        UserActivity userActivity = program.getUserActivity();
        UUID authorId = userActivity.getUser().getId();

        List<Candidate> candidates = new ArrayList<>();
        collect(candidates, subscriptionRepository.findByTargetAuthorId(authorId),
            NotificationType.AUTHOR_NEW_PROGRAM);
        collect(candidates, subscriptionRepository.findByTargetUserActivityId(userActivity.getId()),
            NotificationType.ACTIVITY_NEW_PROGRAM);

        emit(candidates, authorId, NotificationPayload.ofSchedule(firstSlot).build());
    }

    /** Contexte commun aux deux annonces d'activité, auteur compris. */
    private Map<String, Object> activityContext(UserActivity userActivity) {
        NotificationPayload payload = NotificationPayload.ofUserActivity(userActivity);
        User author = userActivity.getUser();
        if (author != null) {
            payload.with("authorId", author.getId())
                .with("authorName", author.getDisplayName());
        }
        return payload.build();
    }

    /** Retient les abonnements que leur niveau autorise, sous le type donné. */
    private void collect(List<Candidate> candidates, List<Subscription> subscriptions,
                         NotificationType type) {
        for (Subscription sub : subscriptions) {
            if (allows(sub, type)) {
                candidates.add(new Candidate(sub.getSubscriber().getId(), sub, type));
            }
        }
    }

    /**
     * Le niveau décide si l'envoi existe.
     *
     * <p>{@code MUTED} ne coupe que l'émission : la ligne reste, la cible reste
     * dans « mes abonnements », et {@code subscribed} vaut toujours vrai. C'est
     * la soupape qui évite le désabonnement.
     *
     * <p>Niveau nul traité comme {@code ALL} : la colonne est non nulle en base
     * depuis la V58, mais un objet construit en mémoire peut ne pas l'être, et
     * une notification tue par accident ne se remarque pas.
     */
    private boolean allows(Subscription subscription, NotificationType type) {
        SubscriptionLevel level = subscription.getLevel();
        if (level == SubscriptionLevel.MUTED) {
            return false;
        }
        if (level == SubscriptionLevel.NEW_ONLY) {
            return type != NotificationType.ACTIVITY_UPDATED;
        }
        return true;
    }

    /**
     * La portée géographique écarte ce qui est hors zone — et rien d'autre.
     *
     * <p>Deux entrées passent toujours, quel que soit le rayon :
     * <ul>
     *   <li>l'abonnement <b>sans portée</b>, qui est le comportement d'avant et
     *       celui de toutes les lignes antérieures à la V58 ;</li>
     *   <li>l'activité <b>à distance</b> ({@code REMOTE}, {@code ONLINE}) ou sans
     *       coordonnée. C'est déjà la règle de l'Explorer : ces entrées ne sont
     *       pas filtrées par la distance, elles sont reléguées en fin de tri. Les
     *       exclure ici serait incohérent avec ce que l'utilisateur voit
     *       ailleurs — et un filtre qui écarte ce qui n'a pas de géographie
     *       n'est pas un filtre, c'est une perte.</li>
     * </ul>
     *
     * <p>La distance est évaluée <b>au moment de l'émission</b> et ne se rejoue
     * pas si l'activité déménage ensuite : une notification est un fait daté, et
     * la rejouer contre un état ultérieur produirait des annonces sans
     * événement.
     */
    private boolean withinScope(Subscription subscription, Program program, Schedule slot) {
        if (!subscription.hasScope()) {
            return true;
        }
        LocationType locationType = program.getLocationType();
        if (locationType == LocationType.REMOTE || locationType == LocationType.ONLINE) {
            return true;
        }
        Point location = slot.getLocation();
        if (location == null) {
            return true;
        }
        double distance = GeoUtils.haversineMeters(
            subscription.getLat(), subscription.getLng(),
            location.getY(), location.getX());
        return distance <= subscription.getRadiusMeters();
    }

    /**
     * Déduplique par destinataire, puis émet.
     *
     * <p>La clé est le seul destinataire : les candidats d'un même appel
     * décrivent tous <b>le même fait</b> — un programme, ou une activité — donc
     * les réunir par personne suffit à n'en garder qu'un par fait.
     */
    private void emit(List<Candidate> candidates, UUID authorId, Map<String, Object> context) {
        Map<UUID, Candidate> retained = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            retained.merge(candidate.recipientId(), candidate,
                (kept, other) -> rank(kept.type()) <= rank(other.type()) ? kept : other);
        }
        // L'acteur est l'auteur de ce qui est annoncé. Le filtre est indispensable
        // même après la rupture des abonnements au blocage : un abonnement par
        // catégorie survit au blocage — il ne vise personne — et porterait sinon
        // les annonces de quelqu'un qu'on vient de bloquer.
        retained.values().forEach(candidate ->
            notificationService.notify(candidate.recipientId(), authorId, candidate.type(),
                withProvenance(context, candidate.source())));
    }

    private static int rank(NotificationType type) {
        int index = EMISSION_PRIORITY.indexOf(type);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /**
     * Dit de quel abonnement vient cet envoi.
     *
     * <p>Sans ces trois clés, l'utilisateur ne peut ni comprendre ni couper la
     * source : trois abonnements différents produisent le même texte. Le client
     * en tire une ligne « Vous suivez Lena Müller » et un appui long qui met en
     * sourdine — geste qui n'a de sens que parce que {@code subscriptionId}
     * désigne <b>la ligne qui a gagné la déduplication</b>, c'est-à-dire celle
     * qui est nommée à l'écran.
     *
     * <p>Le libellé est <b>copié</b>, jamais relu : une notification doit se
     * rendre entière hors ligne, et doit dire ce qu'elle disait le jour où elle
     * est partie. Une cible renommée laisse donc d'anciennes notifications au
     * nom d'avant, et c'est voulu.
     */
    private Map<String, Object> withProvenance(Map<String, Object> context, Subscription source) {
        if (source == null) {
            return context;
        }
        return NotificationPayload.from(context)
            .with("subscriptionId", source.getId())
            .with("subscriptionType", source.getType())
            .with("subscriptionLabel", labelOf(source))
            .build();
    }

    private String labelOf(Subscription subscription) {
        return switch (subscription.getType()) {
            case AUTHOR -> subscription.getTargetAuthor() != null
                ? subscription.getTargetAuthor().getDisplayName() : null;
            case USER_ACTIVITY -> subscription.getTargetUserActivity() != null
                    && subscription.getTargetUserActivity().getActivity() != null
                ? subscription.getTargetUserActivity().getActivity().getName() : null;
            case CATEGORY -> subscription.getTargetCategory() != null
                ? subscription.getTargetCategory().getName() : null;
        };
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

    /**
     * On ne se suit pas soi-même — ni par le profil, ni par une activité.
     *
     * <p>La contrainte {@code chk_subscription_not_self} de la V36 ne couvre que
     * la cible {@code AUTHOR} : dire la même chose d'une activité supposerait de
     * joindre {@code user_activities} pour lire son propriétaire, ce qu'un
     * {@code CHECK} ne sait pas faire. Le garde-fou est donc ici, et seulement
     * ici — d'où l'importance de le traverser sur les deux chemins.
     */
    private void requireNotSelf(UUID subscriberId, UUID authorId) {
        if (subscriberId.equals(authorId)) {
            throw new ForbiddenException("Vous ne pouvez pas vous abonner à vous-même.");
        }
    }

    /**
     * Le réglage « qui peut me suivre » de l'auteur visé.
     *
     * <p>Il vaut pour <b>les deux chemins</b> qui mènent à une personne : son
     * profil, et chacune de ses activités. Réservé au seul profil, il se
     * contournait par n'importe laquelle de ses activités — et l'abonné ainsi
     * arrivé recevait bien ses nouveaux programmes, ce qui vidait le réglage de
     * son sens tout en le laissant afficher « fermé ».
     *
     * <p>Ne s'applique pas aux catégories : elles n'appartiennent à personne, et
     * s'abonner à « Yoga » n'est pas suivre quelqu'un.
     *
     * <p>Rappel : le réglage ne vaut que pour l'avenir. Il refuse les nouveaux
     * abonnements, il ne supprime pas les existants et ne les fait pas taire.
     */
    /**
     * Refuse un abonnement entre deux personnes que le blocage a séparées.
     *
     * <p>Posée avant {@link #requireOpenToSubscriptions} : ce dernier rend un
     * code nommé qui apprendrait à une personne bloquée que le compte visé
     * existe et va bien. Elle reçoit donc, ici, le refus d'une ressource
     * introuvable.
     */
    private void requireNotBlocked(UUID subscriberId, UUID authorId) {
        if (blockFilterService.blockedBy(subscriberId, authorId)) {
            throw new ForbiddenException(ErrorCode.USER_BLOCKED,
                "Vous avez bloqué cette personne.");
        }
        if (blockFilterService.blocked(subscriberId, authorId)) {
            throw new ResourceNotFoundException("Auteur introuvable.");
        }
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
