# Pair — Phase 1 : Fondations & Boucle de rencontre
## Spécification d'implémentation pour Claude Code

> **Objectif de la phase 1** : faire tourner la boucle centrale de Pair —
> un utilisateur peut s'inscrire, décrire ses activités, apparaître sur la carte,
> et contacter quelqu'un trouvé près de lui.
> À la fin de cette phase, le produit est testable par de vrais utilisateurs.

> **Prérequis** : avoir exécuté la spécification `pair-data-model-spec.md`
> (entités JPA, migrations Flyway, extensions PostgreSQL).

---

## Structure du projet Spring Boot

```
src/main/java/com/pair/
├── config/
│   ├── SecurityConfig.java
│   ├── WebSocketConfig.java
│   ├── JpaConfig.java
│   └── CorsConfig.java
├── domain/
│   ├── user/
│   │   ├── User.java (entité — déjà définie dans data-model-spec)
│   │   ├── UserRepository.java
│   │   ├── UserService.java
│   │   └── UserController.java
│   ├── activity/
│   │   ├── Activity.java / Category.java / UserActivity.java
│   │   ├── ActivityRepository.java / CategoryRepository.java
│   │   ├── UserActivityRepository.java
│   │   ├── ActivityService.java
│   │   └── ActivityController.java
│   ├── program/
│   │   ├── Program.java / Schedule.java / ProgramMedia.java
│   │   ├── ProgramRepository.java / ScheduleRepository.java
│   │   ├── ProgramService.java
│   │   └── ProgramController.java
│   ├── map/
│   │   ├── MapService.java
│   │   └── MapController.java
│   ├── chat/
│   │   ├── Conversation.java / Message.java / ConversationMember.java
│   │   ├── ConversationRepository.java / MessageRepository.java
│   │   ├── ChatService.java
│   │   ├── ChatController.java       ← REST (liste conversations)
│   │   └── ChatWebSocketHandler.java ← WebSocket (messages temps réel)
│   └── auth/
│       ├── AuthController.java
│       ├── AuthService.java
│       ├── JwtTokenProvider.java
│       └── EmailVerificationService.java
├── shared/
│   ├── dto/          ← DTOs partagés (PageResponse, ErrorResponse)
│   ├── exception/    ← Exceptions métier + GlobalExceptionHandler
│   ├── security/     ← JwtAuthFilter, UserDetailsServiceImpl
│   ├── sanitizer/    ← HtmlSanitizer (OWASP)
│   └── email/        ← EmailService (Postmark/SendGrid)
└── PairApplication.java
```

---

## Étape 1 — Configuration Spring Security + JWT

### SecurityConfig.java

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable) // JWT stateless — pas de CSRF session
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Routes publiques
                .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.GET,  "/api/auth/verify-email").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/forgot-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/reset-password").permitAll()
                // WebSocket (auth gérée dans le handshake)
                .requestMatchers("/ws/**").permitAll()
                // Tout le reste : authentifié
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // Coût 12 — bon équilibre sécurité/perf
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

### JwtTokenProvider.java

```java
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}") // Stocker dans env var, jamais en dur
    private String jwtSecret;

    @Value("${jwt.access-token-expiry-ms:900000}")   // 15 min
    private long accessTokenExpiryMs;

    @Value("${jwt.refresh-token-expiry-ms:2592000000}") // 30 jours
    private long refreshTokenExpiryMs;

    public String generateAccessToken(UUID userId, String email) {
        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + accessTokenExpiryMs))
            .signWith(getSigningKey())
            .compact();
    }

    public String generateRefreshToken(UUID userId) {
        return Jwts.builder()
            .subject(userId.toString())
            .claim("type", "refresh")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiryMs))
            .signWith(getSigningKey())
            .compact();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(
            Jwts.parser().verifyWith(getSigningKey()).build()
                .parseSignedClaims(token).getPayload().getSubject()
        );
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }
}
```

### JwtAuthFilter.java

```java
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null && tokenProvider.validateToken(token)) {
            UUID userId = tokenProvider.extractUserId(token);
            UserDetails userDetails = userDetailsService.loadUserById(userId);
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
```

---

## Étape 2 — Authentification

### DTOs Auth

```java
// Requête inscription
public record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8, max = 100) String password,
    @NotBlank @Size(max = 80) String displayName
) {}

// Requête connexion
public record LoginRequest(
    @NotBlank @Email String email,
    @NotBlank String password
) {}

// Réponse auth (access + refresh token)
public record AuthResponse(
    String accessToken,
    String refreshToken,
    UUID userId,
    String displayName,
    String verificationStatus
) {}

// Réponse erreur standard
public record ErrorResponse(
    String code,
    String message,
    Instant timestamp
) {}
```

### AuthService.java

