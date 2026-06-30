package org.program.pair.domain.review;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.review.dto.CreateReviewRequest;
import org.program.pair.domain.review.dto.ReviewDto;
import org.program.pair.domain.review.dto.ReviewSummaryDto;
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
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
@Tag(name = "Reviews", description = "Système d'avis sur les programmes")
@SecurityRequirement(name = "bearerAuth")
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping
    @Operation(
        summary = "Créer un avis",
        description = "Évaluer un programme avec 5 critères. Nécessite d'avoir échangé avec le créateur du programme."
    )
    public ResponseEntity<ReviewDto> createReview(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody CreateReviewRequest request) {

        Review review = reviewService.createReview(currentUser.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ReviewDto.fromEntity(review));
    }

    @GetMapping("/programs/{programId}/summary")
    @Operation(summary = "Résumé des avis d'un programme", description = "Note moyenne, nombre total et moyennes par critère")
    public ResponseEntity<ReviewSummaryDto> getProgramReviewSummary(@PathVariable UUID programId) {
        return ResponseEntity.ok(reviewService.getProgramReviewSummary(programId));
    }

    @GetMapping("/programs/{programId}")
    @Operation(summary = "Avis d'un programme", description = "Liste des avis d'un programme, paginés")
    public ResponseEntity<Page<ReviewDto>> getProgramReviews(
            @PathVariable UUID programId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<ReviewDto> reviews = reviewService.getProgramReviews(programId, pageable)
            .map(ReviewDto::fromEntity);
        return ResponseEntity.ok(reviews);
    }

    @GetMapping("/me")
    @Operation(summary = "Mes avis", description = "Avis que j'ai donnés")
    public ResponseEntity<Page<ReviewDto>> getMyReviews(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<ReviewDto> reviews = reviewService.getUserReviews(currentUser.getId(), pageable)
            .map(ReviewDto::fromEntity);
        return ResponseEntity.ok(reviews);
    }

    @GetMapping("/can-review/{programId}")
    @Operation(
        summary = "Puis-je évaluer ce programme?",
        description = "Vérifie si je peux évaluer ce programme (conversation avec créateur, pas déjà évalué)"
    )
    public ResponseEntity<Boolean> canReview(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @PathVariable UUID programId) {

        boolean can = reviewService.canReview(currentUser.getId(), programId);
        return ResponseEntity.ok(can);
    }
}
