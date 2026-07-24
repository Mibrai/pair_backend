# meetDo — Évolution stratégique : BACKEND
## Spécification d'implémentation pour Claude Code

> **Contexte** : le backend Spring Boot existe déjà (phases 1 à 4 implémentées,
> déployé sur Railway). Cette spec ajoute les briques qui différencient meetDo
> de Strava : le créneau comme objet central, la boucle de confirmation après
> rencontre, la régularité déclarative, et les alertes par activité.
>
> **Nom des packages** : le code existant utilise `org.program.pair`. On
> conserve ce namespace (le renommage produit → meetDo ne justifie pas une
> refonte des packages). Seuls les libellés utilisateur mentionnent "meetDo".
>
> **Principe directeur non négociable** : aucune fonctionnalité de classement
> entre personnes. La métrique de valeur est le **nombre de partenaires
> différents** et la **régularité**, jamais un score comparatif.

---

## Vue d'ensemble des ajouts

```
Nouvelles entités
├── SlotParticipation   ← qui a rejoint quel créneau
├── Attendance          ← confirmation "j'y étais" après le créneau
└── ActivityAlert       ← alerte quand quelqu'un arrive sur une activité/zone

Entités modifiées
├── Schedule            ← devient un créneau ouvert et rejoignable
└── User                ← compteurs dénormalisés (partenaires, série)

Nouveaux services
├── SlotService         ← rejoindre/quitter, feed "autour de moi"
├── AttendanceService   ← boucle de confirmation mutuelle
├── PracticeStatsService← régularité, série, partenaires distincts
└── ActivityAlertService← création, matching, déclenchement
```

---

## Partie 1 — Le créneau comme objet central

### 1.1 Modifier l'entité `Schedule`

Ajouter les champs suivants à l'entité existante :

```java
// Un créneau peut être ouvert à d'autres personnes (le coeur du produit)
@Column(name = "is_open_to_partners", nullable = false)
private Boolean isOpenToPartners = true;

// Statut du créneau dans son cycle de vie
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
private SlotStatus status = SlotStatus.OPEN;

// Nombre de participants confirmés (dénormalisé pour éviter un COUNT à chaque affichage)
@Column(name = "participant_count", nullable = false)
private Integer participantCount = 0;

// Message d'accueil libre affiché aux personnes intéressées
@Column(name = "welcome_note", length = 300)
private String welcomeNote;

@OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
private List<SlotParticipation> participations = new ArrayList<>();
```

```java
public enum SlotStatus { OPEN, FULL, CANCELLED, PAST }
```

### 1.2 Nouvelle entité `SlotParticipation`

```java
@Entity
@Table(name = "slot_participations",
    uniqueConstraints = @UniqueConstraint(columnNames = {"schedule_id", "user_id"}),
    indexes = {
        @Index(name = "idx_slotpart_schedule", columnList = "schedule_id"),
        @Index(name = "idx_slotpart_user", columnList = "user_id"),
        @Index(name = "idx_slotpart_status", columnList = "status")
    }
)
@EntityListeners(AuditingEntityListener.class)
public class SlotParticipation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParticipationStatus status = ParticipationStatus.INTERESTED;

    // Message optionnel envoyé en rejoignant ("je débute, ça vous va ?")
    @Column(name = "join_message", length = 300)
    private String joinMessage; // sanitized

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public enum ParticipationStatus {
        INTERESTED,  // a manifesté son intérêt
        CONFIRMED,   // l'hôte a validé (ou validation auto si pas de limite)
        DECLINED,    // l'hôte a refusé
        WITHDRAWN    // le participant s'est retiré
    }
}
```

### 1.3 Migration Flyway

```sql
-- V21__slots_and_participation.sql

ALTER TABLE schedules
    ADD COLUMN is_open_to_partners BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN participant_count   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN welcome_note        VARCHAR(300);

CREATE INDEX idx_schedules_status ON schedules(status);
CREATE INDEX idx_schedules_open   ON schedules(is_open_to_partners, starts_at);

CREATE TABLE slot_participations (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id  UUID NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES users(id),
    status       VARCHAR(20) NOT NULL DEFAULT 'INTERESTED',
    join_message VARCHAR(300),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_slot_user UNIQUE (schedule_id, user_id)
);

CREATE INDEX idx_slotpart_schedule ON slot_participations(schedule_id);
CREATE INDEX idx_slotpart_user     ON slot_participations(user_id);
CREATE INDEX idx_slotpart_status   ON slot_participations(status);
```

### 1.4 DTOs

