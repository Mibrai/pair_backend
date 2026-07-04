package org.program.pair.domain.activity;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.dto.*;
import org.program.pair.domain.media.ImageProcessor;
import org.program.pair.domain.media.MediaType;
import org.program.pair.domain.media.MediaValidator;
import org.program.pair.domain.media.StorageService;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class ActivityController {

    private final ActivityService activityService;
    private final StorageService storageService;
    private final MediaValidator mediaValidator;
    private final ImageProcessor imageProcessor;

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

    @PatchMapping("/activities/{activityId}/icon")
    public ActivityDto setActivityIcon(
            @PathVariable UUID activityId,
            @RequestParam("icon") String icon) {
        return activityService.updateActivityIcon(activityId, icon);
    }

    @PostMapping("/activities/{activityId}/icon/upload")
    public ActivityDto uploadActivityIcon(
            @PathVariable UUID activityId,
            @RequestParam("file") MultipartFile file) throws IOException {
        mediaValidator.validateImage(file);
        InputStream processedImage = imageProcessor.processImage(file);
        ProcessedMultipartFile processedFile = new ProcessedMultipartFile(
            file.getOriginalFilename(), processedImage);
        String filename = storageService.store(processedFile, activityId, MediaType.ACTIVITY_ICON);
        return activityService.updateActivityIcon(activityId, "/api/media/files/" + filename);
    }

    private static class ProcessedMultipartFile implements MultipartFile {
        private final String originalFilename;
        private final InputStream inputStream;

        ProcessedMultipartFile(String originalFilename, InputStream inputStream) {
            this.originalFilename = originalFilename;
            this.inputStream = inputStream;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return "image/jpeg"; }
        @Override public boolean isEmpty() { return false; }
        @Override public long getSize() { return 0; }
        @Override public byte[] getBytes() throws IOException { return inputStream.readAllBytes(); }
        @Override public InputStream getInputStream() { return inputStream; }
        @Override public void transferTo(java.io.File dest) throws IOException { throw new UnsupportedOperationException(); }
    }
}
