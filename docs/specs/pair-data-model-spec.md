# Pair — Spécification du modèle de données
## Instructions pour Claude Code

Ce document décrit le modèle de données complet du réseau social **Pair**.
Implémente chaque entité JPA, les repositories Spring Data, les migrations Liquibase/Flyway,
et les index PostgreSQL décrits ci-dessous.

---

## Stack technique cible

- **Java 21** + **Spring Boot 3.x**
- **Spring Data JPA** + **Hibernate 6**
- **PostgreSQL 16** avec extensions **PostGIS** et **pgvector**
- **Hibernate Spatial** (via `hibernate-spatial`) pour les types géographiques
- **Flyway** pour les migrations SQL
- UUIDs générés côté base (`gen_random_uuid()`)
- Auditing Spring Data (`@CreatedDate`, `@LastModifiedDate`)

---

## Extensions PostgreSQL à activer (migration V1)

```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;
```

---

## Dépendances Maven à ajouter (pom.xml)

```xml
<!-- PostGIS / Hibernate Spatial -->
<dependency>
  <groupId>org.hibernate.orm</groupId>
  <artifactId>hibernate-spatial</artifactId>
</dependency>

<!-- pgvector -->
<dependency>
  <groupId>com.pgvector</groupId>
  <artifactId>pgvector</artifactId>
  <version>0.1.6</version>
</dependency>

<!-- Spring Security -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- Validation -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- WebSocket -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>

<!-- Flyway -->
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
</dependency>
```

---

## Configuration application.properties

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/pair_db
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

---

## Domaine 1 — Utilisateur & Identité

### Enum : VerificationStatus

```java
public enum VerificationStatus {
    UNVERIFIED, EMAIL_VERIFIED, PHONE_VERIFIED, ID_VERIFIED
}
```

### Entité : User

```java
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_email", columnList = "email"),
    @Index(name = "idx_users_last_active", columnList = "last_active_at"),
    @Index(name = "idx_users_location", columnList = "location", using = "GIST")
})
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    @Email
    @NotBlank
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(name = "display_name", nullable = false, length = 80)
    @NotBlank
    @Size(max = 80)
    private String displayName;

    @Column(length = 1000)
    @Size(max = 1000)
    private String bio; // Toujours échapper côté service avant affichage (anti-XSS)

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl; // Chemin S3 après ré-encodage obligatoire

    // Géolocalisation flouttée (PostGIS Point SRID 4326)
    @Column(columnDefinition = "geometry(Point,4326)")
    private org.locationtech.jts.geom.Point location;

    @Column(name = "blur_radius_m", nullable = false)
    private Integer blurRadiusM = 500; // Rayon de floutage en mètres

    @Column(name = "location_public", nullable = false)
    private Boolean locationPublic = false;

    @Column(name = "online_status_visible", nullable = false)
    private Boolean onlineStatusVisible = false;

    @Column(name = "receive_messages", nullable = false)
    private Boolean receiveMessages = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 30)
    private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true; // Suppression douce

    // Relations
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UserActivity> userActivities = new ArrayList<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NotificationPref> notificationPrefs = new ArrayList<>();
}
```

### Migration SQL : users (Flyway V2)

```sql
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    phone           VARCHAR(20),
    display_name    VARCHAR(80) NOT NULL,
    bio             VARCHAR(1000),
    avatar_url      VARCHAR(500),
    location        GEOMETRY(Point, 4326),
    blur_radius_m   INTEGER NOT NULL DEFAULT 500,
    location_public BOOLEAN NOT NULL DEFAULT FALSE,
    online_status_visible BOOLEAN NOT NULL DEFAULT FALSE,
    receive_messages BOOLEAN NOT NULL DEFAULT TRUE,
    verification_status VARCHAR(30) NOT NULL DEFAULT 'UNVERIFIED',
    verified_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_active_at  TIMESTAMPTZ,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_last_active ON users(last_active_at);
CREATE INDEX idx_users_location ON users USING GIST(location);
```

