# Pair — Phase 3 : Crédibilité & Confiance
## Spécification d'implémentation pour Claude Code

> **Prérequis** : Phases 1 et 2 complètes.
>
> **Objectif** : construire la couche de confiance — badges, recommandations
> entre pairs, avis sur les programmes — sans jamais noter les personnes.
> Règle absolue : tout avis ou recommandation nécessite une interaction prouvée.

---

## Module 1 — Badges

### BadgeService.java

```java
@Service
@Transactional
@RequiredArgsConstructor
public class BadgeService {

    private final BadgeRepository badgeRepository;
    private final BadgeAwardRepository badgeAwardRepository;
    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final ProgramRepository programRepository;
    private final ProgressionEntryRepository progressionRepository;

    // Évaluer et décerner les badges d'un utilisateur
    // Appelé après chaque action significative
    @Async
    public void evaluateBadges(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();
        List<Badge> allBadges = badgeRepository.findAll();
        Set<String> alreadyAwarded = badgeAwardRepository
            .findByUserId(userId).stream()
            .map(a -> a.getBadge().getCode())
            .collect(Collectors.toSet());

        for (Badge badge : allBadges) {
            if (alreadyAwarded.contains(badge.getCode())) continue;
            if (isEligible(user, badge)) {
                awardBadge(user, badge);
            }
        }
    }

    private boolean isEligible(User user, Badge badge) {
        return switch (badge.getConditionType()) {
            case VERIFICATION -> switch (badge.getCode()) {
                case "VERIFIED_EMAIL" ->
                    user.getVerificationStatus() != VerificationStatus.UNVERIFIED;
                case "VERIFIED_PHONE" ->
                    user.getVerificationStatus() == VerificationStatus.PHONE_VERIFIED
                    || user.getVerificationStatus() == VerificationStatus.ID_VERIFIED;
                default -> false;
            };
            case RECOMMENDATION_COUNT -> {
                int count = badgeAwardRepository.countRecommendationsReceived(user.getId());
                yield count >= badge.getConditionThreshold();
            }
            case PROGRAM_COUNT -> {
                int count = programRepository.countActiveByUserId(user.getId());
                yield count >= badge.getConditionThreshold();
            }
            case PROGRESSION_STREAK -> {
                int streak = progressionRepository.getCurrentStreak(user.getId());
                yield streak >= badge.getConditionThreshold();
            }
            case ACTIVITY_DIVERSITY -> {
                int count = userActivityRepository.countByUserId(user.getId());
                yield count >= badge.getConditionThreshold();
            }
            default -> false;
        };
    }

    private void awardBadge(User user, Badge badge) {
        BadgeAward award = new BadgeAward();
        award.getBadgeAwardId().setBadgeId(badge.getId());
        award.getBadgeAwardId().setUserId(user.getId());
        award.setBadge(badge);
        award.setUser(user);
        award.setAwardedAt(Instant.now());
        badgeAwardRepository.save(award);
        // Déclencher notification in-app (Phase 4 pour email/push)
        eventPublisher.publishEvent(new BadgeEarnedEvent(user.getId(), badge));
    }
}
```

### BadgeController.java

```java
@RestController
@RequestMapping("/api/badges")
@RequiredArgsConstructor
public class BadgeController {

    private final BadgeService badgeService;
    private final BadgeRepository badgeRepository;

    // GET /api/badges — tous les badges disponibles
    @GetMapping
    public List<BadgeDto> getAllBadges() {
        return badgeRepository.findAll().stream()
            .map(this::toDto).toList();
    }

    // GET /api/badges/me — mes badges obtenus
    @GetMapping("/me")
    public List<BadgeAwardDto> getMyBadges(
            @AuthenticationPrincipal UserPrincipal principal) {
        return badgeService.getUserBadges(principal.getId());
    }

    // GET /api/badges/users/{userId} — badges publics d'un utilisateur
    @GetMapping("/users/{userId}")
    public List<BadgeAwardDto> getUserBadges(@PathVariable UUID userId) {
        return badgeService.getUserBadges(userId);
    }
}
```

---

## Module 2 — Recommandations entre pairs

### DTOs Recommandations

```java
// Créer une recommandation
public record CreateRecommendationRequest(
    @NotNull UUID toUserId,
    @NotNull UUID interactionProofId,  // ID de la conversation qui prouve l'interaction
    @Size(max = 500) String comment
) {}

// Recommandation reçue
public record PeerRecommendationDto(
    UUID id,
    UserPublicDto fromUser,
    String comment,
    Instant createdAt
) {}
```

### PeerRecommendationService.java