```java
// Créneau affiché dans le feed "autour de moi"
public record SlotFeedItemDto(
    UUID scheduleId,
    UUID programId,
    String programTitle,
    String activityName,
    String categoryColorRamp,
    String level,
    String format,
    UserPublicDto host,
    String placeName,
    String displayAddress,   // null si lieu privé non partagé
    Double lat,              // null si lieu privé non partagé
    Double lng,
    Double distanceMeters,
    Instant startsAt,
    Instant endsAt,
    Integer maxParticipants,
    Integer participantCount,
    Boolean isOpenToPartners,
    String welcomeNote,
    String myParticipationStatus // null si je n'ai pas rejoint
) {}

public record JoinSlotRequest(
    @Size(max = 300) String joinMessage
) {}

public record SlotFeedRequest(
    @NotNull Double lat,
    @NotNull Double lng,
    @NotNull @Min(500) @Max(50000) Integer radiusMeters,
    UUID activityId,        // filtre optionnel
    UUID categoryId,        // filtre optionnel
    Instant from,           // défaut : maintenant
    Instant to              // défaut : maintenant + 7 jours
) {}
```

### 1.5 `SlotService`

```java
@Service
@Transactional
@RequiredArgsConstructor
public class SlotService {

    private final ScheduleRepository scheduleRepository;
    private final SlotParticipationRepository participationRepository;
    private final UserRepository userRepository;
    private final ChatService chatService;
    private final NotificationService notificationService;
    private final HtmlSanitizer sanitizer;

    /**
     * Feed "autour de moi" — le coeur du produit.
     * Retourne les créneaux ouverts, à venir, dans le rayon demandé.
     * RÈGLE DE VISIBILITÉ : ne retourne jamais un créneau dont l'hôte est
     * inactif, ni dont l'activité est masquée (visibleOnMap = false).
     */
    @Transactional(readOnly = true)
    public List<SlotFeedItemDto> getSlotFeed(SlotFeedRequest request, UUID requesterId) {
        Instant from = request.from() != null ? request.from() : Instant.now();
        Instant to   = request.to()   != null ? request.to()
                                              : Instant.now().plus(7, ChronoUnit.DAYS);

        List<Schedule> slots = scheduleRepository.findOpenSlotsInRadius(
            request.lat(), request.lng(), request.radiusMeters(),
            from, to, request.activityId(), request.categoryId(), 100);

        return slots.stream()
            .filter(s -> !s.getProgram().getUserActivity().getUser().getId().equals(requesterId))
            .map(s -> toFeedItem(s, request.lat(), request.lng(), requesterId))
            .toList();
    }

    /**
     * Rejoindre un créneau.
     * Effet de bord clé : ouvre automatiquement une conversation avec l'hôte,
     * contextualisée par l'activité — c'est ce qui transforme une intention
     * en rencontre réelle.
     */
    public SlotFeedItemDto joinSlot(UUID userId, UUID scheduleId, JoinSlotRequest request) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new NotFoundException("Créneau introuvable."));

        User host = slot.getProgram().getUserActivity().getUser();

        if (host.getId().equals(userId)) {
            throw new ValidationException("Vous ne pouvez pas rejoindre votre propre créneau.");
        }
        if (!Boolean.TRUE.equals(slot.getIsOpenToPartners())) {
            throw new ValidationException("Ce créneau n'est pas ouvert aux partenaires.");
        }
        if (slot.getStatus() != SlotStatus.OPEN) {
            throw new ValidationException("Ce créneau n'accepte plus de participants.");
        }
        if (slot.getStartsAt().isBefore(Instant.now())) {
            throw new ValidationException("Ce créneau est déjà passé.");
        }
        if (participationRepository.existsByScheduleIdAndUserId(scheduleId, userId)) {
            throw new DuplicateException("Vous avez déjà rejoint ce créneau.");
        }

        SlotParticipation participation = new SlotParticipation();
        participation.setSchedule(slot);
        participation.setUser(userRepository.getReferenceById(userId));
        // Validation auto s'il reste de la place et pas de limite stricte
        participation.setStatus(ParticipationStatus.CONFIRMED);
        if (request.joinMessage() != null) {
            participation.setJoinMessage(sanitizer.sanitize(request.joinMessage()));
        }
        participationRepository.save(participation);

        // Mettre à jour le compteur + le statut si complet
        slot.setParticipantCount(slot.getParticipantCount() + 1);
        if (slot.getMaxParticipants() != null
                && slot.getParticipantCount() >= slot.getMaxParticipants()) {
            slot.setStatus(SlotStatus.FULL);
        }
        scheduleRepository.save(slot);

        // Ouvrir la conversation contextualisée (respecte receiveMessages de l'hôte)
        if (Boolean.TRUE.equals(host.getReceiveMessages())) {
            chatService.createConversation(userId, new CreateConversationRequest(
                host.getId(),
                slot.getProgram().getUserActivity().getActivity().getId()
            ));
        }

        notificationService.notify(host.getId(), NotificationType.SLOT_JOINED, Map.of(
            "scheduleId", scheduleId.toString(),
            "programTitle", slot.getProgram().getTitle(),
            "participantName", userRepository.findById(userId)
                .map(User::getDisplayName).orElse("Quelqu'un")
        ));

        return toFeedItem(slot, null, null, userId);
    }

    public void leaveSlot(UUID userId, UUID scheduleId) {
        SlotParticipation participation = participationRepository
            .findByScheduleIdAndUserId(scheduleId, userId)
            .orElseThrow(() -> new NotFoundException("Participation introuvable."));

        participation.setStatus(ParticipationStatus.WITHDRAWN);
        participationRepository.save(participation);

        Schedule slot = participation.getSchedule();
        slot.setParticipantCount(Math.max(0, slot.getParticipantCount() - 1));
        if (slot.getStatus() == SlotStatus.FULL) {
            slot.setStatus(SlotStatus.OPEN);
        }
        scheduleRepository.save(slot);
    }
}
```