### Repository : UserRepository

```java
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    // Recherche des utilisateurs visibles dans un rayon (PostGIS)
    @Query(value = """
        SELECT u.* FROM users u
        WHERE u.is_active = true
          AND u.location_public = true
          AND ST_DWithin(
              u.location::geography,
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              :radiusMeters
          )
        ORDER BY ST_Distance(
            u.location::geography,
            ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
        )
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<User> findVisibleUsersInRadius(
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    // Utilisateurs actifs récemment (statut en ligne)
    @Query("SELECT u FROM User u WHERE u.id IN :ids AND u.lastActiveAt > :since AND u.onlineStatusVisible = true")
    List<User> findOnlineUsers(@Param("ids") List<UUID> ids, @Param("since") Instant since);
}
```

---

## Domaine 2 — Activités & Programmes

### Entité : Category

```java
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 80)
    @NotBlank
    private String name;

    @Column(length = 80)
    private String icon;

    @Column(name = "color_ramp", length = 30)
    private String colorRamp;

    @OneToMany(mappedBy = "category")
    private List<Activity> activities = new ArrayList<>();
}
```

### Entité : Activity

```java
@Entity
@Table(name = "activities", indexes = {
    @Index(name = "idx_activities_slug", columnList = "slug"),
    @Index(name = "idx_activities_category", columnList = "category_id")
})
public class Activity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Activity parent; // Hiérarchie auto-référencée

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 120)
    @NotBlank
    private String name;

    @Column(nullable = false, unique = true, length = 150)
    private String slug;

    @Column(length = 500)
    private String description;

    // Vecteur sémantique pgvector (1536 dimensions — OpenAI/Anthropic compatible)
    // Stocker via pgvector-java : PGvector type
    @Column(columnDefinition = "vector(1536)")
    private float[] embedding;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "activity")
    private List<UserActivity> userActivities = new ArrayList<>();
}
```

### Enums : ActivityLevel, ActivityFormat

```java
public enum ActivityLevel { BEGINNER, INTERMEDIATE, ADVANCED, ANY }
public enum ActivityFormat { SOLO, DUO, GROUP, ANY }
```

### Entité : UserActivity (table de jonction enrichie)

```java
@Entity
@Table(name = "user_activities",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "activity_id"}),
    indexes = {
        @Index(name = "idx_ua_user", columnList = "user_id"),
        @Index(name = "idx_ua_activity", columnList = "activity_id"),
        @Index(name = "idx_ua_visible", columnList = "visible_on_map")
    }
)
@EntityListeners(AuditingEntityListener.class)
public class UserActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false)
    private Activity activity;

    @Column(name = "visible_on_map", nullable = false)
    private Boolean visibleOnMap = true;

    @Column(name = "custom_description", length = 500)
    @Size(max = 500)
    private String customDescription; // Échapper côté service

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ActivityLevel level = ActivityLevel.ANY;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ActivityFormat format = ActivityFormat.ANY;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "userActivity", cascade = CascadeType.ALL)
    private List<Program> programs = new ArrayList<>();
}
```

### Enums : ProgramStatus, PlaceType

```java
public enum ProgramStatus { DRAFT, ACTIVE, PAUSED, ARCHIVED }
public enum PlaceType { PUBLIC, PRIVATE }
```

### Entité : Program

```java
@Entity
@Table(name = "programs", indexes = {
    @Index(name = "idx_programs_user_activity", columnList = "user_activity_id"),
    @Index(name = "idx_programs_status", columnList = "status"),
    @Index(name = "idx_programs_archived", columnList = "archived_at")
})
@EntityListeners(AuditingEntityListener.class)
public class Program {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_activity_id", nullable = false)
    private UserActivity userActivity;

    @Column(nullable = false, length = 150)
    @NotBlank
    @Size(max = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    @Size(max = 3000)
    private String description; // Échapper côté service

    @Column(columnDefinition = "vector(1536)")
    private float[] embedding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProgramStatus status = ProgramStatus.DRAFT;

    @Column(name = "is_public", nullable = false)
    private Boolean isPublic = true;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "program", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startsAt ASC")
    private List<Schedule> schedules = new ArrayList<>();

    @OneToMany(mappedBy = "program", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<ProgramMedia> media = new ArrayList<>();

    @OneToMany(mappedBy = "program")
    private List<ProgressionEntry> progressions = new ArrayList<>();

    @OneToMany(mappedBy = "program")
    private List<Review> reviews = new ArrayList<>();
}
```

