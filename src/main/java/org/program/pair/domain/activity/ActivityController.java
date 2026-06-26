package org.program.pair.domain.activity;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.dto.*;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class ActivityController {

    private final ActivityService activityService;

    @GetMapping("/categories")
    public List<CategoryDto> getCategories() {
        return activityService.getAllCategories();
    }

    @GetMapping("/activities")
    public Page<ActivityDto> searchActivities(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return activityService.searchActivities(categoryId, search,
            PageRequest.of(page, Math.min(size, 50)));
    }

    @GetMapping("/users/me/activities")
    public List<UserActivityDto> getMyActivities(
            @AuthenticationPrincipal UserPrincipal principal) {
        return activityService.getUserActivities(principal.getId());
    }

    @PostMapping("/users/me/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public UserActivityDto addActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpsertUserActivityRequest request) {
        return activityService.addActivityToProfile(principal.getId(), request);
    }

    @PutMapping("/users/me/activities/{userActivityId}")
    public UserActivityDto updateActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userActivityId,
            @Valid @RequestBody UpsertUserActivityRequest request) {
        return activityService.updateUserActivity(principal.getId(), userActivityId, request);
    }

    @DeleteMapping("/users/me/activities/{userActivityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userActivityId) {
        activityService.removeActivityFromProfile(principal.getId(), userActivityId);
    }

    @PatchMapping("/users/me/activities/{userActivityId}/visibility")
    public UserActivityDto toggleVisibility(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userActivityId,
            @RequestBody VisibilityRequest request) {
        return activityService.toggleMapVisibility(
            principal.getId(), userActivityId, request.visible());
    }
}
