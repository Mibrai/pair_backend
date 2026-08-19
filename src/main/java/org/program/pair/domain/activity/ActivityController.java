package org.program.pair.domain.activity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
    private final ActivityBrowseService activityBrowseService;
    private final SuggestedActivityService suggestedActivityService;
    private final StorageService storageService;
    private final MediaValidator mediaValidator;
    private final ImageProcessor imageProcessor;

    /**
     * L'Explorer : une activité telle qu'une personne la propose, avec sa photo,
     * son organisateur, son nombre de programmes, sa prochaine séance et son
     * adresse — l'objet que le client fabriquait jusqu'ici en croisant
     * /programs, /map/activities et /activities, avec le nom d'activité pour
     * clé étrangère.
     *
     * <p>Maille : {@code UserActivity}. Enveloppe : {@code Page<T>} Spring,
     * comme /notifications. Distances en mètres.
     *
     * @return page de cartes, triée par distance croissante puis par nom
     */
    @GetMapping("/activities/browse")
    public Page<BrowsedActivityDto> browseActivities(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @ModelAttribute ActivityBrowseRequest request) {
        return activityBrowseService.browse(request, idOrNull(principal));
    }

    /**
     * Activités à proposer à quelqu'un qui n'en a encore déclaré aucune.
     *
     * <p>Alimente le dernier écran du parcours d'accueil, juste après
     * l'autorisation de position. <b>Ne rend jamais une liste vide</b> tant que la
     * base contient des activités : à défaut de voisinage, elle propose les plus
     * pratiquées ailleurs, et le dit par le drapeau {@code fallback}.
     *
     * <p>Route authentifiée : {@code /api/activities} n'est ouverte qu'en
     * correspondance exacte, et la suggestion a besoin de savoir ce que
     * l'appelant déclare déjà pour ne pas le lui proposer.
     */
    @GetMapping("/activities/suggested")
    public List<SuggestedActivityDto> suggestedActivities(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int limit) {
        return suggestedActivityService.suggest(principal.getId(), lat, lng, limit);
    }

    /**
     * Route <b>publique</b> : le principal est nul pour un appelant anonyme, et
     * {@code subscribed} vaut alors {@code false} — faute d'identité, pas faute
     * d'abonnement. Un client connecté ne doit pas s'en servir comme source de
     * vérité s'il l'a appelée hors session.
     */
    @GetMapping("/categories")
    public List<CategoryDto> getCategories(
            @AuthenticationPrincipal UserPrincipal principal) {
        return activityService.getAllCategories(idOrNull(principal));
    }

    private static UUID idOrNull(UserPrincipal principal) {
        return principal != null ? principal.getId() : null;
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryDto createCategory(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateCategoryRequest request) {
        return activityService.createCategory(request);
    }

    @PostMapping("/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityDto createActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateActivityRequest request) {
        return activityService.createActivity(request);
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

    @GetMapping("/users/{id}/activities")
    public List<UserActivityDto> getPublicUserActivities(@PathVariable UUID id) {
        return activityService.getPublicUserActivities(id);
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

    @DeleteMapping("/activities/{activityId}/icon")
    public ActivityDto deleteActivityIcon(@PathVariable UUID activityId) throws IOException {
        ActivityService.IconRemovalResult result = activityService.removeActivityIcon(activityId);
        String prefix = "/api/media/files/";
        if (result.previousIcon() != null && result.previousIcon().startsWith(prefix)) {
            storageService.delete(result.previousIcon().substring(prefix.length()));
        }
        return result.activity();
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