### 1.6 Requête PostGIS du feed

```java
// ScheduleRepository
@Query(value = """
    SELECT s.* FROM schedules s
    JOIN programs p        ON s.program_id = p.id
    JOIN user_activities ua ON p.user_activity_id = ua.id
    JOIN users u           ON ua.user_id = u.id
    WHERE s.is_open_to_partners = TRUE
      AND s.status IN ('OPEN', 'FULL')
      AND s.starts_at BETWEEN :fromTs AND :toTs
      AND p.status = 'ACTIVE'
      AND p.is_public = TRUE
      AND u.is_active = TRUE
      AND ua.visible_on_map = TRUE
      AND (:activityId IS NULL OR ua.activity_id = :activityId)
      AND (:categoryId IS NULL OR EXISTS (
            SELECT 1 FROM activities a
            WHERE a.id = ua.activity_id AND a.category_id = :categoryId))
      AND ST_DWithin(
            s.location::geography,
            ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
            :radiusMeters)
    ORDER BY s.starts_at ASC,
             ST_Distance(s.location::geography,
                         ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
    LIMIT :limit
    """, nativeQuery = true)
List<Schedule> findOpenSlotsInRadius(
    @Param("lat") double lat,
    @Param("lng") double lng,
    @Param("radiusMeters") int radiusMeters,
    @Param("fromTs") Instant from,
    @Param("toTs") Instant to,
    @Param("activityId") UUID activityId,
    @Param("categoryId") UUID categoryId,
    @Param("limit") int limit
);
```

### 1.7 Endpoints

```java
@RestController
@RequestMapping("/api/slots")
@RequiredArgsConstructor
public class SlotController {

    // GET /api/slots/feed?lat=&lng=&radiusMeters=&activityId=&categoryId=&from=&to=
    @GetMapping("/feed")
    public List<SlotFeedItemDto> getFeed(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid SlotFeedRequest request) { ... }

    // GET /api/slots/{scheduleId}
    @GetMapping("/{scheduleId}")
    public SlotFeedItemDto getSlot(...) { ... }

    // POST /api/slots/{scheduleId}/join
    @PostMapping("/{scheduleId}/join")
    @ResponseStatus(HttpStatus.CREATED)
    public SlotFeedItemDto join(...) { ... }

    // DELETE /api/slots/{scheduleId}/join
    @DeleteMapping("/{scheduleId}/join")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(...) { ... }

    // GET /api/slots/mine?upcoming=true|false
    // Créneaux que j'ai créés OU rejoints
    @GetMapping("/mine")
    public List<SlotFeedItemDto> getMySlots(...) { ... }

    // GET /api/slots/{scheduleId}/participants  (hôte uniquement)
    @GetMapping("/{scheduleId}/participants")
    public List<SlotParticipantDto> getParticipants(...) { ... }
}
```

---

## Partie 2 — La boucle de confirmation "J'y étais"

C'est le mécanisme qui remplace la donnée automatique de Strava. Il doit être
**ultra-léger** : un seul tap, pas de formulaire.

### 2.1 Entité `Attendance`

