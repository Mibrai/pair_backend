# Pair — Phase 4 : Engagement, Notifications & Montée en charge
## Spécification d'implémentation pour Claude Code

> **Prérequis** : Phases 1, 2 et 3 complètes.
>
> **Objectif** : faire revenir les utilisateurs (notifications granulaires,
> push, résumés), rendre l'app robuste à la charge (Redis, rate limiting
> complet, archivage avancé) et finaliser la conformité RGPD.

---

## Nouvelles dépendances Maven

```xml
<!-- Redis -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- Firebase Admin SDK (notifications push) -->
<dependency>
  <groupId>com.google.firebase</groupId>
  <artifactId>firebase-admin</artifactId>
  <version>9.2.0</version>
</dependency>

<!-- Bucket4j (rate limiting production) -->
<dependency>
  <groupId>com.bucket4j</groupId>
  <artifactId>bucket4j-core</artifactId>
  <version>8.7.0</version>
</dependency>
<dependency>
  <groupId>com.bucket4j</groupId>
  <artifactId>bucket4j-redis</artifactId>
  <version>8.7.0</version>
</dependency>

<!-- Quartz (jobs planifiés — résumés email) -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-quartz</artifactId>
</dependency>
```

---

## Module 1 — Notifications complètes

### NotificationService.java

```java
@Service
@Transactional
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPrefRepository prefRepository;
    private final EmailService emailService;
    private final PushNotificationService pushService;

    // Point d'entrée unique pour toutes les notifications
    public void notify(UUID userId, NotificationType type, Map<String, Object> payload) {

        // 1. Récupérer les préférences de l'utilisateur pour ce type
        NotificationPref pref = prefRepository
            .findByUserIdAndNotificationType(userId, type)
            .orElse(defaultPref(userId, type)); // Prefs par défaut si non configurées

        // 2. Notification in-app (toujours, quelle que soit la préférence)
        saveInAppNotification(userId, type, payload);

        // 3. Email selon préférence
        if (Boolean.TRUE.equals(pref.getEmailEnabled())) {
            if (pref.getFrequency() == NotificationFrequency.IMMEDIATE) {
                emailService.sendNotificationEmail(userId, type, payload);
            }
            // DAILY_DIGEST et WEEKLY : géré par les jobs Quartz ci-dessous
        }

        // 4. Push selon préférence
        if (Boolean.TRUE.equals(pref.getPushEnabled())) {
            pushService.sendPush(userId, type, payload);
        }
    }

    private void saveInAppNotification(UUID userId, NotificationType type,
                                        Map<String, Object> payload) {
        Notification notif = new Notification();
        notif.setUser(userRepository.getReferenceById(userId));
        notif.setType(type);
        notif.setChannel(NotificationChannel.IN_APP);
        notif.setPayload(objectMapper.writeValueAsString(payload));
        notif.setIsRead(false);
        notif.setSentAt(Instant.now());
        notificationRepository.save(notif);
    }

    private NotificationPref defaultPref(UUID userId, NotificationType type) {
        NotificationPref pref = new NotificationPref();
        pref.setUser(userRepository.getReferenceById(userId));
        pref.setNotificationType(type);
        pref.setEmailEnabled(true);
        pref.setPushEnabled(true);
        pref.setFrequency(NotificationFrequency.IMMEDIATE);
        return pref;
    }
}
```

### PushNotificationService.java (Firebase)