### Entité : Schedule

```java
@Entity
@Table(name = "schedules", indexes = {
    @Index(name = "idx_schedules_program", columnList = "program_id"),
    @Index(name = "idx_schedules_starts_at", columnList = "starts_at"),
    @Index(name = "idx_schedules_location", columnList = "location", using = "GIST")
})
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    @Column(name = "place_name", nullable = false, length = 200)
    @NotBlank
    private String placeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "place_type", nullable = false, length = 10)
    private PlaceType placeType;

    // PostGIS Point
    @Column(columnDefinition = "geometry(Point,4326)", nullable = false)
    private org.locationtech.jts.geom.Point location;

    // Adresse en clair uniquement si placeType == PUBLIC
    @Column(name = "address_public", length = 300)
    private String addressPublic;

    // Décision explicite de l'utilisateur pour un lieu privé
    @Column(name = "show_exact_address", nullable = false)
    private Boolean showExactAddress = false;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    // Format RFC 5545 : "RRULE:FREQ=WEEKLY;BYDAY=SA"
    @Column(name = "recurrence_rule", length = 200)
    private String recurrenceRule;

    @Column(name = "max_participants")
    @Min(1)
    private Integer maxParticipants;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
```

### Entité : ProgramMedia

```java
@Entity
@Table(name = "program_media", indexes = {
    @Index(name = "idx_media_program", columnList = "program_id")
})
public class ProgramMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    @Column(nullable = false, length = 500)
    private String url; // Chemin S3, jamais URL externe brute

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 10)
    private MediaType mediaType; // IMAGE ou VIDEO

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum MediaType { IMAGE, VIDEO }
}
```

### Entité : ProgressionEntry

```java
@Entity
@Table(name = "progression_entries", indexes = {
    @Index(name = "idx_progression_program", columnList = "program_id"),
    @Index(name = "idx_progression_user", columnList = "user_id"),
    @Index(name = "idx_progression_created", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
public class ProgressionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(length = 150)
    @Size(max = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    @Size(max = 2000)
    private String content; // Échapper côté service

    // Métriques numériques libres [distance_km, duration_min, reps, ...]
    @Column(columnDefinition = "float[]")
    private float[] metrics;

    @Column(name = "is_public", nullable = false)
    private Boolean isPublic = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
```

### Repository : ProgramRepository

```java
@Repository
public interface ProgramRepository extends JpaRepository<Program, UUID> {

    // Recherche sémantique par vecteur (pgvector cosine distance)
    @Query(value = """
        SELECT p.* FROM programs p
        JOIN user_activities ua ON p.user_activity_id = ua.id
        JOIN users u ON ua.user_id = u.id
        WHERE p.status = 'ACTIVE'
          AND p.is_public = true
          AND u.is_active = true
          AND ua.visible_on_map = true
          AND ST_DWithin(
              u.location::geography,
              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
              :radiusMeters
          )
        ORDER BY p.embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<Program> semanticSearchInRadius(
        @Param("queryEmbedding") String queryEmbedding, // format "[0.1, 0.2, ...]"
        @Param("lat") double lat,
        @Param("lng") double lng,
        @Param("radiusMeters") int radiusMeters,
        @Param("limit") int limit
    );

    List<Program> findByUserActivityUserIdAndStatusNot(UUID userId, ProgramStatus status);
}
```

---