```java
@Entity
@Table(name = "attendances",
    uniqueConstraints = @UniqueConstraint(columnNames = {"schedule_id", "user_id"}),
    indexes = {
        @Index(name = "idx_attendance_user_date", columnList = "user_id, attended_at"),
        @Index(name = "idx_attendance_schedule", columnList = "schedule_id")
    }
)
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // TRUE = "j'y étais", FALSE = "finalement je n'y suis pas allé"
    @Column(name = "was_present", nullable = false)
    private Boolean wasPresent;

    // Instant réel du créneau (dénormalisé pour le calcul de série)
    @Column(name = "attended_at", nullable = false)
    private Instant attendedAt;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt = Instant.now();
}
```

### 2.2 Migration

```sql
-- V22__attendances.sql
CREATE TABLE attendances (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id  UUID NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL REFERENCES users(id),
    was_present  BOOLEAN NOT NULL,
    attended_at  TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_attendance UNIQUE (schedule_id, user_id)
);

CREATE INDEX idx_attendance_user_date ON attendances(user_id, attended_at DESC);
CREATE INDEX idx_attendance_schedule  ON attendances(schedule_id);

-- Compteurs dénormalisés sur users
ALTER TABLE users
    ADD COLUMN distinct_partners_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN attendance_count        INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN current_streak_weeks    INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_attendance_at      TIMESTAMPTZ;
```

### 2.3 `AttendanceService`

```java
@Service
@Transactional
@RequiredArgsConstructor
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final SlotParticipationRepository participationRepository;
    private final UserRepository userRepository;
    private final PracticeStatsService practiceStatsService;
    private final BadgeService badgeService;

    /**
     * Confirmation en un tap. Appelé depuis une notification post-créneau.
     * RÈGLE : uniquement possible si l'utilisateur était hôte ou participant
     * confirmé, et uniquement APRÈS la fin du créneau.
     */
    public AttendanceDto confirm(UUID userId, UUID scheduleId, boolean wasPresent) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new NotFoundException("Créneau introuvable."));

        Instant slotEnd = slot.getEndsAt() != null
            ? slot.getEndsAt()
            : slot.getStartsAt().plus(2, ChronoUnit.HOURS);

        if (Instant.now().isBefore(slotEnd)) {
            throw new ValidationException("Ce créneau n'est pas encore terminé.");
        }

        boolean isHost = slot.getProgram().getUserActivity()
            .getUser().getId().equals(userId);
        boolean isParticipant = participationRepository
            .existsByScheduleIdAndUserIdAndStatus(
                scheduleId, userId, ParticipationStatus.CONFIRMED);

        if (!isHost && !isParticipant) {
            throw new ForbiddenException("Vous n'étiez pas inscrit à ce créneau.");
        }
        if (attendanceRepository.existsByScheduleIdAndUserId(scheduleId, userId)) {
            throw new DuplicateException("Présence déjà confirmée.");
        }

        Attendance attendance = new Attendance();
        attendance.setSchedule(slot);
        attendance.setUser(userRepository.getReferenceById(userId));
        attendance.setWasPresent(wasPresent);
        attendance.setAttendedAt(slot.getStartsAt());
        attendanceRepository.save(attendance);

        if (wasPresent) {
            practiceStatsService.recalculateFor(userId);
            // Débloque la possibilité de recommander les autres présents
            badgeService.evaluateBadges(userId);
        }

        return toDto(attendance);
    }

    /**
     * Les personnes que je peux recommander suite à ce créneau :
     * celles qui ont AUSSI confirmé leur présence (double confirmation).
     * C'est la preuve d'interaction réelle exigée par PeerRecommendationService.
     */
    @Transactional(readOnly = true)
    public List<UserPublicDto> getRecommendableCoParticipants(UUID userId, UUID scheduleId) {
        if (!attendanceRepository.existsByScheduleIdAndUserIdAndWasPresentTrue(
                scheduleId, userId)) {
            return List.of();
        }
        return attendanceRepository
            .findPresentCoParticipants(scheduleId, userId)
            .stream().map(this::toPublicDto).toList();
    }
}
```

### 2.4 Job de relance post-créneau