```java
@Service
@Transactional
@RequiredArgsConstructor
public class PeerRecommendationService {

    private final PeerRecommendationRepository recommendationRepository;
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
    private final HtmlSanitizer sanitizer;
    private final BadgeService badgeService;

    public PeerRecommendationDto create(UUID fromUserId,
                                         CreateRecommendationRequest request) {
        // 1. Ne pas se recommander soi-même
        if (fromUserId.equals(request.toUserId())) {
            throw new ValidationException(
                "Vous ne pouvez pas vous recommander vous-même.");
        }

        // 2. Vérifier qu'une conversation existe entre les deux utilisateurs
        Conversation proof = conversationRepository
            .findByIdAndBothMembers(request.interactionProofId(),
                fromUserId, request.toUserId())
            .orElseThrow(() -> new InsufficientInteractionException(
                "Une conversation réelle est requise pour recommander cet utilisateur."));

        // 3. Vérifier unicité (une seule recommandation par paire)
        if (recommendationRepository.existsByFromUserIdAndToUserId(
                fromUserId, request.toUserId())) {
            throw new DuplicateException(
                "Vous avez déjà recommandé cet utilisateur.");
        }

        // 4. Créer la recommandation
        PeerRecommendation rec = new PeerRecommendation();
        rec.setFromUser(userRepository.getReferenceById(fromUserId));
        rec.setToUser(userRepository.getReferenceById(request.toUserId()));
        rec.setInteractionProof(proof);
        if (request.comment() != null) {
            rec.setComment(sanitizer.sanitize(request.comment()));
        }
        rec = recommendationRepository.save(rec);

        // 5. Réévaluer les badges du destinataire
        badgeService.evaluateBadges(request.toUserId());

        return toDto(rec);
    }

    public List<PeerRecommendationDto> getRecommendationsForUser(UUID userId) {
        return recommendationRepository.findByToUserIdOrderByCreatedAtDesc(userId)
            .stream().map(this::toDto).toList();
    }
}
```

### PeerRecommendationController.java

```java
@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class PeerRecommendationController {

    private final PeerRecommendationService recommendationService;

    // POST /api/recommendations
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeerRecommendationDto create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateRecommendationRequest request) {
        return recommendationService.create(principal.getId(), request);
    }

    // GET /api/recommendations/me — recommandations reçues
    @GetMapping("/me")
    public List<PeerRecommendationDto> getMyRecommendations(
            @AuthenticationPrincipal UserPrincipal principal) {
        return recommendationService.getRecommendationsForUser(principal.getId());
    }

    // GET /api/recommendations/users/{userId} — recommandations publiques
    @GetMapping("/users/{userId}")
    public List<PeerRecommendationDto> getUserRecommendations(@PathVariable UUID userId) {
        return recommendationService.getRecommendationsForUser(userId);
    }
}
```

---

## Module 3 — Avis sur les programmes

### DTOs Avis

```java
// Créer un avis
public record CreateReviewRequest(
    @NotNull UUID programId,
    @NotNull UUID interactionProofId,
    @NotNull @Min(1) @Max(5) Float score,
    @Size(max = 1000) String comment,
    List<ReviewCriterionRequest> criteria
) {}

public record ReviewCriterionRequest(
    @NotNull ReviewCriterion.CriterionKey criterionKey,
    @NotNull @Min(1) @Max(5) Float score
) {}

// Avis complet
public record ReviewDto(
    UUID id,
    UUID programId,
    UserPublicDto reviewer,
    Float score,
    String comment,
    List<ReviewCriterionDto> criteria,
    Instant createdAt
) {}

// Résumé des avis d'un programme
public record ReviewSummaryDto(
    Float averageScore,
    Integer totalReviews,
    Map<String, Float> criteriaAverages, // clé = criterionKey
    List<ReviewDto> recentReviews
) {}
```

### ReviewService.java