```java
@Service
@Transactional
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final EmailVerificationService emailVerificationService;

    public AuthResponse register(RegisterRequest request) {
        // 1. Vérifier unicité email
        if (userRepository.existsByEmail(request.email().toLowerCase())) {
            throw new EmailAlreadyExistsException("Cet email est déjà utilisé.");
        }

        // 2. Créer l'utilisateur
        User user = new User();
        user.setEmail(request.email().toLowerCase().strip());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().strip());
        user.setVerificationStatus(VerificationStatus.UNVERIFIED);
        user = userRepository.save(user);

        // 3. Envoyer l'email de vérification
        emailVerificationService.sendVerificationEmail(user);

        // 4. Retourner les tokens (l'utilisateur peut utiliser l'app mais est marqué non vérifié)
        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().toLowerCase())
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .orElseThrow(() -> new InvalidCredentialsException("Identifiants invalides."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Identifiants invalides.");
        }

        // Mettre à jour last_active_at
        user.setLastActiveAt(Instant.now());
        userRepository.save(user);

        return buildAuthResponse(user);
    }

    public AuthResponse refreshToken(String refreshToken) {
        if (!tokenProvider.validateToken(refreshToken)) {
            throw new InvalidTokenException("Refresh token invalide ou expiré.");
        }
        UUID userId = tokenProvider.extractUserId(refreshToken);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));
        return buildAuthResponse(user);
    }

    public void verifyEmail(String token) {
        emailVerificationService.verifyToken(token);
    }

    private AuthResponse buildAuthResponse(User user) {
        return new AuthResponse(
            tokenProvider.generateAccessToken(user.getId(), user.getEmail()),
            tokenProvider.generateRefreshToken(user.getId()),
            user.getId(),
            user.getDisplayName(),
            user.getVerificationStatus().name()
        );
    }
}
```

### AuthController.java

```java
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;
    private final RateLimiter rateLimiter; // Voir config rate limiting ci-dessous

    // POST /api/auth/register
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request,
                                  HttpServletRequest httpRequest) {
        rateLimiter.checkRegister(httpRequest.getRemoteAddr());
        return authService.register(request);
    }

    // POST /api/auth/login
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest httpRequest) {
        rateLimiter.checkLogin(httpRequest.getRemoteAddr());
        return authService.login(request);
    }

    // POST /api/auth/refresh
    @PostMapping("/refresh")
    public AuthResponse refresh(@RequestBody RefreshRequest request) {
        return authService.refreshToken(request.refreshToken());
    }

    // GET /api/auth/verify-email?token=xxx
    @GetMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@RequestParam String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok().build();
    }

    // POST /api/auth/forgot-password
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@RequestBody ForgotPasswordRequest req,
                                                HttpServletRequest httpRequest) {
        rateLimiter.checkPasswordReset(httpRequest.getRemoteAddr());
        authService.sendPasswordResetEmail(req.email());
        // Toujours répondre 200 même si l'email n'existe pas (éviter l'énumération)
        return ResponseEntity.ok().build();
    }

    // POST /api/auth/reset-password
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req.token(), req.newPassword());
        return ResponseEntity.ok().build();
    }
}
```

### Rate Limiting (bean de configuration)

```java
@Component
public class RateLimiter {
    // Utiliser Bucket4j ou une Map<IP, compteur> en mémoire pour commencer
    // En production : stocker dans Redis

    private final Map<String, AtomicInteger> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockouts = new ConcurrentHashMap<>();

    public void checkLogin(String ip) {
        if (isLockedOut(ip)) {
            throw new TooManyRequestsException("Trop de tentatives. Réessayez dans 15 minutes.");
        }
        int attempts = loginAttempts.computeIfAbsent(ip, k -> new AtomicInteger(0))
                                    .incrementAndGet();
        if (attempts >= 10) {
            lockouts.put(ip, Instant.now().plusSeconds(900)); // 15 min lockout
            loginAttempts.remove(ip);
        }
    }

    public void checkRegister(String ip) { /* Max 5 inscriptions/heure par IP */ }
    public void checkPasswordReset(String ip) { /* Max 3 demandes/heure par IP */ }

    private boolean isLockedOut(String ip) {
        Instant lockUntil = lockouts.get(ip);
        if (lockUntil == null) return false;
        if (Instant.now().isAfter(lockUntil)) {
            lockouts.remove(ip);
            return false;
        }
        return true;
    }
}
```

---

## Étape 3 — Gestion du profil utilisateur

### DTOs Profil

```java
// Réponse profil public (ce que les autres voient)
public record UserPublicDto(
    UUID id,
    String displayName,
    String bio,
    String avatarUrl,
    String verificationStatus,
    List<String> badgeCodes,
    List<UserActivitySummaryDto> activities,
    boolean isOnline    // null si onlineStatusVisible = false
) {}

// Réponse profil privé (ce que l'utilisateur voit de lui-même)
public record UserPrivateDto(
    UUID id,
    String email,
    String phone,
    String displayName,
    String bio,
    String avatarUrl,
    Double lat,
    Double lng,
    Integer blurRadiusM,
    Boolean locationPublic,
    Boolean onlineStatusVisible,
    Boolean receiveMessages,
    String verificationStatus,
    Instant createdAt,
    List<UserActivityDto> activities
) {}

// Mise à jour du profil
public record UpdateProfileRequest(
    @Size(max = 80) String displayName,
    @Size(max = 1000) String bio,
    Boolean locationPublic,
    Boolean onlineStatusVisible,
    Boolean receiveMessages,
    Integer blurRadiusM
) {}

// Mise à jour de la position
public record UpdateLocationRequest(
    @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
    @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng
) {}
```

### UserService.java