```java
@Component
@RequiredArgsConstructor
public class AttendancePromptJob {

    private final ScheduleRepository scheduleRepository;
    private final AttendanceRepository attendanceRepository;
    private final NotificationService notificationService;

    /**
     * Toutes les heures : trouve les créneaux terminés il y a 1 à 3 heures
     * et envoie une notification unique "Tu y étais ?" aux inscrits qui
     * n'ont pas encore confirmé.
     * RÈGLE : une seule relance, jamais de rappel insistant.
     */
    @Scheduled(fixedDelay = 3600000)
    public void promptAttendanceConfirmation() {
        Instant from = Instant.now().minus(3, ChronoUnit.HOURS);
        Instant to   = Instant.now().minus(1, ChronoUnit.HOURS);

        List<Schedule> finished = scheduleRepository.findFinishedBetween(from, to);

        for (Schedule slot : finished) {
            List<UUID> toPrompt = attendanceRepository
                .findUnconfirmedParticipantIds(slot.getId());

            for (UUID userId : toPrompt) {
                notificationService.notify(userId,
                    NotificationType.ATTENDANCE_PROMPT, Map.of(
                        "scheduleId", slot.getId().toString(),
                        "programTitle", slot.getProgram().getTitle(),
                        "placeName", slot.getPlaceName()
                    ));
            }
        }
    }
}
```

### 2.5 Endpoints

```java
@RestController
@RequestMapping("/api/attendances")
@RequiredArgsConstructor
public class AttendanceController {

    // POST /api/attendances/{scheduleId}/confirm   body: { "wasPresent": true }
    @PostMapping("/{scheduleId}/confirm")
    public AttendanceDto confirm(...) { ... }

    // GET /api/attendances/pending
    // Créneaux terminés en attente de confirmation de ma part
    @GetMapping("/pending")
    public List<PendingAttendanceDto> getPending(...) { ... }

    // GET /api/attendances/{scheduleId}/co-participants
    // Personnes recommandables suite à ce créneau (double confirmation)
    @GetMapping("/{scheduleId}/co-participants")
    public List<UserPublicDto> getCoParticipants(...) { ... }
}
```

---

## Partie 3 — Statistiques de pratique (sans capteur)

### 3.1 `PracticeStatsService`

```java
@Service
@Transactional
@RequiredArgsConstructor
public class PracticeStatsService {

    private final AttendanceRepository attendanceRepository;
    private final UserRepository userRepository;

    /**
     * Recalcule les compteurs dénormalisés d'un utilisateur.
     * Appelé après chaque confirmation de présence.
     *
     * IMPORTANT : la série (streak) se compte en SEMAINES, pas en jours.
     * Une pratique quotidienne obligatoire serait contre-productive et
     * culpabilisante pour des loisirs. Une semaine où l'on a pratiqué au
     * moins une fois compte comme une semaine active.
     */
    public void recalculateFor(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();

        int attendanceCount = attendanceRepository.countPresentByUserId(userId);
        int distinctPartners = attendanceRepository.countDistinctPartners(userId);
        int streakWeeks = computeWeeklyStreak(userId);
        Instant last = attendanceRepository.findLastAttendanceDate(userId).orElse(null);

        user.setAttendanceCount(attendanceCount);
        user.setDistinctPartnersCount(distinctPartners);
        user.setCurrentStreakWeeks(streakWeeks);
        user.setLastAttendanceAt(last);
        userRepository.save(user);
    }

    private int computeWeeklyStreak(UUID userId) {
        List<Instant> dates = attendanceRepository
            .findPresentDatesDesc(userId);
        if (dates.isEmpty()) return 0;

        Set<LocalDate> activeWeeks = dates.stream()
            .map(i -> i.atZone(ZoneId.of("Europe/Paris")).toLocalDate()
                       .with(DayOfWeek.MONDAY))
            .collect(Collectors.toSet());

        LocalDate cursor = LocalDate.now(ZoneId.of("Europe/Paris"))
            .with(DayOfWeek.MONDAY);
        // Tolérance : la semaine en cours peut être encore vide sans casser la série
        if (!activeWeeks.contains(cursor)) cursor = cursor.minusWeeks(1);

        int streak = 0;
        while (activeWeeks.contains(cursor)) {
            streak++;
            cursor = cursor.minusWeeks(1);
        }
        return streak;
    }
}
```

### 3.2 Requêtes clés

