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

    @Transactional(readOnly = true)
    public List<CategoryDto> getAllCategories() {
        return categoryRepository.findAll().stream()
            .map(this::toCategoryDto)
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

    public CategoryDto createCategory(CreateCategoryRequest request) {
        String name = request.name().strip();
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw new IllegalStateException("La catégorie \"" + name + "\" existe déjà.");
        }
        Category category = Category.builder().name(name).build();
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

    private CategoryDto toCategoryDto(Category category) {
        return new CategoryDto(
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

    private ActivityDto toActivityDto(Activity activity) {
        return new ActivityDto(
            activity.getId(),
            activity.getName(),
            activity.getSlug(),
            activity.getDescription(),
            activity.getIcon(),
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
