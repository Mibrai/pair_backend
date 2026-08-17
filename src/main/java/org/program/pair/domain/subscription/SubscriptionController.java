package org.program.pair.domain.subscription;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.subscription.dto.SubscriberDto;
import org.program.pair.domain.subscription.dto.SubscriptionDto;
import org.program.pair.domain.subscription.dto.SubscriptionScopeRequest;
import org.program.pair.domain.subscription.dto.UpdateSubscriptionRequest;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Subscriptions", description = "Abonnements à un auteur, une activité ou une catégorie")
@SecurityRequirement(name = "bearerAuth")
public class SubscriptionController {

    /** Même plafond que /notifications : une page ne dépasse pas cinquante entrées. */
    private static final int MAX_PAGE_SIZE = 50;

    private final SubscriptionService subscriptionService;

    @PostMapping("/users/{userId}/subscription")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "S'abonner à un auteur",
        description = "Être notifié des nouvelles activités et programmes de cet utilisateur. "
            + "409 ALREADY_SUBSCRIBED si l'abonnement existe déjà — l'état voulu est alors "
            + "en base, et l'appelant peut le traiter comme un succès. "
            + "403 SUBSCRIPTIONS_NOT_ALLOWED si le profil refuse les nouveaux abonnés.")
    public SubscriptionDto subscribeToAuthor(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userId) {
        return subscriptionService.subscribeToAuthor(principal.getId(), userId);
    }

    @DeleteMapping("/users/{userId}/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Se désabonner d'un auteur",
        description = "Idempotent : 204 même sans abonnement préalable. Un 404 ferait "
            + "revenir en arrière, à tort, un retrait déjà appliqué côté client.")
    public void unsubscribeFromAuthor(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userId) {
        subscriptionService.unsubscribeFromAuthor(principal.getId(), userId);
    }

    @PatchMapping("/users/{userId}/subscription")
    @Operation(summary = "Régler un abonnement à un auteur",
        description = "Niveau : ALL, NEW_ONLY ou MUTED. 404 si l'appelant n'a pas "
            + "d'abonnement sur cette cible.")
    public SubscriptionDto updateAuthorSubscription(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateSubscriptionRequest request) {
        return subscriptionService.updateAuthorSubscription(principal.getId(), userId, request);
    }

    @PostMapping("/user-activities/{userActivityId}/subscription")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "S'abonner à une activité",
        description = "Être notifié des changements et des nouveaux programmes de cette "
            + "activité. Mêmes refus que l'abonnement à un auteur, et pour la même raison "
            + "— suivre ce que quelqu'un propose, c'est le suivre : 403 "
            + "SUBSCRIPTIONS_NOT_ALLOWED si son auteur refuse les nouveaux abonnés, 403 "
            + "sur sa propre activité, 409 ALREADY_SUBSCRIBED si l'abonnement existe déjà.")
    public SubscriptionDto subscribeToUserActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userActivityId) {
        return subscriptionService.subscribeToUserActivity(principal.getId(), userActivityId);
    }

    @DeleteMapping("/user-activities/{userActivityId}/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Se désabonner d'une activité", description = "Idempotent : 204 même sans abonnement préalable.")
    public void unsubscribeFromUserActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userActivityId) {
        subscriptionService.unsubscribeFromUserActivity(principal.getId(), userActivityId);
    }

    @PatchMapping("/user-activities/{userActivityId}/subscription")
    @Operation(summary = "Régler un abonnement à une activité")
    public SubscriptionDto updateUserActivitySubscription(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userActivityId,
            @Valid @RequestBody UpdateSubscriptionRequest request) {
        return subscriptionService.updateUserActivitySubscription(
            principal.getId(), userActivityId, request);
    }

    /**
     * Le corps est facultatif : absent, l'abonnement notifie sans contrainte
     * géographique — le comportement d'avant la portée, celui des lignes déjà
     * en base.
     */
    @PostMapping("/categories/{categoryId}/subscription")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "S'abonner à une catégorie",
        description = "Être notifié des nouvelles activités créées dans cette catégorie. "
            + "Corps facultatif : lat, lng et radiusMeters (en MÈTRES) bornent la portée. "
            + "Sans portée, un abonnement notifie dans le monde entier — le référentiel des "
            + "catégories est mondial.")
    public SubscriptionDto subscribeToCategory(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID categoryId,
            @Valid @RequestBody(required = false) SubscriptionScopeRequest scope) {
        return subscriptionService.subscribeToCategory(principal.getId(), categoryId, scope);
    }

    @DeleteMapping("/categories/{categoryId}/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Se désabonner d'une catégorie", description = "Idempotent : 204 même sans abonnement préalable.")
    public void unsubscribeFromCategory(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID categoryId) {
        subscriptionService.unsubscribeFromCategory(principal.getId(), categoryId);
    }

    @PatchMapping("/categories/{categoryId}/subscription")
    @Operation(summary = "Régler un abonnement à une catégorie",
        description = "Niveau et portée géographique. clearScope retire la portée ; "
            + "lat/lng/radiusMeters la posent. Les deux ensemble sont refusés.")
    public SubscriptionDto updateCategorySubscription(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID categoryId,
            @Valid @RequestBody UpdateSubscriptionRequest request) {
        return subscriptionService.updateCategorySubscription(principal.getId(), categoryId, request);
    }

    /**
     * <b>Rupture de contrat assumée</b> : cette route rendait un tableau, elle
     * rend désormais une enveloppe {@code Page}, comme {@code /notifications} et
     * {@code /activities/browse}.
     *
     * <p>Elle n'était paginable qu'une fois {@code subscribed} servi sur les DTO
     * de cible : paginée avant, tous les boutons dont la cible ne figurait pas
     * dans la première page seraient repassés à « S'abonner », et un second clic
     * aurait tenté un {@code POST} déjà existant.
     */
    @GetMapping("/users/me/subscriptions")
    @Operation(summary = "Lister mes abonnements",
        description = "Page d'abonnements, du plus récent au plus ancien. Le tri porte "
            + "sur createdAt seulement : trier par nom de cible supposerait de classer "
            + "trois tables selon le type, et ne classerait que la page.")
    public Page<SubscriptionDto> getMySubscriptions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) SubscriptionType type,
            @RequestParam(defaultValue = "desc") String direction) {

        Sort sort = Sort.by("asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC,
            "createdAt");
        return subscriptionService.listMySubscriptions(
            principal.getId(), type, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), sort));
    }

    /**
     * Mes abonnés — et personne d'autre n'a d'équivalent.
     *
     * <p>Il n'existe volontairement pas de {@code GET /users/{id}/subscribers} :
     * savoir qui suit un tiers n'a aucun usage produit ici, et crée une
     * exposition dont nous ne voulons pas.
     */
    @GetMapping("/users/me/subscribers")
    @Operation(summary = "Lister mes abonnés",
        description = "Les personnes qui me suivent, moi (AUTHOR) ou l'une de mes activités "
            + "(USER_ACTIVITY). targetId restreint à une activité précise, dont l'appelant "
            + "doit être l'auteur — 403 sinon. type=CATEGORY rend 403 : une catégorie "
            + "n'appartient à personne, et exposer qui la suit révélerait une donnée "
            + "personnelle que rien ne justifie de transporter.")
    public Page<SubscriberDto> getMySubscribers(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) SubscriptionType type,
            @RequestParam(required = false) UUID targetId) {

        return subscriptionService.listMySubscribers(
            principal.getId(), type, targetId,
            PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt")));
    }
}