```java
// AttendanceRepository

@Query("SELECT COUNT(a) FROM Attendance a WHERE a.user.id = :userId AND a.wasPresent = true")
int countPresentByUserId(@Param("userId") UUID userId);

/**
 * Nombre de PERSONNES DIFFÉRENTES avec qui l'utilisateur a pratiqué.
 * C'est la métrique de valeur centrale de meetDo — celle qu'on affiche
 * sur le profil, à la place d'une performance chiffrée.
 */
@Query(value = """
    SELECT COUNT(DISTINCT other.user_id)
    FROM attendances mine
    JOIN attendances other ON other.schedule_id = mine.schedule_id
                          AND other.user_id <> mine.user_id
    WHERE mine.user_id = :userId
      AND mine.was_present = TRUE
      AND other.was_present = TRUE
    """, nativeQuery = true)
int countDistinctPartners(@Param("userId") UUID userId);

@Query("SELECT a.attendedAt FROM Attendance a WHERE a.user.id = :userId AND a.wasPresent = true ORDER BY a.attendedAt DESC")
List<Instant> findPresentDatesDesc(@Param("userId") UUID userId);
```

### 3.3 Exposition

```java
public record PracticeStatsDto(
    int attendanceCount,        // "12 séances"
    int distinctPartnersCount,  // "avec 7 personnes différentes"
    int currentStreakWeeks,     // "5 semaines d'affilée"
    Instant lastAttendanceAt,
    List<ActivityBreakdownDto> byActivity  // répartition par activité
) {}

// GET /api/users/me/practice-stats
// GET /api/users/{userId}/practice-stats   (version publique)
```

> ⚠️ **INTERDICTION EXPLICITE** : ne créer AUCUN endpoint de classement,
> palmarès, top utilisateurs, ou comparaison entre personnes. Aucune requête
> avec `ORDER BY attendance_count DESC` exposée publiquement. La statistique
> est un miroir personnel, jamais un podium.

---

## Partie 4 — Alertes par activité (anti-carte-vide)

### 4.1 Entité `ActivityAlert`

```java
@Entity
@Table(name = "activity_alerts",
    uniqueConstraints = @UniqueConstraint(
        columnNames = {"user_id", "activity_id"}),
    indexes = {
        @Index(name = "idx_alert_activity", columnList = "activity_id"),
        @Index(name = "idx_alert_user", columnList = "user_id"),
        @Index(name = "idx_alert_location", columnList = "location")
    }
)
@EntityListeners(AuditingEntityListener.class)
public class ActivityAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false)
    private Activity activity;

    @Column(columnDefinition = "geometry(Point,4326)", nullable = false)
    private Point location;

    @Column(name = "radius_meters", nullable = false)
    private Integer radiusMeters = 10000;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
```

### 4.2 Migration

```sql
-- V23__activity_alerts.sql
CREATE TABLE activity_alerts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    activity_id       UUID NOT NULL REFERENCES activities(id),
    location          GEOMETRY(Point, 4326) NOT NULL,
    radius_meters     INTEGER NOT NULL DEFAULT 10000,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    last_triggered_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_alert_user_activity UNIQUE (user_id, activity_id)
);

CREATE INDEX idx_alert_activity ON activity_alerts(activity_id);
CREATE INDEX idx_alert_user     ON activity_alerts(user_id);
CREATE INDEX idx_alert_location ON activity_alerts USING GIST(location);
```

### 4.3 Déclenchement

```java
@Component
@RequiredArgsConstructor
public class ActivityAlertTrigger {

    private final ActivityAlertRepository alertRepository;
    private final NotificationService notificationService;

    /**
     * Déclenché quand un nouveau créneau ouvert est créé.
     * Notifie les utilisateurs qui attendaient cette activité dans la zone.
     *
     * ANTI-SPAM : une alerte ne se redéclenche pas plus d'une fois tous les
     * 7 jours pour un même utilisateur/activité, même si plusieurs créneaux
     * apparaissent.
     */
    @Async
    @EventListener
    public void onSlotCreated(SlotCreatedEvent event) {
        Schedule slot = event.getSchedule();
        if (!Boolean.TRUE.equals(slot.getIsOpenToPartners())) return;

        UUID activityId = slot.getProgram().getUserActivity().getActivity().getId();
        UUID hostId = slot.getProgram().getUserActivity().getUser().getId();

        Instant cooldown = Instant.now().minus(7, ChronoUnit.DAYS);

        List<ActivityAlert> matching = alertRepository.findMatchingAlerts(
            activityId,
            slot.getLocation().getY(),
            slot.getLocation().getX(),
            cooldown
        );

        for (ActivityAlert alert : matching) {
            if (alert.getUser().getId().equals(hostId)) continue;

            notificationService.notify(alert.getUser().getId(),
                NotificationType.ACTIVITY_ALERT_MATCH, Map.of(
                    "activityName", slot.getProgram().getUserActivity()
                                        .getActivity().getName(),
                    "scheduleId", slot.getId().toString(),
                    "placeName", slot.getPlaceName(),
                    "startsAt", slot.getStartsAt().toString()
                ));

            alert.setLastTriggeredAt(Instant.now());
            alertRepository.save(alert);
        }
    }
}
```