## Domaine 3 — Chat en temps réel

### Enums : ConversationType, MessageStatus

```java
public enum ConversationType { DIRECT, GROUP }
public enum MessageStatus { SENT, DELIVERED, READ }
```

### Entité : Conversation

```java
@Entity
@Table(name = "conversations", indexes = {
    @Index(name = "idx_conv_last_message", columnList = "last_message_at")
})
@EntityListeners(AuditingEntityListener.class)
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ConversationType type = ConversationType.DIRECT;

    // Activité qui a conduit à ce contact (contexte affiché dans le chat)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_context_id")
    private Activity activityContext;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL)
    private List<Message> messages = new ArrayList<>();

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL)
    private List<ConversationMember> members = new ArrayList<>();
}
```

### Entité : ConversationMember

```java
@Entity
@Table(name = "conversation_members",
    uniqueConstraints = @UniqueConstraint(columnNames = {"conversation_id", "user_id"})
)
public class ConversationMember {

    @EmbeddedId
    private ConversationMemberId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("conversationId")
    @JoinColumn(name = "conversation_id")
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    @Column(name = "last_read_at")
    private Instant lastReadAt; // Pour calcul messages non lus

    @Embeddable
    public static class ConversationMemberId implements Serializable {
        private UUID conversationId;
        private UUID userId;
    }
}
```

### Entité : Message

```java
@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_messages_conversation", columnList = "conversation_id"),
    @Index(name = "idx_messages_sender", columnList = "sender_id"),
    @Index(name = "idx_messages_sent_at", columnList = "sent_at")
})
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @Column(nullable = false, length = 4000)
    @NotBlank
    @Size(max = 4000)
    private String content; // Échapper OBLIGATOIREMENT côté service (anti-XSS)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private MessageStatus status = MessageStatus.SENT;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @Column(name = "read_at")
    private Instant readAt;
}
```

---

## Domaine 4 — Crédibilité & Confiance

### Entité : Review

```java
@Entity
@Table(name = "reviews",
    uniqueConstraints = @UniqueConstraint(columnNames = {"program_id", "reviewer_id"}),
    indexes = {
        @Index(name = "idx_reviews_program", columnList = "program_id"),
        @Index(name = "idx_reviews_reviewer", columnList = "reviewer_id")
    }
)
@EntityListeners(AuditingEntityListener.class)
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private User reviewer;

    // Preuve d'interaction réelle obligatoire (conversation existante)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interaction_proof_id", nullable = false)
    private Conversation interactionProof;

    @Column(nullable = false)
    @Min(1) @Max(5)
    private Float score;

    @Column(length = 1000)
    @Size(max = 1000)
    private String comment; // Échapper côté service

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReviewCriterion> criteria = new ArrayList<>();
}
```

### Entité : ReviewCriterion

```java
@Entity
@Table(name = "review_criteria")
public class ReviewCriterion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Enumerated(EnumType.STRING)
    @Column(name = "criterion_key", nullable = false, length = 30)
    private CriterionKey criterionKey;

    @Column(nullable = false)
    @Min(1) @Max(5)
    private Float score;

    public enum CriterionKey {
        AMBIANCE, LEVEL_FIT, PUNCTUALITY, WELCOME
    }
}
```

### Entité : PeerRecommendation

```java
@Entity
@Table(name = "peer_recommendations",
    uniqueConstraints = @UniqueConstraint(columnNames = {"from_user_id", "to_user_id"}),
    indexes = {
        @Index(name = "idx_peer_rec_to", columnList = "to_user_id"),
        @Index(name = "idx_peer_rec_from", columnList = "from_user_id")
    }
)
@EntityListeners(AuditingEntityListener.class)
public class PeerRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_user_id", nullable = false)
    private User fromUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_user_id", nullable = false)
    private User toUser;

    // Preuve d'interaction réelle obligatoire
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interaction_proof_id", nullable = false)
    private Conversation interactionProof;

    @Column(length = 500)
    @Size(max = 500)
    private String comment; // Toujours positif — jamais de note négative

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
```

