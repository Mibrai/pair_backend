# 🎯 Phase 3 - Plan d'Implémentation

**Phase**: Crédibilité & Confiance  
**Date**: 2026-06-23  
**Estimation**: 10-12 heures

---

## 📋 Vue d'Ensemble Phase 3

### Objectif
Construire la couche de confiance avec:
- **Badges**: Gamification et crédibilité
- **Recommandations**: Trust entre pairs
- **Avis**: Social proof sur programmes
- **Signalements**: Modération communautaire

### Règle Fondamentale
❗ **Toute recommandation ou avis nécessite une interaction prouvée** (conversation existante)

---

## Module 1: Badges (2-3h)

### ✅ Existant (Phase 1)
- Table `badges` ✅
- Table `badge_awards` ✅
- Entity `Badge.java` ✅
- Entity `BadgeAward.java` ✅
- Repository `BadgeRepository.java` ✅
- Repository `BadgeAwardRepository.java` ✅

### 🔨 À Implémenter

#### 1. BadgeService.java (1h)
```java
@Service
@Transactional
@RequiredArgsConstructor
public class BadgeService {
    
    // Méthodes à créer:
    - evaluateBadges(UUID userId)
    - isEligible(User user, Badge badge)
    - awardBadge(User user, Badge badge)
    - getUserBadges(UUID userId)
    - getAllBadges()
}
```

**Conditions à implémenter**:
- VERIFICATION (email, phone)
- RECOMMENDATION_COUNT (>= threshold)
- PROGRAM_COUNT (>= threshold)
- PROGRESSION_STREAK (>= threshold)
- ACTIVITY_DIVERSITY (>= threshold)

#### 2. BadgeController.java (30min)
```java
@RestController
@RequestMapping("/api/badges")
public class BadgeController {
    
    // Endpoints:
    GET  /api/badges              → Tous les badges
    GET  /api/badges/me           → Mes badges
    GET  /api/badges/users/{id}   → Badges d'un user
}
```

#### 3. DTOs (30min)
- `BadgeDto.java`
- `BadgeAwardDto.java`

#### 4. SQL Init Data (30min)
Créer badges par défaut:
```sql
INSERT INTO badges (code, name, description, icon_url, condition_type, condition_threshold)
VALUES
  ('VERIFIED_EMAIL', 'Email Vérifié', ..., 'VERIFICATION', 0),
  ('PROGRAM_CREATOR', 'Créateur', ..., 'PROGRAM_COUNT', 1),
  ('SUPER_HOST', 'Super Hôte', ..., 'PROGRAM_COUNT', 5),
  ('STREAK_7', 'Régulier', ..., 'PROGRESSION_STREAK', 7),
  ('STREAK_30', 'Assidu', ..., 'PROGRESSION_STREAK', 30),
  ('MULTI_SPORT', 'Polyvalent', ..., 'ACTIVITY_DIVERSITY', 3);
```

#### 5. Tests (30min)
- Test evaluateBadges()
- Test award conditions
- Test endpoints

---

## Module 2: Recommandations entre Pairs (3-4h)

### 🔨 À Créer Entièrement

#### 1. SQL Table (30min)
```sql
CREATE TABLE peer_recommendations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_user_id UUID NOT NULL REFERENCES users(id),
    to_user_id UUID NOT NULL REFERENCES users(id),
    interaction_proof_id UUID NOT NULL REFERENCES conversations(id),
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    CONSTRAINT uq_recommendation_pair UNIQUE(from_user_id, to_user_id),
    CONSTRAINT chk_no_self_recommend CHECK(from_user_id != to_user_id)
);

CREATE INDEX idx_recommendations_to_user ON peer_recommendations(to_user_id);
CREATE INDEX idx_recommendations_from_user ON peer_recommendations(from_user_id);
```

#### 2. Entity (30min)
- `PeerRecommendation.java`

#### 3. Repository (30min)
```java
public interface PeerRecommendationRepository {
    boolean existsByFromUserIdAndToUserId(UUID from, UUID to);
    List<PeerRecommendation> findByToUserIdOrderByCreatedAtDesc(UUID to);
    int countByToUserId(UUID to);
}
```

#### 4. DTOs (30min)
- `CreateRecommendationRequest.java`
- `PeerRecommendationDto.java`

#### 5. Service (1h)
```java
@Service
@Transactional
public class PeerRecommendationService {
    
    // Méthodes:
    - create(UUID fromUserId, CreateRecommendationRequest)
    - getRecommendationsForUser(UUID userId)
    - countRecommendations(UUID userId)
    
    // Validations:
    - Pas d'auto-recommandation
    - Conversation existe entre from et to
    - Unicité (une seule recommandation par paire)
    - Sanitize comment (HTML)
}
```