```java
@Service
@RequiredArgsConstructor
public class PushNotificationService {

    private final FirebaseMessaging firebaseMessaging;
    private final DeviceTokenRepository deviceTokenRepository;

    public void sendPush(UUID userId, NotificationType type,
                          Map<String, Object> payload) {
        List<String> tokens = deviceTokenRepository.findTokensByUserId(userId);
        if (tokens.isEmpty()) return;

        String title = buildTitle(type, payload);
        String body = buildBody(type, payload);

        MulticastMessage message = MulticastMessage.builder()
            .addAllTokens(tokens)
            .setNotification(Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build())
            .putAllData(payload.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                    e -> String.valueOf(e.getValue()))))
            .build();

        try {
            BatchResponse response = firebaseMessaging.sendEachForMulticast(message);
            // Nettoyer les tokens invalides
            cleanInvalidTokens(tokens, response);
        } catch (FirebaseMessagingException e) {
            log.error("Erreur envoi push pour user {} : {}", userId, e.getMessage());
        }
    }

    private void cleanInvalidTokens(List<String> tokens, BatchResponse response) {
        List<SendResponse> responses = response.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            if (!responses.get(i).isSuccessful()) {
                String code = responses.get(i).getException().getMessagingErrorCode().name();
                if (code.equals("UNREGISTERED") || code.equals("INVALID_ARGUMENT")) {
                    deviceTokenRepository.deleteByToken(tokens.get(i));
                }
            }
        }
    }

    private String buildTitle(NotificationType type, Map<String, Object> payload) {
        return switch (type) {
            case NEW_MESSAGE -> "Nouveau message de " + payload.get("senderName");
            case NEW_MATCH -> "Quelqu'un partage votre passion !";
            case BADGE_EARNED -> "Nouveau badge obtenu 🎉";
            case PROGRAM_REVIEW -> "Nouvel avis sur votre programme";
            case PEER_RECOMMENDATION -> payload.get("fromName") + " vous recommande";
            case CRENEAU_REMINDER -> "Rappel : " + payload.get("programTitle");
            default -> "Pair";
        };
    }
}
```

### NotificationController.java

```java
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationPrefRepository prefRepository;

    // GET /api/notifications?page=&size=
    @GetMapping
    public Page<NotificationDto> getNotifications(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return notificationService.getNotifications(
            principal.getId(), PageRequest.of(page, Math.min(size, 50)));
    }

    // GET /api/notifications/unread-count
    @GetMapping("/unread-count")
    public Map<String, Long> getUnreadCount(
            @AuthenticationPrincipal UserPrincipal principal) {
        long count = notificationService.countUnread(principal.getId());
        return Map.of("count", count);
    }

    // POST /api/notifications/read-all
    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(
            @AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markAllRead(principal.getId());
        return ResponseEntity.ok().build();
    }

    // POST /api/notifications/{id}/read
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        notificationService.markRead(principal.getId(), id);
        return ResponseEntity.ok().build();
    }

    // GET /api/notifications/preferences
    @GetMapping("/preferences")
    public List<NotificationPrefDto> getPreferences(
            @AuthenticationPrincipal UserPrincipal principal) {
        return notificationService.getPreferences(principal.getId());
    }

    // PUT /api/notifications/preferences
    @PutMapping("/preferences")
    public List<NotificationPrefDto> updatePreferences(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody List<UpdateNotificationPrefRequest> requests) {
        return notificationService.updatePreferences(principal.getId(), requests);
    }

    // POST /api/notifications/device-token — enregistrer token push
    @PostMapping("/device-token")
    public ResponseEntity<Void> registerDeviceToken(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody DeviceTokenRequest request) {
        notificationService.registerDeviceToken(principal.getId(),
            request.token(), request.platform());
        return ResponseEntity.ok().build();
    }

    // DELETE /api/notifications/device-token — désenregistrer (déconnexion)
    @DeleteMapping("/device-token")
    public ResponseEntity<Void> removeDeviceToken(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody DeviceTokenRequest request) {
        notificationService.removeDeviceToken(principal.getId(), request.token());
        return ResponseEntity.ok().build();
    }
}
```

---

## Module 2 — Jobs planifiés (résumés email)

### DigestEmailJob.java (Quartz)