### Enums : BadgeCategory, BadgeConditionType

```java
public enum BadgeCategory { TRUST, ACHIEVEMENT, ROLE }
public enum BadgeConditionType {
    VERIFICATION,
    SESSION_COUNT,
    RECOMMENDATION_COUNT,
    PROGRAM_COUNT,
    PROGRESSION_STREAK,
    ACTIVITY_DIVERSITY
}
```

### Entité : Badge

```java
@Entity
@Table(name = "badges")
public class Badge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 60)
    private String code; // Ex: "VERIFIED_EMAIL", "50_SESSIONS"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BadgeCategory category;

    @Column(nullable = false, length = 120)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false, length = 40)
    private BadgeConditionType conditionType;

    @Column(name = "condition_threshold")
    private Integer conditionThreshold;

    @Column(length = 80)
    private String icon;
}
```

### Entité : BadgeAward

```java
@Entity
@Table(name = "badge_awards",
    uniqueConstraints = @UniqueConstraint(columnNames = {"badge_id", "user_id"})
)
public class BadgeAward {

    @EmbeddedId
    private BadgeAwardId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("badgeId")
    @JoinColumn(name = "badge_id")
    private Badge badge;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "awarded_at", nullable = false)
    private Instant awardedAt = Instant.now();

    @Embeddable
    public static class BadgeAwardId implements Serializable {
        private UUID badgeId;
        private UUID userId;
    }
}
```

---

## Domaine 5 — Notifications

### Enums : NotificationType, NotificationChannel, NotificationFrequency

```java
public enum NotificationType {
    NEW_MESSAGE,
    NEW_MATCH,          // Nouvelle personne proche avec activité commune
    PROGRAM_REVIEW,     // Avis reçu sur un programme
    BADGE_EARNED,
    CRENEAU_REMINDER,   // Rappel avant un créneau
    NEW_FOLLOWER,
    PEER_RECOMMENDATION,
    ACCOUNT_VERIFICATION,
    PASSWORD_RESET
}

public enum NotificationChannel { EMAIL, PUSH, IN_APP }

public enum NotificationFrequency { IMMEDIATE, DAILY_DIGEST, WEEKLY }
```

### Entité : Notification

```java
@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notif_user", columnList = "user_id"),
    @Index(name = "idx_notif_sent_at", columnList = "sent_at"),
    @Index(name = "idx_notif_is_read", columnList = "is_read")
})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NotificationChannel channel;

    // Données contextuelles variables selon le type
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @Column(name = "read_at")
    private Instant readAt;
}
```

### Entité : NotificationPref

```java
@Entity
@Table(name = "notification_prefs",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "notification_type"})
)
public class NotificationPref {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 40)
    private NotificationType notificationType;

    @Column(name = "email_enabled", nullable = false)
    private Boolean emailEnabled = true;

    @Column(name = "push_enabled", nullable = false)
    private Boolean pushEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationFrequency frequency = NotificationFrequency.IMMEDIATE;
}
```

---

## Domaine 6 — Sécurité & Administration

### Entité : Report

```java
@Entity
@Table(name = "reports", indexes = {
    @Index(name = "idx_reports_reporter", columnList = "reporter_id"),
    @Index(name = "idx_reports_target", columnList = "target_type, target_id"),
    @Index(name = "idx_reports_status", columnList = "status")
})
@EntityListeners(AuditingEntityListener.class)
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId; // Référence polymorphe

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status = ReportStatus.OPEN;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public enum TargetType { USER, PROGRAM, MESSAGE, REVIEW }
    public enum ReportReason { SPAM, HARASSMENT, FAKE_PROFILE, INAPPROPRIATE, OTHER }
    public enum ReportStatus { OPEN, IN_REVIEW, RESOLVED, DISMISSED }
}
```

### Entité : SearchLog