#### 6. Controller (30min)
```java
@RestController
@RequestMapping("/api/recommendations")
public class PeerRecommendationController {
    
    POST /api/recommendations        → Créer
    GET  /api/recommendations/me     → Mes recommandations reçues
    GET  /api/recommendations/users/{id} → Recommandations d'un user
}
```

#### 7. Tests (30min)
- Test création
- Test validation interaction prouvée
- Test unicité
- Test endpoints

---

## Module 3: Avis sur Programmes (3-4h)

### 🔨 À Créer Entièrement

#### 1. SQL Tables (30min)
```sql
CREATE TABLE reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    program_id UUID NOT NULL REFERENCES programs(id) ON DELETE CASCADE,
    reviewer_id UUID NOT NULL REFERENCES users(id),
    score FLOAT NOT NULL CHECK(score >= 1 AND score <= 5),
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    
    CONSTRAINT uq_review_per_program UNIQUE(program_id, reviewer_id)
);

CREATE TABLE review_criteria (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    criterion_key VARCHAR(50) NOT NULL,
    score FLOAT NOT NULL CHECK(score >= 1 AND score <= 5)
);

CREATE INDEX idx_reviews_program ON reviews(program_id);
CREATE INDEX idx_reviews_reviewer ON reviews(reviewer_id);
CREATE INDEX idx_criteria_review ON review_criteria(review_id);
```

#### 2. Entities (1h)
- `Review.java`
- `ReviewCriterion.java`
- `CriterionKey` enum

**CriterionKey values**:
- ORGANIZATION
- COMMUNICATION
- ATMOSPHERE
- LEVEL_MATCH
- LOCATION

#### 3. Repository (30min)
```java
public interface ReviewRepository {
    boolean existsByProgramIdAndReviewerId(UUID program, UUID reviewer);
    List<Review> findByProgramIdOrderByCreatedAtDesc(UUID programId);
    Page<Review> findByProgramId(UUID programId, Pageable pageable);
}
```

#### 4. DTOs (30min)
- `CreateReviewRequest.java`
- `ReviewCriterionRequest.java`
- `ReviewDto.java`
- `ReviewCriterionDto.java`
- `ReviewSummaryDto.java`

#### 5. Service (1.5h)
```java
@Service
@Transactional
public class ReviewService {
    
    // Méthodes:
    - createReview(UUID reviewerId, CreateReviewRequest)
    - getProgramReviews(UUID programId, Pageable)
    - getProgramReviewSummary(UUID programId)
    - updateReview(UUID reviewId, UpdateReviewRequest)
    - deleteReview(UUID reviewId, UUID userId)
    
    // Validations:
    - Pas d'auto-notation (reviewer != program owner)
    - Conversation existe entre reviewer et owner
    - Unicité (un seul avis par reviewer/program)
    - Sanitize comment (HTML)
    - Score 1-5
    
    // Summary calculation:
    - Average score
    - Total reviews
    - Criteria averages
    - Recent reviews (top 5)
}
```

#### 6. Controller (30min)
```java
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    
    POST /api/reviews                        → Créer avis
    GET  /api/reviews/programs/{id}/summary  → Résumé avis
    GET  /api/reviews/programs/{id}          → Liste paginée
    PUT  /api/reviews/{id}                   → Modifier (bonus)
    DELETE /api/reviews/{id}                 → Supprimer (bonus)
}
```

#### 7. Tests (30min)
- Test création
- Test validation interaction
- Test summary calculation
- Test endpoints

---

## Module 4: Signalements (1-2h)

### 🔨 À Créer Entièrement

#### 1. SQL Table (30min)
```sql
CREATE TABLE reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES users(id),
    target_type VARCHAR(20) NOT NULL, -- USER, PROGRAM, MESSAGE, REVIEW
    target_id UUID NOT NULL,
    reason VARCHAR(50) NOT NULL,
    details TEXT,
    status VARCHAR(20) DEFAULT 'PENDING',
    reviewed_by UUID REFERENCES users(id),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reports_status ON reports(status);
CREATE INDEX idx_reports_target ON reports(target_type, target_id);
CREATE INDEX idx_reports_reporter ON reports(reporter_id);
```

#### 2. Entity (30min)
- `Report.java`
- `TargetType` enum
- `ReportReason` enum
- `ReportStatus` enum

**TargetType**: USER, PROGRAM, MESSAGE, REVIEW
**ReportReason**: SPAM, INAPPROPRIATE, HARASSMENT, FAKE, OTHER
**ReportStatus**: PENDING, REVIEWED, ACTIONED, DISMISSED

