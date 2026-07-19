package org.program.pair.domain.subscription;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.subscription.dto.SubscriptionDto;
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
    @Operation(summary = "S'abonner à un auteur", description = "Être notifié des nouvelles activités et programmes de cet utilisateur")
    public SubscriptionDto subscribeToAuthor(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userId) {
        return subscriptionService.subscribeToAuthor(principal.getId(), userId);
    }

    @DeleteMapping("/users/{userId}/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Se désabonner d'un auteur")
    public void unsubscribeFromAuthor(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userId) {
        subscriptionService.unsubscribeFromAuthor(principal.getId(), userId);
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
    @Operation(summary = "Se désabonner d'une activité")
    public void unsubscribeFromUserActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userActivityId) {
        subscriptionService.unsubscribeFromUserActivity(principal.getId(), userActivityId);
    }

    @PostMapping("/categories/{categoryId}/subscription")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "S'abonner à une catégorie", description = "Être notifié des nouvelles activités créées dans cette catégorie")
    public SubscriptionDto subscribeToCategory(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID categoryId) {
        return subscriptionService.subscribeToCategory(principal.getId(), categoryId);
    }

    @DeleteMapping("/categories/{categoryId}/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Se désabonner d'une catégorie")
    public void unsubscribeFromCategory(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID categoryId) {
        subscriptionService.unsubscribeFromCategory(principal.getId(), categoryId);
    }

    @GetMapping("/users/me/subscriptions")
    @Operation(summary = "Lister mes abonnements")
    public List<SubscriptionDto> getMySubscriptions(
            @AuthenticationPrincipal UserPrincipal principal) {
        return subscriptionService.listMySubscriptions(principal.getId());
    }
}