```java
@Component
@RequiredArgsConstructor
public class DigestEmailJob implements Job {

    private final NotificationRepository notificationRepository;
    private final NotificationPrefRepository prefRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    // Exécuté tous les jours à 18h00
    @Scheduled(cron = "0 0 18 * * *", zone = "Europe/Paris")
    public void sendDailyDigests() {
        // Trouver tous les users avec au moins une préférence DAILY_DIGEST
        List<UUID> usersWithDigest = prefRepository
            .findUserIdsWithFrequency(NotificationFrequency.DAILY_DIGEST);

        for (UUID userId : usersWithDigest) {
            sendDigestForUser(userId, NotificationFrequency.DAILY_DIGEST,
                Instant.now().minus(1, ChronoUnit.DAYS));
        }
    }

    // Exécuté tous les lundis à 9h00
    @Scheduled(cron = "0 0 9 * * MON", zone = "Europe/Paris")
    public void sendWeeklyDigests() {
        List<UUID> usersWithWeekly = prefRepository
            .findUserIdsWithFrequency(NotificationFrequency.WEEKLY);

        for (UUID userId : usersWithWeekly) {
            sendDigestForUser(userId, NotificationFrequency.WEEKLY,
                Instant.now().minus(7, ChronoUnit.DAYS));
        }
    }

    private void sendDigestForUser(UUID userId, NotificationFrequency freq,
                                    Instant since) {
        List<Notification> pending = notificationRepository
            .findUnsentDigestNotifications(userId, freq, since);

        if (pending.isEmpty()) return;

        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) return;

        emailService.sendDigestEmail(user, pending, freq);

        // Marquer comme envoyées en digest
        notificationRepository.markAsSentInDigest(
            pending.stream().map(Notification::getId).toList());
    }
}
```

### CreneauReminderJob.java

```java
@Component
@RequiredArgsConstructor
public class CreneauReminderJob {

    private final ScheduleRepository scheduleRepository;
    private final NotificationService notificationService;

    // Exécuté toutes les heures
    @Scheduled(fixedDelay = 3600000)
    public void sendCreneauReminders() {
        // Trouver les créneaux qui commencent dans 24h (+/- 30 min)
        Instant from = Instant.now().plus(23, ChronoUnit.HOURS)
                                   .plus(30, ChronoUnit.MINUTES);
        Instant to   = Instant.now().plus(24, ChronoUnit.HOURS)
                                   .plus(30, ChronoUnit.MINUTES);

        List<Schedule> upcoming = scheduleRepository.findByStartsAtBetween(from, to);

        for (Schedule schedule : upcoming) {
            UUID ownerId = schedule.getProgram().getUserActivity().getUser().getId();
            notificationService.notify(ownerId, NotificationType.CRENEAU_REMINDER,
                Map.of(
                    "programTitle", schedule.getProgram().getTitle(),
                    "placeName", schedule.getPlaceName(),
                    "startsAt", schedule.getStartsAt().toString()
                ));
        }
    }
}
```

---

## Module 3 — Redis (statut en ligne + rate limiting production)

### RedisConfig.java

```java
@Configuration
public class RedisConfig {

    @Value("${redis.host:localhost}")
    private String redisHost;

    @Value("${redis.port:6379}")
    private int redisPort;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(redisHost, redisPort);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    // Configurer le message broker WebSocket avec Redis
    // Remplace le SimpleBroker de la Phase 1 par un RedisMessageBroker
    // pour supporter plusieurs instances de l'app
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }
}
```

### OnlineStatusService.java

```java
@Service
@RequiredArgsConstructor
public class OnlineStatusService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final Duration ONLINE_TTL = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "pair:online:";

    public void setOnline(UUID userId) {
        redisTemplate.opsForValue().set(
            KEY_PREFIX + userId, "1", ONLINE_TTL);
    }

    public boolean isOnline(UUID userId) {
        return Boolean.TRUE.equals(
            redisTemplate.hasKey(KEY_PREFIX + userId));
    }

    public Map<UUID, Boolean> areOnline(List<UUID> userIds) {
        return userIds.stream().collect(Collectors.toMap(
            id -> id,
            id -> isOnline(id)
        ));
    }

    // Appelé à chaque requête authentifiée (via JwtAuthFilter)
    public void refreshOnlineStatus(UUID userId) {
        setOnline(userId);
    }
}
```