```java
@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final HtmlSanitizer sanitizer;
    private final GeometryFactory geometryFactory = new GeometryFactory(
        new PrecisionModel(), 4326);

    @Transactional(readOnly = true)
    public UserPrivateDto getMyProfile(UUID userId) {
        User user = findActiveUser(userId);
        return toPrivateDto(user);
    }

    @Transactional(readOnly = true)
    public UserPublicDto getPublicProfile(UUID targetId, UUID requesterId) {
        User target = findActiveUser(targetId);
        // Ne jamais retourner un profil supprimé ou inactif
        return toPublicDto(target, requesterId);
    }

    public UserPrivateDto updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = findActiveUser(userId);

        if (request.displayName() != null) {
            user.setDisplayName(sanitizer.sanitize(request.displayName()).strip());
        }
        if (request.bio() != null) {
            user.setBio(sanitizer.sanitize(request.bio()));
        }
        if (request.locationPublic() != null) {
            user.setLocationPublic(request.locationPublic());
        }
        if (request.onlineStatusVisible() != null) {
            user.setOnlineStatusVisible(request.onlineStatusVisible());
        }
        if (request.receiveMessages() != null) {
            user.setReceiveMessages(request.receiveMessages());
        }
        if (request.blurRadiusM() != null) {
            // Minimum 100m — on n'accepte pas de floutage inférieur
            user.setBlurRadiusM(Math.max(100, request.blurRadiusM()));
        }

        return toPrivateDto(userRepository.save(user));
    }

    public void updateLocation(UUID userId, UpdateLocationRequest request) {
        User user = findActiveUser(userId);
        Point point = geometryFactory.createPoint(
            new Coordinate(request.lng(), request.lat()));
        user.setLocation(point);
        user.setLastActiveAt(Instant.now());
        userRepository.save(user);
    }

    public void updateAvatar(UUID userId, String s3Url) {
        User user = findActiveUser(userId);
        // s3Url est le chemin retourné après upload + ré-encodage côté S3
        user.setAvatarUrl(s3Url);
        userRepository.save(user);
    }

    public void deactivateAccount(UUID userId) {
        User user = findActiveUser(userId);
        user.setIsActive(false);       // Suppression douce
        user.setLocationPublic(false); // Disparaître immédiatement de la carte
        userRepository.save(user);
        // TODO Phase 4 : déclencher la purge RGPD après délai légal
    }

    private User findActiveUser(UUID userId) {
        return userRepository.findById(userId)
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));
    }

    private UserPublicDto toPublicDto(User user, UUID requesterId) {
        boolean showOnline = Boolean.TRUE.equals(user.getOnlineStatusVisible())
            && user.getLastActiveAt() != null
            && user.getLastActiveAt().isAfter(Instant.now().minusSeconds(300)); // 5 min

        return new UserPublicDto(
            user.getId(),
            user.getDisplayName(),
            user.getBio(),
            user.getAvatarUrl(),
            user.getVerificationStatus().name(),
            List.of(), // badges — Phase 3
            List.of(), // activities — rempli par ActivityService
            showOnline
        );
    }
}
```

### UserController.java

```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final UserService userService;

    // GET /api/users/me
    @GetMapping("/me")
    public UserPrivateDto getMyProfile(@AuthenticationPrincipal UserPrincipal principal) {
        return userService.getMyProfile(principal.getId());
    }

    // PUT /api/users/me
    @PutMapping("/me")
    public UserPrivateDto updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(principal.getId(), request);
    }

    // PUT /api/users/me/location
    @PutMapping("/me/location")
    public ResponseEntity<Void> updateLocation(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateLocationRequest request) {
        userService.updateLocation(principal.getId(), request);
        return ResponseEntity.ok().build();
    }

    // POST /api/users/me/avatar
    // Multipart — le fichier est uploadé, ré-encodé, puis l'URL S3 est sauvegardée
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AvatarResponse> uploadAvatar(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file) {
        // Valider type MIME réel (pas le header déclaré)
        validateImageFile(file);
        String s3Url = storageService.uploadAndReencode(file, "avatars/" + principal.getId());
        userService.updateAvatar(principal.getId(), s3Url);
        return ResponseEntity.ok(new AvatarResponse(s3Url));
    }

    // GET /api/users/{id} — profil public
    @GetMapping("/{id}")
    public UserPublicDto getPublicProfile(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return userService.getPublicProfile(id, principal.getId());
    }

    // DELETE /api/users/me — suppression douce
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateAccount(@AuthenticationPrincipal UserPrincipal principal) {
        userService.deactivateAccount(principal.getId());
    }

    private void validateImageFile(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) { // Max 5 MB
            throw new InvalidFileException("Fichier invalide ou trop volumineux.");
        }
        // Vérifier les magic bytes (pas le Content-Type déclaré)
        // Utiliser Apache Tika ou vérification manuelle des premiers octets
    }
}
```

---

## Étape 4 — Activités

### DTOs Activités

```java
// Catégorie
public record CategoryDto(UUID id, String name, String icon, String colorRamp) {}

// Activité
public record ActivityDto(
    UUID id, String name, String slug, String description,
    UUID parentId, CategoryDto category
) {}

// Activité utilisateur (avec ses paramètres)
public record UserActivityDto(
    UUID id,
    ActivityDto activity,
    Boolean visibleOnMap,
    String customDescription,
    String level,
    String format,
    Instant createdAt,
    List<ProgramSummaryDto> programs
) {}

// Créer/modifier une activité sur son profil
public record UpsertUserActivityRequest(
    @NotNull UUID activityId,
    Boolean visibleOnMap,
    @Size(max = 500) String customDescription,
    ActivityLevel level,
    ActivityFormat format
) {}

// Résumé programme (pour liste)
public record ProgramSummaryDto(
    UUID id, String title, String status, boolean isPublic, Instant updatedAt
) {}
```