```java
@Service
@Transactional
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ProgramRepository programRepository;
    private final ConversationRepository conversationRepository;
    private final HtmlSanitizer sanitizer;

    public ReviewDto createReview(UUID reviewerId, CreateReviewRequest request) {

        Program program = programRepository.findById(request.programId())
            .orElseThrow(() -> new NotFoundException("Programme introuvable."));

        // 1. L'auteur du programme ne peut pas s'auto-noter
        UUID programOwnerId = program.getUserActivity().getUser().getId();
        if (reviewerId.equals(programOwnerId)) {
            throw new ValidationException(
                "Vous ne pouvez pas noter votre propre programme.");
        }

        // 2. Vérifier l'interaction réelle (conversation entre reviewer et owner)
        conversationRepository
            .findDirectBetween(reviewerId, programOwnerId)
            .orElseThrow(() -> new InsufficientInteractionException(
                "Une interaction via le chat est requise avant de laisser un avis."));

        // 3. Vérifier unicité
        if (reviewRepository.existsByProgramIdAndReviewerId(
                request.programId(), reviewerId)) {
            throw new DuplicateException(
                "Vous avez déjà laissé un avis sur ce programme.");
        }

        // 4. Créer l'avis
        Review review = new Review();
        review.setProgram(program);
        review.setReviewer(userRepository.getReferenceById(reviewerId));
        review.setScore(request.score());
        if (request.comment() != null) {
            review.setComment(sanitizer.sanitize(request.comment()));
        }

        // 5. Ajouter les critères détaillés
        if (request.criteria() != null) {
            List<ReviewCriterion> criteria = request.criteria().stream()
                .map(c -> {
                    ReviewCriterion rc = new ReviewCriterion();
                    rc.setReview(review);
                    rc.setCriterionKey(c.criterionKey());
                    rc.setScore(c.score());
                    return rc;
                }).toList();
            review.setCriteria(criteria);
        }

        return toDto(reviewRepository.save(review));
    }

    public ReviewSummaryDto getProgramReviewSummary(UUID programId) {
        List<Review> reviews = reviewRepository
            .findByProgramIdOrderByCreatedAtDesc(programId);

        if (reviews.isEmpty()) {
            return new ReviewSummaryDto(null, 0, Map.of(), List.of());
        }

        float avgScore = (float) reviews.stream()
            .mapToDouble(r -> r.getScore()).average().orElse(0);

        // Calcul des moyennes par critère
        Map<String, Float> criteriaAverages = reviews.stream()
            .flatMap(r -> r.getCriteria().stream())
            .collect(Collectors.groupingBy(
                c -> c.getCriterionKey().name(),
                Collectors.collectingAndThen(
                    Collectors.averagingDouble(c -> c.getScore()),
                    d -> d.floatValue()
                )
            ));

        List<ReviewDto> recent = reviews.stream().limit(5)
            .map(this::toDto).toList();

        return new ReviewSummaryDto(avgScore, reviews.size(),
            criteriaAverages, recent);
    }
}
```

### ReviewController.java

```java
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    // POST /api/reviews
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewDto createReview(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateReviewRequest request) {
        return reviewService.createReview(principal.getId(), request);
    }

    // GET /api/reviews/programs/{programId}/summary
    @GetMapping("/programs/{programId}/summary")
    public ReviewSummaryDto getProgramSummary(@PathVariable UUID programId) {
        return reviewService.getProgramReviewSummary(programId);
    }

    // GET /api/reviews/programs/{programId}?page=&size=
    @GetMapping("/programs/{programId}")
    public Page<ReviewDto> getProgramReviews(
            @PathVariable UUID programId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return reviewService.getProgramReviews(programId,
            PageRequest.of(page, Math.min(size, 20)));
    }
}
```

---

## Module 4 — Signalements

### ReportController.java

```java
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    // POST /api/reports
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<Void> createReport(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateReportRequest request) {
        reportService.createReport(principal.getId(), request);
        // Toujours 201 — ne pas confirmer si la cible existe ou pas
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}

public record CreateReportRequest(
    @NotNull Report.TargetType targetType,
    @NotNull UUID targetId,
    @NotNull Report.ReportReason reason,
    @Size(max = 500) String details
) {}
```

---

## Récapitulatif des endpoints Phase 3

### Badges
| Méthode | Route | Description |
|---------|-------|-------------|
| GET | /api/badges | Tous les badges disponibles |
| GET | /api/badges/me | Mes badges |
| GET | /api/badges/users/{id} | Badges d'un utilisateur |

### Recommandations
| Méthode | Route | Description |
|---------|-------|-------------|
| POST | /api/recommendations | Recommander un utilisateur |
| GET  | /api/recommendations/me | Mes recommandations reçues |
| GET  | /api/recommendations/users/{id} | Recommandations d'un utilisateur |

### Avis
| Méthode | Route | Description |
|---------|-------|-------------|
| POST | /api/reviews | Laisser un avis sur un programme |
| GET  | /api/reviews/programs/{id}/summary | Résumé des avis |
| GET  | /api/reviews/programs/{id} | Liste des avis paginée |

### Signalements
| Méthode | Route | Description |
|---------|-------|-------------|
| POST | /api/reports | Signaler un contenu ou utilisateur |

