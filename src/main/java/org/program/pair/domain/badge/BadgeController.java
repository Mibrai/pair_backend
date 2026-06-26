package org.program.pair.domain.badge;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.badge.dto.BadgeAwardDto;
import org.program.pair.domain.badge.dto.BadgeDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/badges")
@RequiredArgsConstructor
@Tag(name = "Badges", description = "Système de badges et gamification")
@SecurityRequirement(name = "bearerAuth")
public class BadgeController {

    private final BadgeService badgeService;

    @GetMapping
    @Operation(summary = "Liste tous les badges disponibles")
    public ResponseEntity<List<BadgeDto>> getAllBadges() {
        List<BadgeDto> badges = badgeService.getAllBadges().stream()
            .map(BadgeDto::fromEntity)
            .toList();
        return ResponseEntity.ok(badges);
    }

    @GetMapping("/me")
    @Operation(summary = "Mes badges", description = "Récupère tous les badges que j'ai obtenus")
    public ResponseEntity<List<BadgeAwardDto>> getMyBadges(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<BadgeAwardDto> awards = badgeService.getUserBadges(currentUser.getId()).stream()
            .map(BadgeAwardDto::fromEntity)
            .toList();
        return ResponseEntity.ok(awards);
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "Badges d'un utilisateur", description = "Récupère les badges publics d'un utilisateur")
    public ResponseEntity<List<BadgeAwardDto>> getUserBadges(@PathVariable UUID userId) {
        List<BadgeAwardDto> awards = badgeService.getUserBadges(userId).stream()
            .map(BadgeAwardDto::fromEntity)
            .toList();
        return ResponseEntity.ok(awards);
    }

    @PostMapping("/me/evaluate")
    @Operation(
        summary = "Évaluer mes badges",
        description = "Déclenche l'évaluation de tous les badges et attribue ceux pour lesquels je suis éligible"
    )
    public ResponseEntity<List<BadgeAwardDto>> evaluateMyBadges(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<BadgeAwardDto> newBadges = badgeService.evaluateBadges(currentUser.getId()).stream()
            .map(BadgeAwardDto::fromEntity)
            .toList();
        return ResponseEntity.ok(newBadges);
    }

    @GetMapping("/me/count")
    @Operation(summary = "Nombre de badges obtenus")
    public ResponseEntity<Long> getMyBadgeCount(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        long count = badgeService.countUserBadges(currentUser.getId());
        return ResponseEntity.ok(count);
    }
}
