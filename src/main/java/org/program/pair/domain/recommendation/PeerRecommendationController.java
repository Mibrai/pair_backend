package org.program.pair.domain.recommendation;

import org.program.pair.shared.exception.UserNotFoundException;
import org.program.pair.domain.block.BlockFilterService;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Parameter;
import org.program.pair.shared.web.Pages;
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
    private final BlockFilterService blockFilterService;

    @PostMapping
    @Operation(
        summary = "Créer une recommandation",
        description = "Geste binaire et positif : recommander quelqu'un, sans note ni commentaire "
            + "obligatoires (rating et comment sont facultatifs, aucune valeur par défaut n'est "
            + "appliquée). Nécessite une preuve d'interaction réelle : soit une conversation "
            + "directe, soit une double confirmation de présence sur le même créneau "
            + "(SHARED_ATTENDANCE) — les deux personnes n'ont pas besoin de s'être jamais écrit."
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
            @RequestParam(defaultValue = "20") @Parameter(schema = @Schema(maximum = "50", defaultValue = "20")) int size) {

        Pageable pageable = Pages.borne(page, size);
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
            @RequestParam(defaultValue = "20") @Parameter(schema = @Schema(maximum = "50", defaultValue = "20")) int size) {

        Pageable pageable = Pages.borne(page, size);
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
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") @Parameter(schema = @Schema(maximum = "50", defaultValue = "20")) int size) {

        introuvableSiBloque(currentUser, userId);
        Pageable pageable = Pages.borne(page, size);
        Page<PeerRecommendationDto> recommendations = recommendationService
            .getRecommendationsReceived(userId, pageable)
            .map(PeerRecommendationDto::fromEntity);

        return ResponseEntity.ok(recommendations);
    }

    /**
     * Ses propres statistiques seulement (P-BL-10, décision du 13/09) : les
     * décomptes d'une autre personne ne se lisent plus, et ceux de l'appelant
     * sont aussi à {@code /me/stats}. Pour autrui, le même 404 qu'un compte
     * inexistant.
     */
    @GetMapping("/stats/{userId}")
    @Operation(
        summary = "Statistiques de recommandations",
        description = "Ses propres statistiques (reçues, données). 404 pour toute autre personne : "
            + "les décomptes d'autrui ne se lisent plus (P-BL-10). Préférer /me/stats."
    )
    public ResponseEntity<RecommendationStatsDto> getUserStats(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable UUID userId) {
        if (!currentUser.getId().equals(userId)) {
            throw new UserNotFoundException("Utilisateur introuvable.");
        }
        RecommendationStatsDto stats = recommendationService.getUserStats(userId);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/can-recommend/{userId}")
    @Operation(
        summary = "Puis-je recommander cet utilisateur?",
        description = "Vérifie si je peux recommander cet utilisateur (conversation directe ou "
            + "présence partagée confirmée, pas déjà recommandé)"
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

    /**
     * Les recommandations d'une personne bloquée — dans un sens ou dans l'autre —
     * sont introuvables, avec le message d'un compte qui n'existe pas (P-BS-14).
     * Même règle que {@code GET /api/users/{id}} : un 403 apprendrait le blocage.
     */
    private void introuvableSiBloque(UserPrincipal appelant, UUID userId) {
        if (blockFilterService.blocked(appelant.getId(), userId)) {
            throw new UserNotFoundException("Utilisateur introuvable.");
        }
    }
}