#### 3. Repository & Service (30min)
```java
public interface ReportRepository {
    List<Report> findByStatusOrderByCreatedAtDesc(ReportStatus status);
    int countByTargetTypeAndTargetId(TargetType type, UUID id);
}

@Service
public class ReportService {
    Report createReport(UUID reporterId, CreateReportRequest);
    // Note: Admin endpoints pour review dans Phase 4
}
```

#### 4. DTOs & Controller (30min)
```java
public record CreateReportRequest(
    TargetType targetType,
    UUID targetId,
    ReportReason reason,
    String details
) {}

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    POST /api/reports → Créer signalement (201 toujours)
}
```

---

## 📊 Planning Détaillé

### Semaine 1 (6h)
**Jour 1** (3h):
- ✅ Module 1: Badges (complet)
  - BadgeService
  - BadgeController
  - DTOs
  - SQL init data
  - Tests

**Jour 2** (3h):
- ✅ Module 2: Recommandations (début)
  - SQL table
  - Entity
  - Repository
  - DTOs

### Semaine 2 (6h)
**Jour 3** (3h):
- ✅ Module 2: Recommandations (fin)
  - Service complet
  - Controller
  - Tests

**Jour 4** (3h):
- ✅ Module 3: Avis (début)
  - SQL tables
  - Entities
  - Repository
  - DTOs

### Semaine 3 (3h)
**Jour 5** (3h):
- ✅ Module 3: Avis (fin)
  - Service complet
  - Controller
  - Tests

### Bonus (1-2h)
**Si temps disponible**:
- ✅ Module 4: Signalements
  - SQL table
  - Entity
  - Service
  - Controller

---

## 🧪 Tests à Créer

### Tests Unitaires
- BadgeService (conditions)
- PeerRecommendationService (validations)
- ReviewService (summary calculation)

### Tests Intégration
- Endpoints badges
- Endpoints recommandations
- Endpoints avis
- Endpoints signalements

### Tests End-to-End
- Scénario complet: User A recommande User B
- Scénario complet: User X laisse avis sur programme Y
- Validation interaction prouvée

### Scripts de Test
```bash
# À créer
test-badges.sh
test-recommendations.sh
test-reviews.sh
test-reports.sh
```

---

## 📝 Notes Importantes

### Interaction Prouvée
**Règle critique**: Pour recommander un user ou noter un programme, il FAUT:
1. Une conversation existe dans `conversations`
2. Les deux users sont membres (`conversation_members`)
3. Au moins 1 message échangé (optionnel mais recommandé)

**Implémentation**:
```java
conversationRepository.findDirectBetween(userId1, userId2)
    .orElseThrow(() -> new InsufficientInteractionException(...));
```

### HTML Sanitization
Tous les champs texte utilisateur doivent être sanitizés:
```java
@Service
public class HtmlSanitizer {
    private final PolicyFactory policy = Sanitizers.FORMATTING
        .and(Sanitizers.LINKS);
    
    public String sanitize(String html) {
        return policy.sanitize(html);
    }
}
```

### Badge Evaluation
Appeler `badgeService.evaluateBadges(userId)` après:
- User crée un programme
- User reçoit une recommandation
- User atteint un streak milestone
- User ajoute une nouvelle activité

**Solution**: Event listeners ou appels directs

---

## 🎯 Livrable Final Phase 3

### Code
- ✅ 4 modules complets
- ✅ ~15 nouveaux fichiers Java
- ✅ 4 nouvelles tables SQL
- ✅ 10+ nouveaux endpoints

### Tests
- ✅ Scripts automatisés
- ✅ Tests unitaires
- ✅ Tests intégration

### Documentation
- ✅ PHASE3_COMPLETE.md
- ✅ API endpoints documentés
- ✅ Règles métier expliquées

---

## 🚀 Après Phase 3

### Options
1. **Phase 4**: Notifications + Performance
2. **Finalisation**: Tests complets + Déploiement
3. **Optimisations**: pgvector + S3 + Redis

**Recommandation**: Finaliser Phase 3 → Déploiement MVP → Phase 4

---

## ✅ Checklist Avant Démarrage

Phase 2 Requirements:
- [x] Phase 1 complète
- [x] Phase 2 complète
- [x] Tables badges existantes
- [x] Conversations fonctionnelles
- [x] Application stable

Outils Prêts:
- [x] PostgreSQL 18.4
- [x] Spring Boot 4.1
- [x] HTML Sanitizer (dépendance)
- [x] Tests automatisés (framework)

**Status**: ✅ PRÊT POUR PHASE 3!