### RateLimiterService.java (Bucket4j + Redis — remplace Phase 1)

```java
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final ProxyManager<String> proxyManager; // Bucket4j Redis

    // Login : 10 tentatives par 15 minutes par IP
    public void checkLogin(String ip) {
        Bucket bucket = proxyManager.builder()
            .build(KEY_LOGIN + ip, () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(10, Duration.ofMinutes(15)))
                .build());
        if (!bucket.tryConsume(1)) {
            throw new TooManyRequestsException(
                "Trop de tentatives de connexion. Réessayez dans 15 minutes.");
        }
    }

    // Inscription : 5 par heure par IP
    public void checkRegister(String ip) {
        Bucket bucket = proxyManager.builder()
            .build(KEY_REGISTER + ip, () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(5, Duration.ofHours(1)))
                .build());
        if (!bucket.tryConsume(1)) {
            throw new TooManyRequestsException(
                "Trop d'inscriptions depuis cette adresse IP.");
        }
    }

    // Recherche : 30 par minute par utilisateur (LLM coûte des tokens)
    public void checkSearch(UUID userId) {
        Bucket bucket = proxyManager.builder()
            .build(KEY_SEARCH + userId, () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(30, Duration.ofMinutes(1)))
                .build());
        if (!bucket.tryConsume(1)) {
            throw new TooManyRequestsException(
                "Trop de recherches. Patientez un moment.");
        }
    }

    // Upload média : 20 par heure par utilisateur
    public void checkMediaUpload(UUID userId) {
        Bucket bucket = proxyManager.builder()
            .build(KEY_UPLOAD + userId, () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(20, Duration.ofHours(1)))
                .build());
        if (!bucket.tryConsume(1)) {
            throw new TooManyRequestsException(
                "Limite d'upload atteinte pour aujourd'hui.");
        }
    }

    private static final String KEY_LOGIN = "pair:rl:login:";
    private static final String KEY_REGISTER = "pair:rl:register:";
    private static final String KEY_SEARCH = "pair:rl:search:";
    private static final String KEY_UPLOAD = "pair:rl:upload:";
}
```

---

## Module 4 — Archivage avancé & historique des modifications

### AuditLogService.java

```java
@Service
@Transactional
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    // Enregistrer une modification sur un objet sensible
    public void log(UUID actorId, String entityType, UUID entityId,
                    String action, Map<String, Object> changes) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setAction(action); // CREATE | UPDATE | ARCHIVE | DELETE
        entry.setChanges(objectMapper.writeValueAsString(changes));
        entry.setCreatedAt(Instant.now());
        auditLogRepository.save(entry);
    }
}

// Entité AuditLog (migration SQL à ajouter)
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_entity", columnList = "entity_type, entity_id"),
    @Index(name = "idx_audit_actor", columnList = "actor_id"),
    @Index(name = "idx_audit_created", columnList = "created_at")
})
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID actorId;
    @Column(length = 50) private String entityType;
    private UUID entityId;
    @Column(length = 20) private String action;
    @Column(columnDefinition = "jsonb") private String changes;
    private Instant createdAt;
}
```

### AuditLog SQL (migration Flyway Phase 4)

```sql
CREATE TABLE audit_logs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id    UUID NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id   UUID NOT NULL,
    action      VARCHAR(20) NOT NULL,
    changes     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_entity  ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_actor   ON audit_logs(actor_id);
CREATE INDEX idx_audit_created ON audit_logs(created_at);

-- Purge automatique après 1 an (optionnel, via pg_cron)
-- SELECT cron.schedule('purge-audit-logs', '0 3 1 * *',
--   'DELETE FROM audit_logs WHERE created_at < NOW() - INTERVAL ''1 year''');
```

