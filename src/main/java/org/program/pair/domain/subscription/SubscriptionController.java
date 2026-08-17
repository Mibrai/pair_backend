package org.program.pair.domain.subscription;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.subscription.dto.SubscriptionDto;
import org.program.pair.domain.subscription.dto.SubscriptionScopeRequest;
import org.program.pair.domain.subscription.dto.UpdateSubscriptionRequest;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Subscriptions", description = "Abonnements à un auteur, une activité ou une catégorie")
@SecurityRequirement(name = "bearerAuth")
public class SubscriptionController {

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
    @Operation(summary = "S'abonner à une activité", description = "Être notifié des changements et des nouveaux programmes de cette activité")
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

    @GetMapping("/users/me/subscriptions")
    @Operation(summary = "Lister mes abonnements")
    public List<SubscriptionDto> getMySubscriptions(
            @AuthenticationPrincipal UserPrincipal principal) {
        return subscriptionService.listMySubscriptions(principal.getId());
    }
}