```java
@Entity
@Table(name = "search_logs", indexes = {
    @Index(name = "idx_search_log_user", columnList = "user_id"),
    @Index(name = "idx_search_log_created", columnList = "created_at")
})
public class SearchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id") // Nullable (recherche anonyme possible)
    private User user;

    @Column(name = "raw_query", nullable = false, length = 500)
    @NotBlank
    private String rawQuery;

    // Intent structuré retourné par le LLM {"activity": "yoga", "radius": 5000, ...}
    @Column(name = "parsed_intent", columnDefinition = "jsonb")
    private String parsedIntent;

    // Vecteur de la requête pour analyse des tendances
    @Column(name = "query_embedding", columnDefinition = "vector(1536)")
    private float[] queryEmbedding;

    @Column(name = "results_count")
    private Integer resultsCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
```

---

## Règles métier critiques à implémenter côté service

### Règles de visibilité (à vérifier AVANT toute réponse API)

```java
// 1. Jamais afficher l'adresse privée sans consentement explicite
public String resolveDisplayAddress(Schedule schedule) {
    if (schedule.getPlaceType() == PlaceType.PUBLIC) {
        return schedule.getAddressPublic();
    }
    if (Boolean.TRUE.equals(schedule.getShowExactAddress())) {
        return schedule.getAddressPublic();
    }
    return null; // Afficher uniquement la zone flouttée côté client
}

// 2. Jamais retourner un utilisateur masqué dans les résultats de recherche
// Appliquer avant génération de toute réponse chatbot
public boolean isUserSearchable(User user) {
    return user.getIsActive()
        && user.getLocationPublic()
        && user.getVerificationStatus() != VerificationStatus.UNVERIFIED;
}

// 3. Un avis ou recommandation nécessite une interaction prouvée
public void validateInteractionProof(UUID reviewerId, UUID programOwnerId) {
    boolean hasConversation = conversationRepository
        .existsBetweenUsers(reviewerId, programOwnerId);
    if (!hasConversation) {
        throw new InsufficientInteractionException(
            "Une interaction réelle est requise avant de laisser un avis."
        );
    }
}
```

### Sécurité des champs texte (à appliquer sur tout champ sanitized)

```java
// Utiliser une bibliothèque type OWASP Java HTML Sanitizer
// Dépendance : com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

@Component
public class HtmlSanitizer {
    private static final PolicyFactory POLICY = Sanitizers.FORMATTING
        .and(Sanitizers.LINKS)
        .and(Sanitizers.BLOCKS);

    public String sanitize(String input) {
        if (input == null) return null;
        return POLICY.sanitize(input);
    }
}
// Appeler sanitizer.sanitize() sur : bio, customDescription,
// program.description, message.content, comment, progressionEntry.content
```

---

## Index pgvector à créer (migration SQL)

```sql
-- Index HNSW pour la recherche sémantique (meilleur rappel que ivfflat)
CREATE INDEX idx_activities_embedding
    ON activities USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX idx_programs_embedding
    ON programs USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX idx_search_logs_embedding
    ON search_logs USING hnsw (query_embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

---

## Ordre d'implémentation recommandé (Phase 1 en priorité)

```
1. Extensions PostgreSQL + migration V1
2. Entités : User, Category, Activity, UserActivity
3. Spring Security + authentification JWT
4. Endpoints : /auth/register, /auth/login, /auth/verify-email
5. Endpoints : /users/me (CRUD profil), /users/me/activities
6. Entités : Program, Schedule, ProgramMedia
7. Endpoints : /programs (CRUD), /schedules (CRUD)
8. UserRepository.findVisibleUsersInRadius (requête PostGIS)
9. Endpoint : /map/users?lat=&lng=&radius=&activityId=
10. Entités : Conversation, ConversationMember, Message
11. Spring WebSocket + STOMP : /ws/chat
12. Endpoints : /conversations, /messages
13. Entités : Notification, NotificationPref
14. Service email transactionnel (vérification, mot de passe)
```