---

## Module 5 — Conformité RGPD

### GdprService.java

```java
@Service
@Transactional
@RequiredArgsConstructor
public class GdprService {

    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final ProgressionEntryRepository progressionRepository;
    private final SearchLogRepository searchLogRepository;

    // Export des données (droit d'accès RGPD)
    public GdprExportDto exportUserData(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();

        return new GdprExportDto(
            toProfileExport(user),
            userActivityRepository.findByUserId(userId),
            programRepository.findByUserId(userId),
            messageRepository.findByUserId(userId),
            progressionRepository.findByUserId(userId),
            searchLogRepository.findByUserId(userId),
            reviewRepository.findByReviewerId(userId),
            recommendationRepository.findByFromUserId(userId)
        );
        // Retourné en JSON, téléchargeable par l'utilisateur
    }

    // Suppression définitive (droit à l'oubli RGPD)
    // Appelé 30 jours après deactivateAccount() — via job planifié
    @Scheduled(cron = "0 0 3 * * *") // Tous les jours à 3h00
    public void purgeDeactivatedAccounts() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        List<User> toPurge = userRepository
            .findInactiveAccountsBefore(cutoff);

        for (User user : toPurge) {
            purgeUser(user);
        }
    }

    private void purgeUser(User user) {
        // Anonymiser les messages (garder la structure des conversations)
        messageRepository.anonymizeBySenderId(user.getId());
        // Anonymiser les avis (garder la note, supprimer le texte et l'auteur)
        reviewRepository.anonymizeByReviewerId(user.getId());
        // Supprimer les données personnelles
        searchLogRepository.deleteByUserId(user.getId());
        progressionRepository.deleteByUserId(user.getId());
        // Supprimer le compte
        userRepository.delete(user);
        log.info("Compte RGPD purgé : {}", user.getId());
    }
}
```

### GdprController.java

```java
@RestController
@RequestMapping("/api/gdpr")
@RequiredArgsConstructor
public class GdprController {

    private final GdprService gdprService;

    // GET /api/gdpr/export — télécharger ses données
    @GetMapping("/export")
    public ResponseEntity<GdprExportDto> exportData(
            @AuthenticationPrincipal UserPrincipal principal) {
        GdprExportDto export = gdprService.exportUserData(principal.getId());
        return ResponseEntity.ok()
            .header("Content-Disposition",
                "attachment; filename=\"pair-export-" + principal.getId() + ".json\"")
            .body(export);
    }
}
```

---

## Récapitulatif des endpoints Phase 4

### Notifications
| Méthode | Route | Description |
|---------|-------|-------------|
| GET    | /api/notifications | Liste des notifications |
| GET    | /api/notifications/unread-count | Nombre non lus |
| POST   | /api/notifications/read-all | Tout marquer lu |
| POST   | /api/notifications/{id}/read | Marquer lu |
| GET    | /api/notifications/preferences | Mes préférences |
| PUT    | /api/notifications/preferences | Modifier préférences |
| POST   | /api/notifications/device-token | Enregistrer token push |
| DELETE | /api/notifications/device-token | Désenregistrer token push |

### RGPD
| Méthode | Route | Description |
|---------|-------|-------------|
| GET | /api/gdpr/export | Exporter ses données |

### Jobs automatiques (pas d'endpoint — déclenchés en interne)
| Job | Fréquence | Description |
|-----|-----------|-------------|
| DigestEmailJob.sendDailyDigests | Tous les jours 18h | Résumés quotidiens |
| DigestEmailJob.sendWeeklyDigests | Lundi 9h | Résumés hebdo |
| CreneauReminderJob | Toutes les heures | Rappels créneaux J-1 |
| GdprService.purgeDeactivatedAccounts | Tous les jours 3h | Purge RGPD 30j |