### ActivityController.java

```java
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    // GET /api/categories — liste toutes les catégories
    @GetMapping("/categories")
    public List<CategoryDto> getCategories() {
        return activityService.getAllCategories();
    }

    // GET /api/activities?categoryId=&search=&page=&size=
    @GetMapping("/activities")
    public Page<ActivityDto> searchActivities(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return activityService.searchActivities(categoryId, search,
            PageRequest.of(page, Math.min(size, 50)));
    }

    // GET /api/users/me/activities — mes activités
    @GetMapping("/users/me/activities")
    public List<UserActivityDto> getMyActivities(
            @AuthenticationPrincipal UserPrincipal principal) {
        return activityService.getUserActivities(principal.getId());
    }

    // POST /api/users/me/activities — ajouter une activité à mon profil
    @PostMapping("/users/me/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public UserActivityDto addActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpsertUserActivityRequest request) {
        return activityService.addActivityToProfile(principal.getId(), request);
    }

    // PUT /api/users/me/activities/{userActivityId}
    @PutMapping("/users/me/activities/{userActivityId}")
    public UserActivityDto updateActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userActivityId,
            @Valid @RequestBody UpsertUserActivityRequest request) {
        return activityService.updateUserActivity(principal.getId(), userActivityId, request);
    }

    // DELETE /api/users/me/activities/{userActivityId}
    @DeleteMapping("/users/me/activities/{userActivityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userActivityId) {
        activityService.removeActivityFromProfile(principal.getId(), userActivityId);
    }

    // PATCH /api/users/me/activities/{userActivityId}/visibility
    @PatchMapping("/users/me/activities/{userActivityId}/visibility")
    public UserActivityDto toggleVisibility(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID userActivityId,
            @RequestBody VisibilityRequest request) {
        return activityService.toggleMapVisibility(
            principal.getId(), userActivityId, request.visible());
    }
}
```

---

## Étape 5 — Programmes & Créneaux

### DTOs Programmes

```java
// Créer un programme
public record CreateProgramRequest(
    @NotNull UUID userActivityId,
    @NotBlank @Size(max = 150) String title,
    @Size(max = 3000) String description,
    Boolean isPublic
) {}

// Modifier un programme
public record UpdateProgramRequest(
    @Size(max = 150) String title,
    @Size(max = 3000) String description,
    ProgramStatus status,
    Boolean isPublic
) {}

// Programme complet
public record ProgramDto(
    UUID id,
    String title,
    String description,
    String status,
    Boolean isPublic,
    Instant createdAt,
    Instant updatedAt,
    List<ScheduleDto> schedules,
    List<ProgramMediaDto> media,
    Float averageScore,    // calculé
    Integer reviewCount    // calculé
) {}

// Créer un créneau
public record CreateScheduleRequest(
    @NotBlank @Size(max = 200) String placeName,
    @NotNull PlaceType placeType,
    @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
    @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
    String addressPublic,    // obligatoire si placeType == PUBLIC
    Boolean showExactAddress,
    @NotNull Instant startsAt,
    Instant endsAt,
    String recurrenceRule,   // RFC 5545
    @Min(1) Integer maxParticipants
) {}

// Créneau affiché
public record ScheduleDto(
    UUID id,
    String placeName,
    String placeType,
    Double lat,    // null si lieu privé non partagé
    Double lng,    // null si lieu privé non partagé
    String displayAddress,  // adresse ou null selon règles visibilité
    Instant startsAt,
    Instant endsAt,
    String recurrenceRule,
    Integer maxParticipants
) {}
```

### ProgramService.java (logique de visibilité adresse)

