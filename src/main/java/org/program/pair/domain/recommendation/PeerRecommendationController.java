package org.program.pair.domain.recommendation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.recommendation.dto.CreateRecommendationRequest;
import org.program.pair.domain.recommendation.dto.PeerRecommendationDto;
import org.program.pair.domain.recommendation.dto.RecommendationStatsDto;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
@Tag(name = "Recommendations", description = "Système de recommandations entre pairs")
@SecurityRequirement(name = "bearerAuth")
public class PeerRecommendationController {

    private final PeerRecommendationService recommendationService;

    @PostMapping
    @Operation(
        summary = "Créer une recommandation",
        description = "Recommander un utilisateur avec qui vous avez échangé des messages. Nécessite une conversation existante."
    )
    public ResponseEntity<PeerRecommendationDto> createRecommendation(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody CreateRecommendationRequest request) {

        PeerRecommendation recommendation = recommendationService.createRecommendation(
            currentUser.getId(),
            request
        );

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(PeerRecommendationDto.fromEntity(recommendation));
    }

    @GetMapping("/received")
    @Operation(
        summary = "Mes recommandations reçues",
        description = "Liste des recommandations que j'ai reçues, paginées"
    )
    public ResponseEntity<Page<PeerRecommendationDto>> getMyRecommendations(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<PeerRecommendationDto> recommendations = recommendationService
            .getRecommendationsReceived(currentUser.getId(), pageable)
            .map(PeerRecommendationDto::fromEntity);

        return ResponseEntity.ok(recommendations);
    }

    @GetMapping("/given")
    @Operation(
        summary = "Mes recommandations données",
        description = "Liste des recommandations que j'ai données à d'autres utilisateurs"
    )
    public ResponseEntity<Page<PeerRecommendationDto>> getMyGivenRecommendations(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<PeerRecommendationDto> recommendations = recommendationService
            .getRecommendationsGiven(currentUser.getId(), pageable)
            .map(PeerRecommendationDto::fromEntity);

        return ResponseEntity.ok(recommendations);
    }

    @GetMapping("/users/{userId}")
    @Operation(
        summary = "Recommandations d'un utilisateur",
        description = "Recommandations publiques reçues par un utilisateur"
    )
    public ResponseEntity<Page<PeerRecommendationDto>> getUserRecommendations(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<PeerRecommendationDto> recommendations = recommendationService
            .getRecommendationsReceived(userId, pageable)
            .map(PeerRecommendationDto::fromEntity);

        return ResponseEntity.ok(recommendations);
    }

    @GetMapping("/stats/{userId}")
    @Operation(
        summary = "Statistiques de recommandations",
        description = "Statistiques des recommandations d'un utilisateur (reçues, données, moyenne)"
    )
    public ResponseEntity<RecommendationStatsDto> getUserStats(@PathVariable UUID userId) {
        RecommendationStatsDto stats = recommendationService.getUserStats(userId);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/can-recommend/{userId}")
    @Operation(
        summary = "Puis-je recommander cet utilisateur?",
        description = "Vérifie si je peux recommander cet utilisateur (conversation existante, pas déjà recommandé)"
    )
    public ResponseEntity<Boolean> canRecommend(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable UUID userId) {

        boolean can = recommendationService.canRecommend(currentUser.getId(), userId);
        return ResponseEntity.ok(can);
    }

    @GetMapping("/me/stats")
    @Operation(summary = "Mes statistiques de recommandations")
    public ResponseEntity<RecommendationStatsDto> getMyStats(
            @AuthenticationPrincipal UserPrincipal currentUser) {

        RecommendationStatsDto stats = recommendationService.getUserStats(currentUser.getId());
        return ResponseEntity.ok(stats);
    }
}