```java
// ActivityAlertRepository
@Query(value = """
    SELECT a.* FROM activity_alerts a
    JOIN users u ON a.user_id = u.id
    WHERE a.activity_id = :activityId
      AND a.is_active = TRUE
      AND u.is_active = TRUE
      AND (a.last_triggered_at IS NULL OR a.last_triggered_at < :cooldown)
      AND ST_DWithin(
            a.location::geography,
            ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
            a.radius_meters)
    """, nativeQuery = true)
List<ActivityAlert> findMatchingAlerts(
    @Param("activityId") UUID activityId,
    @Param("lat") double lat,
    @Param("lng") double lng,
    @Param("cooldown") Instant cooldown
);
```

### 4.4 Endpoints

```java
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class ActivityAlertController {

    // GET /api/alerts          → mes alertes actives
    // POST /api/alerts         → créer une alerte { activityId, lat, lng, radiusMeters }
    // PATCH /api/alerts/{id}   → activer/désactiver
    // DELETE /api/alerts/{id}  → supprimer
}
```

---

## Partie 5 — Réponse enrichie sur résultat vide

Modifier `SemanticSearchService.buildAlternativeSuggestions()` pour retourner
des actions structurées et exploitables par le client, plus des chaînes de
texte figées.

```java
public record EmptyStateActionDto(
    String type,        // "EXPAND_RADIUS" | "CREATE_SLOT" | "SET_ALERT" | "SIMILAR_ACTIVITY"
    String label,
    Map<String, Object> payload
) {}
```

```java
private List<EmptyStateActionDto> buildEmptyStateActions(
        SearchIntent intent, SearchRequest request, UUID activityId) {

    List<EmptyStateActionDto> actions = new ArrayList<>();

    // 1. Élargir le rayon
    int expanded = Math.min(request.radiusMeters() * 3, 50000);
    actions.add(new EmptyStateActionDto("EXPAND_RADIUS",
        "Chercher dans un rayon de " + (expanded / 1000) + " km",
        Map.of("radiusMeters", expanded)));

    // 2. Créer soi-même un créneau (transformer le vide en action)
    if (activityId != null) {
        actions.add(new EmptyStateActionDto("CREATE_SLOT",
            "Proposer un créneau et être le premier ici",
            Map.of("activityId", activityId)));

        // 3. Poser une alerte
        actions.add(new EmptyStateActionDto("SET_ALERT",
            "Me prévenir quand quelqu'un arrive",
            Map.of("activityId", activityId,
                   "lat", request.lat(), "lng", request.lng(),
                   "radiusMeters", request.radiusMeters())));
    }

    // 4. Activités sémantiquement proches ayant du monde à proximité
    List<Activity> neighbours = activityRepository
        .findSimilarActivitiesWithNearbyUsers(activityId, request.lat(),
                                              request.lng(), request.radiusMeters(), 3);
    for (Activity a : neighbours) {
        actions.add(new EmptyStateActionDto("SIMILAR_ACTIVITY",
            "Voir " + a.getName() + " à la place",
            Map.of("activityId", a.getId().toString(), "name", a.getName())));
    }

    return actions;
}
```

---

## Partie 6 — Nouveaux types de notification

Ajouter à l'enum `NotificationType` :

```java
SLOT_JOINED,             // quelqu'un a rejoint mon créneau
SLOT_CANCELLED,          // un créneau que j'ai rejoint est annulé
ATTENDANCE_PROMPT,       // "tu y étais ?" après un créneau
ACTIVITY_ALERT_MATCH,    // quelqu'un pratique enfin cette activité près de moi
STREAK_MILESTONE,        // série de N semaines atteinte
PARTNER_MILESTONE        // Nème partenaire différent
```

Créer les préférences par défaut correspondantes dans `NotificationPref`.

---

## Partie 7 — Nouveaux badges

Ajouter à `seed/data/badges.json` :