```java
@Service
@Transactional
@RequiredArgsConstructor
public class ProgramService {

    private final ProgramRepository programRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserActivityRepository userActivityRepository;
    private final HtmlSanitizer sanitizer;
    private final GeometryFactory geometryFactory = new GeometryFactory(
        new PrecisionModel(), 4326);

    public ProgramDto createProgram(UUID userId, CreateProgramRequest request) {
        UserActivity ua = userActivityRepository
            .findByIdAndUserId(request.userActivityId(), userId)
            .orElseThrow(() -> new ForbiddenException("Activité introuvable."));

        Program program = new Program();
        program.setUserActivity(ua);
        program.setTitle(sanitizer.sanitize(request.title()).strip());
        program.setDescription(sanitizer.sanitize(request.description()));
        program.setStatus(ProgramStatus.DRAFT);
        program.setIsPublic(request.isPublic() != null ? request.isPublic() : true);

        return toDto(programRepository.save(program));
    }

    public ProgramDto updateProgram(UUID userId, UUID programId,
                                     UpdateProgramRequest request) {
        Program program = findProgramOwnedBy(programId, userId);

        if (request.title() != null)
            program.setTitle(sanitizer.sanitize(request.title()).strip());
        if (request.description() != null)
            program.setDescription(sanitizer.sanitize(request.description()));
        if (request.status() != null) {
            // Archivage : ne jamais supprimer, toujours archiver
            program.setStatus(request.status());
            if (request.status() == ProgramStatus.ARCHIVED) {
                program.setArchivedAt(Instant.now());
            }
        }
        if (request.isPublic() != null) program.setIsPublic(request.isPublic());

        return toDto(programRepository.save(program));
    }

    public ScheduleDto addSchedule(UUID userId, UUID programId,
                                    CreateScheduleRequest request) {
        Program program = findProgramOwnedBy(programId, userId);

        // Règle visibilité adresse
        if (request.placeType() == PlaceType.PUBLIC && request.addressPublic() == null) {
            throw new ValidationException("L'adresse est obligatoire pour un lieu public.");
        }

        Schedule schedule = new Schedule();
        schedule.setProgram(program);
        schedule.setPlaceName(sanitizer.sanitize(request.placeName()).strip());
        schedule.setPlaceType(request.placeType());
        schedule.setLocation(geometryFactory.createPoint(
            new Coordinate(request.lng(), request.lat())));

        // Stocker l'adresse uniquement si lieu public ou consentement explicite
        if (request.placeType() == PlaceType.PUBLIC) {
            schedule.setAddressPublic(request.addressPublic());
        } else if (Boolean.TRUE.equals(request.showExactAddress())) {
            schedule.setAddressPublic(request.addressPublic());
            schedule.setShowExactAddress(true);
        }

        schedule.setStartsAt(request.startsAt());
        schedule.setEndsAt(request.endsAt());
        schedule.setRecurrenceRule(request.recurrenceRule());
        schedule.setMaxParticipants(request.maxParticipants());

        return toScheduleDto(scheduleRepository.save(schedule));
    }

    // Résolution de l'adresse affichée selon règles de visibilité
    public ScheduleDto toScheduleDto(Schedule s) {
        String displayAddress = null;
        Double displayLat = null;
        Double displayLng = null;

        if (s.getPlaceType() == PlaceType.PUBLIC) {
            displayAddress = s.getAddressPublic();
            displayLat = s.getLocation().getY();
            displayLng = s.getLocation().getX();
        } else if (Boolean.TRUE.equals(s.getShowExactAddress())) {
            displayAddress = s.getAddressPublic();
            displayLat = s.getLocation().getY();
            displayLng = s.getLocation().getX();
        }
        // Lieu privé non partagé : lat/lng/adresse restent null

        return new ScheduleDto(
            s.getId(), s.getPlaceName(), s.getPlaceType().name(),
            displayLat, displayLng, displayAddress,
            s.getStartsAt(), s.getEndsAt(),
            s.getRecurrenceRule(), s.getMaxParticipants()
        );
    }
}
```

### ProgramController.java

```java
@RestController
@RequestMapping("/api/programs")
@RequiredArgsConstructor
public class ProgramController {

    private final ProgramService programService;

    // GET /api/programs?userActivityId=&status=&page=&size=
    @GetMapping
    public Page<ProgramDto> getPrograms(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID userActivityId,
            @RequestParam(required = false) ProgramStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return programService.getUserPrograms(
            principal.getId(), userActivityId, status,
            PageRequest.of(page, Math.min(size, 50)));
    }

    // GET /api/programs/{id}
    @GetMapping("/{id}")
    public ProgramDto getProgram(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return programService.getProgram(id, principal.getId());
    }

    // POST /api/programs
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProgramDto createProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateProgramRequest request) {
        return programService.createProgram(principal.getId(), request);
    }

    // PUT /api/programs/{id}
    @PutMapping("/{id}")
    public ProgramDto updateProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProgramRequest request) {
        return programService.updateProgram(principal.getId(), id, request);
    }

    // DELETE /api/programs/{id} → archive (jamais suppression physique)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        programService.archiveProgram(principal.getId(), id);
    }

    // POST /api/programs/{id}/schedules
    @PostMapping("/{id}/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleDto addSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody CreateScheduleRequest request) {
        return programService.addSchedule(principal.getId(), id, request);
    }

    // PUT /api/programs/{id}/schedules/{scheduleId}
    @PutMapping("/{id}/schedules/{scheduleId}")
    public ScheduleDto updateSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody UpdateScheduleRequest request) {
        return programService.updateSchedule(principal.getId(), scheduleId, request);
    }

    // DELETE /api/programs/{id}/schedules/{scheduleId}
    @DeleteMapping("/{id}/schedules/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @PathVariable UUID scheduleId) {
        programService.deleteSchedule(principal.getId(), scheduleId);
    }
}
```

---

## Étape 6 — Carte (Map)

### DTOs Carte

```java
// Utilisateur affiché sur la carte
public record MapUserDto(
    UUID userId,
    String displayName,
    String avatarUrl,
    Double lat,           // coordonnée flouttée selon blur_radius_m
    Double lng,
    boolean isOnline,
    List<MapActivityBadgeDto> visibleActivities,
    String verificationStatus
) {}

// Badge d'activité affiché sur la carte
public record MapActivityBadgeDto(
    UUID activityId,
    String activityName,
    String level,
    String format,
    String categoryColorRamp
) {}

// Paramètres de recherche carte
public record MapSearchRequest(
    @NotNull Double lat,
    @NotNull Double lng,
    @NotNull @Min(500) @Max(50000) Integer radiusMeters,
    UUID activityId,   // filtrer par activité (optionnel)
    String level,
    String format
) {}
```

