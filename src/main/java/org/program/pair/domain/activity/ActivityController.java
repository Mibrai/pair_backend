package org.program.pair.domain.activity;

import org.program.pair.shared.media.ProcessedMultipartFile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.dto.*;
import org.program.pair.domain.media.ImageProcessor;
import org.program.pair.domain.media.MediaFileService;
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
    private final MediaFileService mediaFileService;
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
     * Les compteurs du panneau de filtres de l'Explorer.
     *
     * <p>Mêmes paramètres que {@code /activities/browse}, dont seuls la zone, les
     * catégories et l'expiration sont retenus : un compteur doit annoncer ce
     * qu'on obtiendrait <b>en cochant</b> la case, pas ce qu'on a déjà. Compter à
     * l'intérieur du filtre courant afficherait zéro à côté de toutes les cases
     * non cochées, et les ferait passer pour des impasses.
     *
     * <p>Route séparée plutôt qu'enveloppe autour de la page : le
     * {@code Page<BrowsedActivityDto>} est déjà consommé par une version publiée
     * du client, et l'envelopper aurait cassé ce contrat.
     */
    @GetMapping("/activities/browse/facets")
    public ActivityFacetsDto browseFacets(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @ModelAttribute ActivityBrowseRequest request) {
        return activityBrowseService.facets(request, idOrNull(principal));
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
            @Valid @RequestBody ActivityVisibilityRequest request) {
        return activityService.toggleMapVisibility(
            principal.getId(), userActivityId, request.visible());
    }

    // ———————————————————— icônes d'activité ————————————————————
    //
    // LA LECTURE QUI TRANCHE (fiche P-BS-01, étape 9). La table `activities`
    // NE PORTE PAS D'AUTEUR : V3 la crée avec parent_id, category_id, name,
    // slug, description, embedding et created_at, V22 y ajoute `icon` et V38
    // `image_url` ; aucune colonne ne désigne un créateur, et l'entité
    // `Activity` n'en a pas non plus. C'est un référentiel PARTAGÉ, alimenté
    // par les migrations de catalogue (V3, V103), pas un objet que quelqu'un
    // possède.
    //
    // Conséquence, et elle est décidée par cette lecture et non par une
    // préférence : le changement d'icône RESTE PERMIS à tout compte connecté.
    // Un 403 MEDIA_FORBIDDEN aurait supposé un auteur à comparer ; il n'y en a
    // pas, et inventer une règle de propriété sur un référentiel partagé
    // casserait le parcours publié de l'application (create_activity_sheet,
    // edit_activity_sheet) sans protéger quoi que ce soit.
    //
    // Ce qui change, c'est le SORT DE L'ANCIEN FICHIER : il n'est effacé que si
    // son déposant est l'appelant. C'était le vrai dégât — tout compte pouvait
    // remplacer l'icône d'une activité partagée et détruire au passage le
    // fichier déposé par quelqu'un d'autre. Les trois routes reçoivent donc
    // maintenant l'appelant, ce qui leur manquait.

    @PatchMapping("/activities/{activityId}/icon")
    public ActivityDto setActivityIcon(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID activityId,
            @RequestParam("icon") String icon) {
        ActivityService.IconChange change = activityService.updateActivityIcon(activityId, icon);
        oublierAncienneIcone(change.previousIcon(), icon, principal);
        return change.activity();
    }

    @PostMapping("/activities/{activityId}/icon/upload")
    public ActivityDto uploadActivityIcon(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID activityId,
            @RequestParam("file") MultipartFile file) throws IOException {
        mediaValidator.validateImage(file);
        InputStream processedImage = imageProcessor.processImage(file);
        ProcessedMultipartFile processedFile = new ProcessedMultipartFile(
            file.getOriginalFilename(), processedImage);
        // Le déposant est l'appelant, pas l'activité : cette ligne passait
        // `activityId`, et c'est la moitié « activité » de la racine du défaut.
        String filename = storageService.store(processedFile, principal.getId(), MediaType.ACTIVITY_ICON);
        String nouvelleIcone = MediaFileService.URL_PREFIX + filename;

        ActivityService.IconChange change = activityService.updateActivityIcon(activityId, nouvelleIcone);
        oublierAncienneIcone(change.previousIcon(), nouvelleIcone, principal);
        return change.activity();
    }

    @DeleteMapping("/activities/{activityId}/icon")
    public ActivityDto deleteActivityIcon(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID activityId) {
        ActivityService.IconChange change = activityService.removeActivityIcon(activityId);
        oublierAncienneIcone(change.previousIcon(), null, principal);
        return change.activity();
    }

    /**
     * Efface l'ancienne icône <b>seulement</b> si l'appelant l'avait déposée.
     *
     * <p>Silencieux par construction : une ligature Material, une URL externe,
     * un fichier antérieur à V109 ou un fichier déposé par un tiers laissent
     * tous les octets en place, et le changement d'icône réussit quand même.
     * C'est le comportement demandé — on ne refuse pas le geste, on refuse la
     * destruction collatérale.
     */
    private void oublierAncienneIcone(String ancienne, String nouvelle, UserPrincipal principal) {
        if (ancienne == null || ancienne.equals(nouvelle) || principal == null) {
            return;
        }
        mediaFileService.supprimerUrlSiAuteur(ancienne, principal.getId());
    }

}