```json
[
  { "code": "FIRST_MEETUP", "category": "ACHIEVEMENT", "label": "Première rencontre",
    "conditionType": "ATTENDANCE_COUNT", "conditionThreshold": 1, "icon": "handshake" },
  { "code": "TEN_MEETUPS", "category": "ACHIEVEMENT", "label": "10 séances partagées",
    "conditionType": "ATTENDANCE_COUNT", "conditionThreshold": 10, "icon": "calendar-check" },
  { "code": "FIVE_PARTNERS", "category": "ACHIEVEMENT", "label": "5 partenaires différents",
    "conditionType": "DISTINCT_PARTNERS", "conditionThreshold": 5, "icon": "users" },
  { "code": "TWENTY_PARTNERS", "category": "ACHIEVEMENT", "label": "20 partenaires différents",
    "conditionType": "DISTINCT_PARTNERS", "conditionThreshold": 20, "icon": "users-round" },
  { "code": "STREAK_4_WEEKS", "category": "ACHIEVEMENT", "label": "1 mois de régularité",
    "conditionType": "WEEKLY_STREAK", "conditionThreshold": 4, "icon": "flame" },
  { "code": "STREAK_12_WEEKS", "category": "ACHIEVEMENT", "label": "3 mois de régularité",
    "conditionType": "WEEKLY_STREAK", "conditionThreshold": 12, "icon": "flame" },
  { "code": "SLOT_HOST", "category": "ROLE", "label": "Organise des créneaux",
    "conditionType": "SLOT_HOSTED_COUNT", "conditionThreshold": 3, "icon": "star" }
]
```

Ajouter les valeurs correspondantes à `BadgeConditionType` :

```java
ATTENDANCE_COUNT,
DISTINCT_PARTNERS,
WEEKLY_STREAK,
SLOT_HOSTED_COUNT
```

Et les brancher dans `BadgeService.isEligible()`.

---

## Partie 8 — Adapter la preuve d'interaction

`PeerRecommendationService` et `ReviewService` exigent aujourd'hui une
`Conversation` comme preuve d'interaction. Élargir cette preuve : une
**double confirmation de présence sur le même créneau** est une preuve plus
forte qu'une simple conversation.

```java
public enum InteractionProofType { CONVERSATION, SHARED_ATTENDANCE }

// Dans PeerRecommendationService.create() :
boolean hasProof =
       conversationRepository.existsDirectBetween(fromUserId, toUserId)
    || attendanceRepository.existsSharedPresence(fromUserId, toUserId);

if (!hasProof) {
    throw new InsufficientInteractionException(
        "Une rencontre réelle est requise pour recommander cette personne.");
}
```

```java
@Query(value = """
    SELECT EXISTS (
      SELECT 1
      FROM attendances a1
      JOIN attendances a2 ON a1.schedule_id = a2.schedule_id
      WHERE a1.user_id = :userA AND a2.user_id = :userB
        AND a1.was_present = TRUE AND a2.was_present = TRUE
    )
    """, nativeQuery = true)
boolean existsSharedPresence(@Param("userA") UUID userA, @Param("userB") UUID userB);
```

---

## Ordre d'implémentation

```
1. Migration V21 + entités Schedule modifiée + SlotParticipation
2. SlotService + SlotController + requête PostGIS du feed
3. Migration V22 + Attendance + AttendanceService + AttendanceController
4. PracticeStatsService + endpoints practice-stats
5. AttendancePromptJob (relance post-créneau)
6. Migration V23 + ActivityAlert + ActivityAlertController + trigger
7. EmptyStateActionDto dans SemanticSearchService
8. Nouveaux NotificationType + préférences par défaut
9. Nouveaux badges + BadgeConditionType + BadgeService.isEligible()
10. Élargissement de la preuve d'interaction (SHARED_ATTENDANCE)
```

---

## Tests à ajouter (complément de la spec de tests existante)

```
SlotServiceTest
- joinSlot rejette l'hôte qui rejoint son propre créneau
- joinSlot rejette un créneau déjà passé
- joinSlot rejette un doublon
- joinSlot ouvre bien une conversation contextualisée
- joinSlot respecte receiveMessages = false de l'hôte (pas de conversation, pas d'erreur)
- getSlotFeed ne retourne jamais un créneau d'un utilisateur inactif
- getSlotFeed ne retourne jamais un créneau dont l'activité est masquée

AttendanceServiceTest
- confirm rejette avant la fin du créneau
- confirm rejette un non-inscrit
- confirm rejette un doublon
- getRecommendableCoParticipants retourne vide si je n'ai pas confirmé moi-même

PracticeStatsServiceTest
- countDistinctPartners ne compte jamais deux fois la même personne
- computeWeeklyStreak tolère la semaine en cours vide
- computeWeeklyStreak casse la série après une semaine sautée

ActivityAlertTriggerTest
- ne notifie pas l'hôte du créneau
- respecte le cooldown de 7 jours
- ne notifie pas hors du rayon
```