### MapService.java

```java
@Service
@RequiredArgsConstructor
public class MapService {

    private final UserRepository userRepository;
    private final UserActivityRepository userActivityRepository;
    private final Random random = new Random();

    public List<MapUserDto> getUsersOnMap(MapSearchRequest request, UUID requesterId) {
        // 1. Trouver les utilisateurs visibles dans le rayon
        List<User> nearbyUsers = userRepository.findVisibleUsersInRadius(
            request.lat(), request.lng(), request.radiusMeters(), 100, 0);

        // 2. Filtrer par activité si demandé
        if (request.activityId() != null) {
            Set<UUID> userIdsWithActivity = userActivityRepository
                .findUserIdsByActivityIdAndVisible(request.activityId());
            nearbyUsers = nearbyUsers.stream()
                .filter(u -> userIdsWithActivity.contains(u.getId()))
                .toList();
        }

        // 3. Ne jamais retourner le demandeur lui-même
        return nearbyUsers.stream()
            .filter(u -> !u.getId().equals(requesterId))
            .map(u -> toMapDto(u, request.activityId()))
            .toList();
    }

    private MapUserDto toMapDto(User user, UUID filterActivityId) {
        // Appliquer le floutage de position
        double[] blurred = applyBlur(
            user.getLocation().getY(),
            user.getLocation().getX(),
            user.getBlurRadiusM()
        );

        boolean isOnline = Boolean.TRUE.equals(user.getOnlineStatusVisible())
            && user.getLastActiveAt() != null
            && user.getLastActiveAt().isAfter(Instant.now().minusSeconds(300));

        List<MapActivityBadgeDto> activities = userActivityRepository
            .findVisibleByUserId(user.getId()).stream()
            .filter(ua -> filterActivityId == null
                || ua.getActivity().getId().equals(filterActivityId))
            .map(this::toActivityBadge)
            .toList();

        return new MapUserDto(
            user.getId(),
            user.getDisplayName(),
            user.getAvatarUrl(),
            blurred[0],
            blurred[1],
            isOnline,
            activities,
            user.getVerificationStatus().name()
        );
    }

    // Floutage aléatoire dans un cercle de rayon blur_radius_m
    // Formule de déplacement géodésique simplifié
    private double[] applyBlur(double lat, double lng, int radiusMeters) {
        double radiusDeg = radiusMeters / 111320.0;
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = random.nextDouble() * radiusDeg;
        double blurredLat = lat + distance * Math.cos(angle);
        double blurredLng = lng + distance * Math.sin(angle)
            / Math.cos(Math.toRadians(lat));
        return new double[]{
            Math.round(blurredLat * 10000.0) / 10000.0,
            Math.round(blurredLng * 10000.0) / 10000.0
        };
    }
}
```

### MapController.java

```java
@RestController
@RequestMapping("/api/map")
@RequiredArgsConstructor
public class MapController {

    private final MapService mapService;

    // GET /api/map/users?lat=&lng=&radiusMeters=&activityId=&level=&format=
    @GetMapping("/users")
    public List<MapUserDto> getUsersOnMap(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid MapSearchRequest request) {
        return mapService.getUsersOnMap(request, principal.getId());
    }
}
```

---

## Étape 7 — Chat en temps réel

### Configuration WebSocket

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtTokenProvider tokenProvider;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue"); // In-memory broker (Redis Phase 4)
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat")
            .setAllowedOriginPatterns("*") // À restreindre en production
            .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Valider le JWT au moment du handshake STOMP
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                    message, StompHeaderAccessor.class);
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String token = accessor.getFirstNativeHeader("Authorization");
                    if (token != null && token.startsWith("Bearer ")) {
                        token = token.substring(7);
                        if (tokenProvider.validateToken(token)) {
                            UUID userId = tokenProvider.extractUserId(token);
                            accessor.setUser(() -> userId.toString());
                        } else {
                            throw new MessageDeliveryException("Token WebSocket invalide.");
                        }
                    }
                }
                return message;
            }
        });
    }
}
```

### DTOs Chat

```java
// Créer une conversation
public record CreateConversationRequest(
    @NotNull UUID targetUserId,
    UUID activityContextId  // Activité qui a mené au contact (optionnel)
) {}

// Résumé conversation (pour la liste)
public record ConversationSummaryDto(
    UUID id,
    String type,
    UserPublicDto otherUser,     // Pour DIRECT
    String activityContextName,
    String lastMessageContent,
    Instant lastMessageAt,
    int unreadCount
) {}

// Message envoyé via WebSocket
public record SendMessageRequest(
    @NotNull UUID conversationId,
    @NotBlank @Size(max = 4000) String content
) {}

