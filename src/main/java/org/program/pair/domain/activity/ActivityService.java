package org.program.pair.domain.activity;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.dto.*;
import org.program.pair.domain.subscription.SubscriptionService;
import org.program.pair.domain.user.User;
import org.program.pair.repository.*;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ActivityService {

    private final CategoryRepository categoryRepository;
    private final ActivityRepository activityRepository;
    private final UserActivityRepository userActivityRepository;
    private final ProgramRepository programRepository;
    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;
    private final HtmlSanitizer sanitizer;

    /**
     * Le référentiel des catégories, avec compteur d'abonnés et état de
     * l'appelant.
     *
     * <p>Deux requêtes pour tout le référentiel, pas deux par catégorie : il est
     * court et rendu en entier, borner par une liste d'identifiants coûterait un
     * paramètre sans rien économiser.
     *
     * @param requesterId appelant, ou {@code null} — la route est publique, et
     *                    {@code subscribed} vaut alors {@code false} faute
     *                    d'identité
     */
    @Transactional(readOnly = true)
    public List<CategoryDto> getAllCategories(UUID requesterId) {
        Map<UUID, Long> subscriberCounts = subscriptionService.countAllCategorySubscribers();
        Set<UUID> subscribedTo = subscriptionService.subscribedCategoryIds(requesterId);

        return categoryRepository.findAll().stream()
            .map(category -> new CategoryDto(
                category.getId(),
                category.getName(),
                category.getIcon(),
                category.getColorRamp(),
                subscriberCounts.getOrDefault(category.getId(), 0L),
                subscribedTo.contains(category.getId())))
            .collect(Collectors.toList());
    }

    public ActivityDto createActivity(CreateActivityRequest request) {
        String name = request.name().strip();

        Category category = categoryRepository.findById(request.categoryId())
            .orElseThrow(() -> new ResourceNotFoundException("Catégorie introuvable."));

        if (activityRepository.existsByCategoryIdAndNameIgnoreCase(category.getId(), name)) {
            throw new IllegalStateException(
                "L'activité \"" + name + "\" existe déjà dans cette catégorie.");
        }

        String slug = generateUniqueSlug(name);

        Activity activity = Activity.builder()
            .category(category)
            .name(name)
            .slug(slug)
            .build();

        return toActivityDto(activityRepository.save(activity));
    }

    private String generateUniqueSlug(String name) {
        String base = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");

        if (!activityRepository.existsBySlug(base)) return base;

        int suffix = 2;
        while (activityRepository.existsBySlug(base + "-" + suffix)) suffix++;
        return base + "-" + suffix;
    }

    // Rampe de secours pour toute catégorie créée sans couleur explicite.
    // Un seul format doit exister en base (voir V46) : jamais de hex, jamais
    // de NULL — GET /api/categories doit toujours renvoyer une valeur
    // exploitable telle quelle par le client.
    private static final String[] DEFAULT_COLOR_RAMPS = {
        "orange-red", "purple-violet", "brown-amber", "blue-indigo", "green-teal",
        "cyan-blue", "red-orange", "pink-rose", "lime-green", "sky-blue"
    };

    public CategoryDto createCategory(CreateCategoryRequest request) {
        String name = request.name().strip();
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw new IllegalStateException("La catégorie \"" + name + "\" existe déjà.");
        }
        String colorRamp = DEFAULT_COLOR_RAMPS[Math.floorMod(name.hashCode(), DEFAULT_COLOR_RAMPS.length)];
        Category category = Category.builder().name(name).colorRamp(colorRamp).build();
        return toCategoryDto(categoryRepository.save(category));
    }

    @Transactional(readOnly = true)
    public Page<ActivityDto> searchActivities(UUID categoryId, String search, Pageable pageable) {
        Page<Activity> activities;

        if (categoryId != null && search != null && !search.isBlank()) {
            activities = activityRepository.findByCategoryIdAndNameContainingIgnoreCase(
                categoryId, search.strip(), pageable);
        } else if (categoryId != null) {
            activities = activityRepository.findByCategoryId(categoryId, pageable);
        } else if (search != null && !search.isBlank()) {
            activities = activityRepository.findByNameContainingIgnoreCase(
                search.strip(), pageable);
        } else {
            activities = activityRepository.findAll(pageable);
        }

        return activities.map(this::toActivityDto);
    }

    @Transactional(readOnly = true)
    public List<UserActivityDto> getUserActivities(UUID userId) {
        return userActivityRepository.findByUserId(userId).stream()
            .map(this::toUserActivityDto)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<UserActivityDto> getPublicUserActivities(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("Utilisateur introuvable.");
        }
        return userActivityRepository.findVisibleByUserId(userId).stream()
            .map(this::toUserActivityDto)
            .collect(Collectors.toList());
    }

    public UserActivityDto addActivityToProfile(UUID userId, UpsertUserActivityRequest request) {
        Activity activity = activityRepository.findById(request.activityId())
            .orElseThrow(() -> new ResourceNotFoundException("Activité introuvable."));

        if (userActivityRepository.existsByUserIdAndActivityId(userId, request.activityId())) {
            throw new IllegalStateException("Vous avez déjà ajouté cette activité.");
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));

        UserActivity userActivity = new UserActivity();
        userActivity.setUser(user);
        userActivity.setActivity(activity);
        userActivity.setVisibleOnMap(request.visibleOnMap() != null ? request.visibleOnMap() : true);
        userActivity.setCustomDescription(
            request.customDescription() != null ? sanitizer.sanitize(request.customDescription()) : null);
        userActivity.setLevel(request.level());
        userActivity.setFormat(request.format());

        userActivity = userActivityRepository.save(userActivity);
        subscriptionService.notifySubscribersOfNewUserActivity(userActivity);
        return toUserActivityDto(userActivity);
    }

    public UserActivityDto updateUserActivity(UUID userId, UUID userActivityId,
                                             UpsertUserActivityRequest request) {
        UserActivity userActivity = userActivityRepository
            .findByIdAndUserId(userActivityId, userId)
            .orElseThrow(() -> new ForbiddenException("Activité introuvable sur votre profil."));

        if (request.visibleOnMap() != null) {
            userActivity.setVisibleOnMap(request.visibleOnMap());
        }
        if (request.customDescription() != null) {
            userActivity.setCustomDescription(sanitizer.sanitize(request.customDescription()));
        }
        if (request.level() != null) {
            userActivity.setLevel(request.level());
        }
        if (request.format() != null) {
            userActivity.setFormat(request.format());
        }

        userActivity = userActivityRepository.save(userActivity);
        subscriptionService.notifySubscribersOfUserActivityUpdate(userActivity);
        return toUserActivityDto(userActivity);
    }

    public void removeActivityFromProfile(UUID userId, UUID userActivityId) {
        UserActivity userActivity = userActivityRepository
            .findByIdAndUserId(userActivityId, userId)
            .orElseThrow(() -> new ForbiddenException("Activité introuvable sur votre profil."));

        userActivityRepository.delete(userActivity);
    }

    public UserActivityDto toggleMapVisibility(UUID userId, UUID userActivityId, Boolean visible) {
        UserActivity userActivity = userActivityRepository
            .findByIdAndUserId(userActivityId, userId)
            .orElseThrow(() -> new ForbiddenException("Activité introuvable sur votre profil."));

        userActivity.setVisibleOnMap(visible != null ? visible : !userActivity.getVisibleOnMap());
        userActivity = userActivityRepository.save(userActivity);
        return toUserActivityDto(userActivity);
    }

    /**
     * Rendu <b>imbriqué</b> d'une catégorie : sans compteur ni état d'abonnement.
     *
     * <p>Les calculer ici coûterait deux requêtes par activité rendue, et aucun
     * de ces appels ne connaît l'appelant. Voir {@link #getAllCategories(UUID)}
     * pour le rendu complet.
     */
    private CategoryDto toCategoryDto(Category category) {
        return CategoryDto.nested(
            category.getId(),
            category.getName(),
            category.getIcon(),
            category.getColorRamp()
        );
    }

    public ActivityDto updateActivityIcon(UUID activityId, String icon) {
        Activity activity = activityRepository.findById(activityId)
            .orElseThrow(() -> new ResourceNotFoundException("Activité introuvable."));
        activity.setIcon(icon);
        return toActivityDto(activityRepository.save(activity));
    }

    private static final String DEFAULT_ACTIVITY_ICON = "sports";

    public record IconRemovalResult(String previousIcon, ActivityDto activity) {}

    public IconRemovalResult removeActivityIcon(UUID activityId) {
        Activity activity = activityRepository.findById(activityId)
            .orElseThrow(() -> new ResourceNotFoundException("Activité introuvable."));
        String previousIcon = activity.getIcon();
        activity.setIcon(DEFAULT_ACTIVITY_ICON);
        ActivityDto dto = toActivityDto(activityRepository.save(activity));
        return new IconRemovalResult(previousIcon, dto);
    }

    private ActivityDto toActivityDto(Activity activity) {
        return new ActivityDto(
            activity.getId(),
            activity.getName(),
            activity.getSlug(),
            activity.getDescription(),
            activity.getIcon(),
            activity.getImageUrl(),
            activity.getParent() != null ? activity.getParent().getId() : null,
            activity.getCategory() != null ? toCategoryDto(activity.getCategory()) : null
        );
    }

    private UserActivityDto toUserActivityDto(UserActivity ua) {
        // Load programs for this user activity
        List<ProgramSummaryDto> programs = programRepository
            .findByUserActivityId(ua.getId())
            .stream()
            .map(p -> new ProgramSummaryDto(
                p.getId(),
                p.getTitle(),
                p.getStatus().name(),
                p.getIsPublic(),
                p.getUpdatedAt()
            ))
            .collect(Collectors.toList());

        return new UserActivityDto(
            ua.getId(),
            toActivityDto(ua.getActivity()),
            ua.getVisibleOnMap(),
            ua.getCustomDescription(),
            ua.getLevel() != null ? ua.getLevel().name() : null,
            ua.getFormat() != null ? ua.getFormat().name() : null,
            ua.getCreatedAt(),
            programs
        );
    }
}