// Message reçu (broadcast WebSocket)
public record MessageDto(
    UUID id,
    UUID conversationId,
    UUID senderId,
    String senderName,
    String senderAvatarUrl,
    String content,
    String status,
    Instant sentAt
) {}
```

### ChatService.java

```java
@Service
@Transactional
@RequiredArgsConstructor
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final HtmlSanitizer sanitizer;

    public ConversationSummaryDto createConversation(UUID initiatorId,
                                                      CreateConversationRequest request) {
        // 1. Vérifier que la cible accepte les messages
        User target = userRepository.findById(request.targetUserId())
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));

        if (!Boolean.TRUE.equals(target.getReceiveMessages())) {
            throw new MessagingDisabledException(
                "Cet utilisateur n'accepte pas les messages.");
        }

        // 2. Vérifier si une conversation DIRECT existe déjà
        return conversationRepository
            .findDirectBetween(initiatorId, request.targetUserId())
            .map(this::toSummaryDto)
            .orElseGet(() -> {
                Conversation conv = new Conversation();
                conv.setType(ConversationType.DIRECT);

                if (request.activityContextId() != null) {
                    // Rattacher l'activité contexte si fournie
                    Activity ctx = activityRepository
                        .findById(request.activityContextId()).orElse(null);
                    conv.setActivityContext(ctx);
                }

                conv = conversationRepository.save(conv);
                addMember(conv, initiatorId);
                addMember(conv, request.targetUserId());
                return toSummaryDto(conv);
            });
    }

    public MessageDto sendMessage(UUID senderId, SendMessageRequest request) {
        // 1. Vérifier que l'expéditeur est membre de la conversation
        Conversation conv = conversationRepository
            .findByIdAndMemberId(request.conversationId(), senderId)
            .orElseThrow(() -> new ForbiddenException("Accès conversation refusé."));

        // 2. Sanitiser le contenu (anti-XSS obligatoire)
        String cleanContent = sanitizer.sanitize(request.content());
        if (!StringUtils.hasText(cleanContent)) {
            throw new ValidationException("Message vide après sanitisation.");
        }

        // 3. Persister
        Message message = new Message();
        message.setConversation(conv);
        message.setSender(userRepository.getReferenceById(senderId));
        message.setContent(cleanContent);
        message.setStatus(MessageStatus.SENT);
        message.setSentAt(Instant.now());
        message = messageRepository.save(message);

        // 4. Mettre à jour last_message_at
        conv.setLastMessageAt(message.getSentAt());
        conversationRepository.save(conv);

        // 5. Broadcast WebSocket aux membres
        MessageDto dto = toMessageDto(message);
        conv.getMembers().forEach(member -> {
            if (!member.getUser().getId().equals(senderId)) {
                messagingTemplate.convertAndSendToUser(
                    member.getUser().getId().toString(),
                    "/queue/messages",
                    dto
                );
            }
        });

        return dto;
    }

    public void markAsRead(UUID userId, UUID conversationId) {
        conversationRepository.updateLastReadAt(userId, conversationId, Instant.now());
    }
}
```

### ChatController.java (REST) + ChatWebSocketHandler.java

```java
// REST : liste et création de conversations
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    // GET /api/conversations
    @GetMapping
    public List<ConversationSummaryDto> getConversations(
            @AuthenticationPrincipal UserPrincipal principal) {
        return chatService.getConversations(principal.getId());
    }

    // POST /api/conversations
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationSummaryDto createConversation(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateConversationRequest request) {
        return chatService.createConversation(principal.getId(), request);
    }

    // GET /api/conversations/{id}/messages?before=&size=
    @GetMapping("/{id}/messages")
    public List<MessageDto> getMessages(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam(required = false) Instant before,
            @RequestParam(defaultValue = "30") int size) {
        return chatService.getMessages(principal.getId(), id, before,
            Math.min(size, 50));
    }

    // POST /api/conversations/{id}/read
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        chatService.markAsRead(principal.getId(), id);
        return ResponseEntity.ok().build();
    }
}

// WebSocket : envoi de messages en temps réel
@Controller
@RequiredArgsConstructor
public class ChatWebSocketHandler {

    private final ChatService chatService;

    // Client envoie vers /app/chat.send
    @MessageMapping("/chat.send")
    public void sendMessage(@Payload SendMessageRequest request,
                             Principal principal) {
        UUID senderId = UUID.fromString(principal.getName());
        chatService.sendMessage(senderId, request);
    }

    // Client envoie vers /app/chat.typing (indicateur de saisie)
    @MessageMapping("/chat.typing")
    public void typingIndicator(@Payload TypingRequest request,
                                 Principal principal) {
        UUID senderId = UUID.fromString(principal.getName());
        // Broadcast à l'autre membre sans persister
        messagingTemplate.convertAndSendToUser(
            request.targetUserId().toString(),
            "/queue/typing",
            new TypingDto(senderId, request.conversationId(), true)
        );
    }
}
```

---

## Étape 8 — Email transactionnel

### EmailService.java

```java
@Service
@RequiredArgsConstructor
public class EmailService {

    @Value("${email.from}") private String fromAddress;
    @Value("${email.base-url}") private String baseUrl;

    private final JavaMailSender mailSender;           // Spring Mail → Postmark/SendGrid
    private final TemplateEngine templateEngine;       // Thymeleaf ou Freemarker

    public void sendVerificationEmail(User user, String token) {
        String verifyUrl = baseUrl + "/verify-email?token=" + token;
        Context ctx = new Context();
        ctx.setVariable("name", user.getDisplayName());
        ctx.setVariable("verifyUrl", verifyUrl);
        sendHtml(user.getEmail(), "Vérifiez votre adresse Pair",
            "email/verify", ctx);
    }

    public void sendPasswordResetEmail(User user, String token) {
        String resetUrl = baseUrl + "/reset-password?token=" + token;
        Context ctx = new Context();
        ctx.setVariable("name", user.getDisplayName());
        ctx.setVariable("resetUrl", resetUrl);
        ctx.setVariable("expiresIn", "30 minutes");
        sendHtml(user.getEmail(), "Réinitialisation de mot de passe Pair",
            "email/reset-password", ctx);
    }

    private void sendHtml(String to, String subject, String template, Context ctx) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            String html = templateEngine.process(template, ctx);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            // Logger mais ne pas faire échouer l'opération principale
            log.error("Échec envoi email à {} : {}", to, e.getMessage());
        }
    }
}
```

---

## Étape 9 — Gestion globale des erreurs

### GlobalExceptionHandler.java

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + " : " + f.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return new ErrorResponse("VALIDATION_ERROR", message, Instant.now());
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(UserNotFoundException ex) {
        return new ErrorResponse("NOT_FOUND", ex.getMessage(), Instant.now());
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleForbidden(ForbiddenException ex) {
        return new ErrorResponse("FORBIDDEN", ex.getMessage(), Instant.now());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleUnauth(InvalidCredentialsException ex) {
        // Message générique — ne pas révéler si c'est le mot de passe ou l'email
        return new ErrorResponse("INVALID_CREDENTIALS",
            "Identifiants invalides.", Instant.now());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ErrorResponse handleRateLimit(TooManyRequestsException ex) {
        return new ErrorResponse("RATE_LIMITED", ex.getMessage(), Instant.now());
    }

    @ExceptionHandler(MessagingDisabledException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleMessagingDisabled(MessagingDisabledException ex) {
        return new ErrorResponse("MESSAGING_DISABLED", ex.getMessage(), Instant.now());
    }

    // Sécurité : ne jamais exposer la stack trace en production
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneric(Exception ex) {
        log.error("Erreur non gérée", ex);
        return new ErrorResponse("INTERNAL_ERROR",
            "Une erreur est survenue.", Instant.now());
    }
}
```

---

## Récapitulatif des endpoints Phase 1

### Auth
| Méthode | Route | Auth | Description |
|---------|-------|------|-------------|
| POST | /api/auth/register | Non | Inscription |
| POST | /api/auth/login | Non | Connexion |
| POST | /api/auth/refresh | Non | Renouveler le token |
| GET  | /api/auth/verify-email | Non | Vérifier l'email |
| POST | /api/auth/forgot-password | Non | Demande reset |
| POST | /api/auth/reset-password | Non | Reset mot de passe |

### Profil
| Méthode | Route | Auth | Description |
|---------|-------|------|-------------|
| GET    | /api/users/me | Oui | Mon profil |
| PUT    | /api/users/me | Oui | Modifier mon profil |
| PUT    | /api/users/me/location | Oui | Mettre à jour ma position |
| POST   | /api/users/me/avatar | Oui | Changer ma photo |
| DELETE | /api/users/me | Oui | Désactiver le compte |
| GET    | /api/users/{id} | Oui | Profil public |

### Activités
| Méthode | Route | Auth | Description |
|---------|-------|------|-------------|
| GET    | /api/categories | Oui | Liste catégories |
| GET    | /api/activities | Oui | Recherche activités |
| GET    | /api/users/me/activities | Oui | Mes activités |
| POST   | /api/users/me/activities | Oui | Ajouter activité |
| PUT    | /api/users/me/activities/{id} | Oui | Modifier activité |
| DELETE | /api/users/me/activities/{id} | Oui | Retirer activité |
| PATCH  | /api/users/me/activities/{id}/visibility | Oui | Toggle visibilité carte |

### Programmes & Créneaux
| Méthode | Route | Auth | Description |
|---------|-------|------|-------------|
| GET    | /api/programs | Oui | Mes programmes |
| GET    | /api/programs/{id} | Oui | Détail programme |
| POST   | /api/programs | Oui | Créer programme |
| PUT    | /api/programs/{id} | Oui | Modifier programme |
| DELETE | /api/programs/{id} | Oui | Archiver programme |
| POST   | /api/programs/{id}/schedules | Oui | Ajouter créneau |
| PUT    | /api/programs/{id}/schedules/{sid} | Oui | Modifier créneau |
| DELETE | /api/programs/{id}/schedules/{sid} | Oui | Supprimer créneau |

### Carte
| Méthode | Route | Auth | Description |
|---------|-------|------|-------------|
| GET | /api/map/users | Oui | Utilisateurs sur la carte |

### Chat (REST)
| Méthode | Route | Auth | Description |
|---------|-------|------|-------------|
| GET  | /api/conversations | Oui | Liste conversations |
| POST | /api/conversations | Oui | Créer conversation |
| GET  | /api/conversations/{id}/messages | Oui | Historique messages |
| POST | /api/conversations/{id}/read | Oui | Marquer comme lu |

### Chat (WebSocket STOMP)
| Destination | Direction | Description |
|-------------|-----------|-------------|
| /ws/chat | Handshake | Connexion WebSocket |
| /app/chat.send | Client → Serveur | Envoyer un message |
| /app/chat.typing | Client → Serveur | Indicateur de saisie |
| /user/{id}/queue/messages | Serveur → Client | Recevoir un message |
| /user/{id}/queue/typing | Serveur → Client | Indicateur de saisie |

