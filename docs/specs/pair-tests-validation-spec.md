# Pair — Étape 1 : Tests & Validation du Backend
## Spécification d'implémentation pour Claude Code

> **Prérequis** : data-model + phases 1 à 4 déjà implémentées.
>
> **Objectif** : valider que tout le backend fonctionne correctement avant
> de construire le client ou de déployer. Cette étape ne doit produire
> AUCUNE nouvelle fonctionnalité métier — uniquement des tests et la
> correction des bugs qu'ils révèlent.
>
> **Règle d'or** : un test qui échoue signale soit un bug réel à corriger,
> soit une spec mal interprétée à clarifier. Ne jamais adapter un test
> pour qu'il passe artificiellement.

---

## Stack de test

```xml
<!-- Déjà inclus dans spring-boot-starter-test, vérifier la présence de : -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>
  <scope>test</scope>
</dependency>

<!-- Testcontainers — base PostgreSQL réelle avec PostGIS/pgvector en test -->
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>postgresql</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>

<!-- Mockito (inclus dans starter-test, vérifier version récente) -->
<!-- AssertJ (inclus dans starter-test) -->

<!-- WebSocket test client -->
<dependency>
  <groupId>org.springframework</groupId>
  <artifactId>spring-websocket</artifactId>
  <scope>test</scope>
</dependency>

<!-- Pour les tests de sécurité (injection, XSS) -->
<dependency>
  <groupId>org.springframework.security</groupId>
  <artifactId>spring-security-test</artifactId>
  <scope>test</scope>
</dependency>
```

---

## Configuration Testcontainers (base unique pour tous les tests d'intégration)

### AbstractIntegrationTest.java

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // Image custom avec PostGIS + pgvector pré-installés
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("pair_test")
        .withUsername("test")
        .withPassword("test")
        .withInitScript("test-init.sql"); // active postgis + vector extensions

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        // Mocker les services externes coûteux par défaut
        registry.add("llm.api-key", () -> "test-key");
        registry.add("embedding.api-key", () -> "test-key");
    }

    @Autowired protected TestRestTemplate restTemplate;
    @Autowired protected ObjectMapper objectMapper;

    protected HttpHeaders authHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
```

### resources/test-init.sql

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
```

### application-test.properties

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.locations=classpath:db/migration
jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbm90LXByb2Q=
jwt.access-token-expiry-ms=900000
jwt.refresh-token-expiry-ms=2592000000
spring.mail.host=localhost
spring.mail.port=3025
email.from=test@pair.app
email.base-url=http://localhost:3000
aws.s3.bucket=test-bucket
redis.host=localhost
redis.port=6379
```

---

## Module 1 — Tests unitaires (services, logique métier pure)

> Mocker tous les repositories. Objectif : valider la logique métier isolée,
> rapide à exécuter (pas de Spring context complet).

### AuthServiceTest.java

```java
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtTokenProvider tokenProvider;
    @Mock EmailVerificationService emailVerificationService;
    @InjectMocks AuthService authService;

    @Test
    void register_devraitRejeter_siEmailDejaUtilise() {
        when(userRepository.existsByEmail("test@pair.app")).thenReturn(true);

        RegisterRequest request = new RegisterRequest(
            "test@pair.app", "Password123!", "Test User");

        assertThatThrownBy(() -> authService.register(request))
            .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_devraitHasherLeMotDePasse_avantSauvegarde() {
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(passwordEncoder.encode("Password123!")).thenReturn("$2a$hashed");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tokenProvider.generateAccessToken(any(), any())).thenReturn("access");
        when(tokenProvider.generateRefreshToken(any())).thenReturn("refresh");

        authService.register(new RegisterRequest(
            "test@pair.app", "Password123!", "Test"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$hashed");
        // Vérifier qu'on ne stocke jamais le mot de passe en clair
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("Password123!");
    }

    @Test
    void login_devraitRejeter_siMotDePasseIncorrect() {
        User user = buildActiveUser();
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() ->
            authService.login(new LoginRequest("test@pair.app", "wrong")))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_devraitRejeter_siCompteDesactive() {
        User inactiveUser = buildActiveUser();
        inactiveUser.setIsActive(false);
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        // findByEmail filtre déjà is_active=true côté repository

        assertThatThrownBy(() ->
            authService.login(new LoginRequest("test@pair.app", "any")))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_messageErreur_devraitEtreGenerique_pourEmailEtMotDePasse() {
        // Sécurité : ne jamais révéler si c'est l'email ou le mot de passe qui est faux
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            authService.login(new LoginRequest("inconnu@pair.app", "any")))
            .isInstanceOf(InvalidCredentialsException.class)
            .hasMessage("Identifiants invalides.");
    }

    private User buildActiveUser() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("test@pair.app");
        u.setPasswordHash("$2a$hashed");
        u.setIsActive(true);
        u.setVerificationStatus(VerificationStatus.UNVERIFIED);
        return u;
    }
}
```

### UserServiceTest.java — règles de visibilité

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock HtmlSanitizer sanitizer;
    @InjectMocks UserService userService;

    @Test
    void updateProfile_devraitSanitizerLaBio_pourEviterXSS() {
        User user = buildUser();
        when(userRepository.findById(user.getId()))
            .thenReturn(Optional.of(user).filter(u -> u.getIsActive()));
        String malicious = "<script>alert('xss')</script>Salut";
        String cleaned = "Salut";
        when(sanitizer.sanitize(malicious)).thenReturn(cleaned);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.updateProfile(user.getId(),
            new UpdateProfileRequest(null, malicious, null, null, null, null));

        verify(sanitizer).sanitize(malicious);
    }

    @Test
    void updateProfile_blurRadius_neDoitJamaisEtreInferieurA100m() {
        User user = buildUser();
        when(userRepository.findById(user.getId()))
            .thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.updateProfile(user.getId(),
            new UpdateProfileRequest(null, null, null, null, null, 10)); // trop petit

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getBlurRadiusM()).isGreaterThanOrEqualTo(100);
    }

    @Test
    void deactivateAccount_devraitMasquerImmediatement_dela carte() {
        User user = buildUser();
        user.setLocationPublic(true);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.deactivateAccount(user.getId());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getIsActive()).isFalse();
        assertThat(captor.getValue().getLocationPublic()).isFalse();
    }

    private User buildUser() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setIsActive(true);
        u.setBlurRadiusM(500);
        return u;
    }
}
```

### ProgramServiceTest.java — règles d'adresse critiques

```java
@ExtendWith(MockitoExtension.class)
class ProgramServiceTest {

    @Mock ProgramRepository programRepository;
    @Mock ScheduleRepository scheduleRepository;
    @Mock UserActivityRepository userActivityRepository;
    @Mock HtmlSanitizer sanitizer;
    @InjectMocks ProgramService programService;

    @Test
    void addSchedule_lieuPublic_doitExigerAdresse() {
        Program program = buildOwnedProgram();
        when(programRepository.findById(any())).thenReturn(Optional.of(program));

        CreateScheduleRequest request = new CreateScheduleRequest(
            "Stade municipal", PlaceType.PUBLIC, 48.85, 2.35,
            null, // adresse manquante
            false, Instant.now(), null, null, null);

        assertThatThrownBy(() ->
            programService.addSchedule(program.getUserActivity().getUser().getId(),
                program.getId(), request))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("adresse");
    }

    @Test
    void addSchedule_lieuPrive_sansConsentement_neDoitJamaisExposerAdresse() {
        Program program = buildOwnedProgram();
        when(programRepository.findById(any())).thenReturn(Optional.of(program));
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateScheduleRequest request = new CreateScheduleRequest(
            "Chez moi", PlaceType.PRIVATE, 48.85, 2.35,
            "12 rue de la Paix", // adresse fournie
            false, // mais PAS de consentement explicite
            Instant.now(), null, null, null);

        ScheduleDto result = programService.addSchedule(
            program.getUserActivity().getUser().getId(), program.getId(), request);

        // L'adresse ne doit JAMAIS apparaître dans le DTO retourné
        assertThat(result.displayAddress()).isNull();
        assertThat(result.lat()).isNull();
        assertThat(result.lng()).isNull();
    }

    @Test
    void addSchedule_lieuPrive_avecConsentement_doitExposerAdresse() {
        Program program = buildOwnedProgram();
        when(programRepository.findById(any())).thenReturn(Optional.of(program));
        when(scheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateScheduleRequest request = new CreateScheduleRequest(
            "Chez moi", PlaceType.PRIVATE, 48.85, 2.35,
            "12 rue de la Paix",
            true, // consentement explicite
            Instant.now(), null, null, null);

        ScheduleDto result = programService.addSchedule(
            program.getUserActivity().getUser().getId(), program.getId(), request);

        assertThat(result.displayAddress()).isEqualTo("12 rue de la Paix");
    }

    @Test
    void updateProgram_archive_neDoitJamaisSupprimerPhysiquement() {
        Program program = buildOwnedProgram();
        when(programRepository.findById(any())).thenReturn(Optional.of(program));
        when(programRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        programService.updateProgram(
            program.getUserActivity().getUser().getId(), program.getId(),
            new UpdateProgramRequest(null, null, ProgramStatus.ARCHIVED, null));

        verify(programRepository, never()).delete(any());
        verify(programRepository, never()).deleteById(any());
        ArgumentCaptor<Program> captor = ArgumentCaptor.forClass(Program.class);
        verify(programRepository).save(captor.capture());
        assertThat(captor.getValue().getArchivedAt()).isNotNull();
    }

    private Program buildOwnedProgram() {
        User owner = new User();
        owner.setId(UUID.randomUUID());
        UserActivity ua = new UserActivity();
        ua.setUser(owner);
        Program p = new Program();
        p.setId(UUID.randomUUID());
        p.setUserActivity(ua);
        return p;
    }
}
```

### ChatServiceTest.java

```java
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock ConversationRepository conversationRepository;
    @Mock MessageRepository messageRepository;
    @Mock UserRepository userRepository;
    @Mock SimpMessagingTemplate messagingTemplate;
    @Mock HtmlSanitizer sanitizer;
    @InjectMocks ChatService chatService;

    @Test
    void createConversation_devraitRejeter_siCibleNAccepteAucunMessage() {
        UUID targetId = UUID.randomUUID();
        User target = new User();
        target.setId(targetId);
        target.setReceiveMessages(false);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> chatService.createConversation(
            UUID.randomUUID(), new CreateConversationRequest(targetId, null)))
            .isInstanceOf(MessagingDisabledException.class);
    }

    @Test
    void sendMessage_devraitSanitizerLeContenu_avantPersistance() {
        UUID senderId = UUID.randomUUID();
        Conversation conv = buildConversationWithMember(senderId);
        when(conversationRepository.findByIdAndMemberId(any(), eq(senderId)))
            .thenReturn(Optional.of(conv));
        String malicious = "<img src=x onerror=alert(1)>Salut";
        when(sanitizer.sanitize(malicious)).thenReturn("Salut");
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        chatService.sendMessage(senderId,
            new SendMessageRequest(conv.getId(), malicious));

        verify(sanitizer).sanitize(malicious);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getContent()).doesNotContain("<script");
        assertThat(captor.getValue().getContent()).doesNotContain("onerror");
    }

    @Test
    void sendMessage_devraitRejeter_siExpediteurNonMembre() {
        UUID senderId = UUID.randomUUID();
        when(conversationRepository.findByIdAndMemberId(any(), eq(senderId)))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.sendMessage(senderId,
            new SendMessageRequest(UUID.randomUUID(), "test")))
            .isInstanceOf(ForbiddenException.class);
    }

    private Conversation buildConversationWithMember(UUID userId) {
        Conversation conv = new Conversation();
        conv.setId(UUID.randomUUID());
        ConversationMember member = new ConversationMember();
        User u = new User(); u.setId(userId);
        member.setUser(u);
        conv.setMembers(List.of(member));
        return conv;
    }
}
```

### ReviewServiceTest.java — règles de crédibilité critiques

```java
@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock ReviewRepository reviewRepository;
    @Mock ProgramRepository programRepository;
    @Mock ConversationRepository conversationRepository;
    @Mock UserRepository userRepository;
    @Mock HtmlSanitizer sanitizer;
    @InjectMocks ReviewService reviewService;

    @Test
    void createReview_devraitRejeter_auteurNoteSonPropreProgramme() {
        UUID ownerId = UUID.randomUUID();
        Program program = buildProgramOwnedBy(ownerId);
        when(programRepository.findById(program.getId())).thenReturn(Optional.of(program));

        CreateReviewRequest request = new CreateReviewRequest(
            program.getId(), UUID.randomUUID(), 5f, "Top", null);

        // L'auteur EST le propriétaire
        assertThatThrownBy(() -> reviewService.createReview(ownerId, request))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("propre programme");
    }

    @Test
    void createReview_devraitRejeter_sansInteractionProuvee() {
        UUID ownerId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        Program program = buildProgramOwnedBy(ownerId);
        when(programRepository.findById(program.getId())).thenReturn(Optional.of(program));
        when(conversationRepository.findDirectBetween(reviewerId, ownerId))
            .thenReturn(Optional.empty()); // AUCUNE conversation

        CreateReviewRequest request = new CreateReviewRequest(
            program.getId(), UUID.randomUUID(), 5f, "Top", null);

        assertThatThrownBy(() -> reviewService.createReview(reviewerId, request))
            .isInstanceOf(InsufficientInteractionException.class);

        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createReview_devraitRejeter_siDejaNoteUneFois() {
        UUID ownerId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        Program program = buildProgramOwnedBy(ownerId);
        when(programRepository.findById(program.getId())).thenReturn(Optional.of(program));
        when(conversationRepository.findDirectBetween(reviewerId, ownerId))
            .thenReturn(Optional.of(new Conversation()));
        when(reviewRepository.existsByProgramIdAndReviewerId(program.getId(), reviewerId))
            .thenReturn(true);

        CreateReviewRequest request = new CreateReviewRequest(
            program.getId(), UUID.randomUUID(), 5f, "Top", null);

        assertThatThrownBy(() -> reviewService.createReview(reviewerId, request))
            .isInstanceOf(DuplicateException.class);
    }

    @Test
    void createReview_accepteAvis_quandToutEstValide() {
        UUID ownerId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        Program program = buildProgramOwnedBy(ownerId);
        when(programRepository.findById(program.getId())).thenReturn(Optional.of(program));
        when(conversationRepository.findDirectBetween(reviewerId, ownerId))
            .thenReturn(Optional.of(new Conversation()));
        when(reviewRepository.existsByProgramIdAndReviewerId(any(), any()))
            .thenReturn(false);
        when(sanitizer.sanitize(any())).thenReturn("Super programme");
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateReviewRequest request = new CreateReviewRequest(
            program.getId(), UUID.randomUUID(), 5f, "Super programme", null);

        assertThatCode(() -> reviewService.createReview(reviewerId, request))
            .doesNotThrowAnyException();
    }

    private Program buildProgramOwnedBy(UUID ownerId) {
        User owner = new User(); owner.setId(ownerId);
        UserActivity ua = new UserActivity(); ua.setUser(owner);
        Program p = new Program(); p.setId(UUID.randomUUID()); p.setUserActivity(ua);
        return p;
    }
}
```

### PeerRecommendationServiceTest.java

```java
@ExtendWith(MockitoExtension.class)
class PeerRecommendationServiceTest {

    @Mock PeerRecommendationRepository recommendationRepository;
    @Mock ConversationRepository conversationRepository;
    @Mock UserRepository userRepository;
    @Mock HtmlSanitizer sanitizer;
    @Mock BadgeService badgeService;
    @InjectMocks PeerRecommendationService recommendationService;

    @Test
    void create_devraitRejeter_autoRecommandation() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(() -> recommendationService.create(userId,
            new CreateRecommendationRequest(userId, UUID.randomUUID(), null)))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void create_devraitRejeter_sansConversationEntreLesDeux() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        UUID proofId = UUID.randomUUID();
        when(conversationRepository.findByIdAndBothMembers(proofId, fromId, toId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> recommendationService.create(fromId,
            new CreateRecommendationRequest(toId, proofId, null)))
            .isInstanceOf(InsufficientInteractionException.class);
    }

    @Test
    void create_devraitRejeter_doublonDeRecommandation() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        UUID proofId = UUID.randomUUID();
        when(conversationRepository.findByIdAndBothMembers(proofId, fromId, toId))
            .thenReturn(Optional.of(new Conversation()));
        when(recommendationRepository.existsByFromUserIdAndToUserId(fromId, toId))
            .thenReturn(true);

        assertThatThrownBy(() -> recommendationService.create(fromId,
            new CreateRecommendationRequest(toId, proofId, null)))
            .isInstanceOf(DuplicateException.class);
    }
}
```

### BadgeServiceTest.java

```java
@ExtendWith(MockitoExtension.class)
class BadgeServiceTest {

    @Mock BadgeRepository badgeRepository;
    @Mock BadgeAwardRepository badgeAwardRepository;
    @Mock UserRepository userRepository;
    @Mock ProgramRepository programRepository;
    @InjectMocks BadgeService badgeService;

    @Test
    void evaluateBadges_neDoitPasRedonnerUnBadgeDejaObtenu() {
        UUID userId = UUID.randomUUID();
        User user = new User(); user.setId(userId);
        user.setVerificationStatus(VerificationStatus.EMAIL_VERIFIED);

        Badge verifiedBadge = new Badge();
        verifiedBadge.setCode("VERIFIED_EMAIL");
        verifiedBadge.setConditionType(BadgeConditionType.VERIFICATION);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(badgeRepository.findAll()).thenReturn(List.of(verifiedBadge));

        BadgeAward existingAward = new BadgeAward();
        existingAward.setBadge(verifiedBadge);
        when(badgeAwardRepository.findByUserId(userId))
            .thenReturn(List.of(existingAward)); // déjà obtenu

        badgeService.evaluateBadges(userId);

        verify(badgeAwardRepository, never()).save(any());
    }

    @Test
    void evaluateBadges_devraitDecernerVerifiedEmail_siConditionRemplie() {
        UUID userId = UUID.randomUUID();
        User user = new User(); user.setId(userId);
        user.setVerificationStatus(VerificationStatus.EMAIL_VERIFIED);

        Badge verifiedBadge = new Badge();
        verifiedBadge.setCode("VERIFIED_EMAIL");
        verifiedBadge.setConditionType(BadgeConditionType.VERIFICATION);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(badgeRepository.findAll()).thenReturn(List.of(verifiedBadge));
        when(badgeAwardRepository.findByUserId(userId)).thenReturn(List.of());

        badgeService.evaluateBadges(userId);

        verify(badgeAwardRepository).save(any());
    }
}
```

---

## Module 2 — Tests d'intégration (boucle complète end-to-end)

> Spring context complet + base PostgreSQL réelle via Testcontainers.
> Objectif : valider que les couches s'articulent correctement.

### AuthFlowIntegrationTest.java

```java
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    @Test
    void parcoursComplet_inscriptionVerificationConnexion() {
        // 1. Inscription
        RegisterRequest registerReq = new RegisterRequest(
            "nouveau@pair.app", "MotDePasse123!", "Nouvel Utilisateur");
        ResponseEntity<AuthResponse> registerResp = restTemplate.postForEntity(
            "/api/auth/register", registerReq, AuthResponse.class);

        assertThat(registerResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResp.getBody().verificationStatus()).isEqualTo("UNVERIFIED");

        // 2. Login immédiat possible même non vérifié
        LoginRequest loginReq = new LoginRequest("nouveau@pair.app", "MotDePasse123!");
        ResponseEntity<AuthResponse> loginResp = restTemplate.postForEntity(
            "/api/auth/login", loginReq, AuthResponse.class);

        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResp.getBody().accessToken()).isNotBlank();

        // 3. Profil accessible avec le token
        HttpEntity<Void> entity = new HttpEntity<>(
            authHeaders(loginResp.getBody().accessToken()));
        ResponseEntity<UserPrivateDto> profileResp = restTemplate.exchange(
            "/api/users/me", HttpMethod.GET, entity, UserPrivateDto.class);

        assertThat(profileResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profileResp.getBody().email()).isEqualTo("nouveau@pair.app");
    }

    @Test
    void register_devraitRetourner409_siEmailDejaUtilise() {
        RegisterRequest req = new RegisterRequest(
            "doublon@pair.app", "Password123!", "User1");
        restTemplate.postForEntity("/api/auth/register", req, AuthResponse.class);

        ResponseEntity<ErrorResponse> resp = restTemplate.postForEntity(
            "/api/auth/register", req, ErrorResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void accesSansToken_devraitRetourner401() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
            "/api/users/me", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void accesAvecTokenInvalide_devraitRetourner401() {
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders("token.invalide.xyz"));
        ResponseEntity<String> resp = restTemplate.exchange(
            "/api/users/me", HttpMethod.GET, entity, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

### MapVisibilityIntegrationTest.java — cœur du modèle de confiance

```java
class MapVisibilityIntegrationTest extends AbstractIntegrationTest {

    @Test
    void utilisateurMasque_neDoitJamaisApparaitreSurLaCarte() {
        // 1. Créer deux utilisateurs proches géographiquement
        String tokenA = registerAndLogin("userA@pair.app");
        String tokenB = registerAndLogin("userB@pair.app");

        // 2. userB se positionne mais NE PAS activer locationPublic
        updateLocation(tokenB, 48.8566, 2.3522);
        updateProfile(tokenB, Map.of("locationPublic", false));

        // 3. userA active sa position et cherche autour de lui
        updateLocation(tokenA, 48.8567, 2.3523);
        updateProfile(tokenA, Map.of("locationPublic", true));

        List<MapUserDto> results = getMapUsers(tokenA, 48.8566, 2.3522, 5000);

        // userB ne doit JAMAIS apparaître
        assertThat(results).noneMatch(u -> u.displayName().equals("userB"));
    }

    @Test
    void compteDesactive_neDoitJamaisApparaitreSurLaCarte() {
        String tokenA = registerAndLogin("active@pair.app");
        String tokenB = registerAndLogin("supprime@pair.app");

        updateLocation(tokenB, 48.8566, 2.3522);
        updateProfile(tokenB, Map.of("locationPublic", true));
        deactivateAccount(tokenB);

        updateLocation(tokenA, 48.8567, 2.3523);
        List<MapUserDto> results = getMapUsers(tokenA, 48.8566, 2.3522, 5000);

        assertThat(results).noneMatch(u -> u.displayName().equals("supprime"));
    }

    @Test
    void utilisateurHorsRayon_neDoitPasApparaitre() {
        String tokenA = registerAndLogin("paris@pair.app");
        String tokenB = registerAndLogin("marseille@pair.app");

        updateLocation(tokenB, 43.2965, 5.3698); // Marseille
        updateProfile(tokenB, Map.of("locationPublic", true));

        updateLocation(tokenA, 48.8566, 2.3522); // Paris
        List<MapUserDto> results = getMapUsers(tokenA, 48.8566, 2.3522, 5000); // 5km

        assertThat(results).noneMatch(u -> u.displayName().equals("marseille"));
    }

    @Test
    void positionAffichee_doitEtreFlouttee_pasExacte() {
        String tokenA = registerAndLogin("observateur@pair.app");
        String tokenB = registerAndLogin("observe@pair.app");

        double exactLat = 48.85660000;
        double exactLng = 2.35220000;
        updateLocation(tokenB, exactLat, exactLng);
        updateProfile(tokenB, Map.of("locationPublic", true, "blurRadiusM", 500));

        updateLocation(tokenA, exactLat + 0.0001, exactLng + 0.0001);
        List<MapUserDto> results = getMapUsers(tokenA, exactLat, exactLng, 5000);

        MapUserDto observed = results.stream()
            .filter(u -> u.displayName().equals("observe")).findFirst().orElseThrow();

        // La position affichée ne doit JAMAIS être exactement la position réelle
        assertThat(observed.lat()).isNotEqualTo(exactLat);
        assertThat(observed.lng()).isNotEqualTo(exactLng);
    }

    // Helpers réutilisés dans toute la classe
    private String registerAndLogin(String email) { /* ... */ return "token"; }
    private void updateLocation(String token, double lat, double lng) { /* ... */ }
    private void updateProfile(String token, Map<String, Object> fields) { /* ... */ }
    private void deactivateAccount(String token) { /* ... */ }
    private List<MapUserDto> getMapUsers(String token, double lat, double lng, int radius) {
        return List.of(); // implémentation réelle via restTemplate
    }
}
```

### ChatFlowIntegrationTest.java

```java
class ChatFlowIntegrationTest extends AbstractIntegrationTest {

    @Test
    void conversation_neDoitJamaisSeCreer_siCibleRefuseLesMessages() {
        String tokenA = registerAndLogin("initiateur@pair.app");
        String tokenB = registerAndLogin("ferme@pair.app");
        updateProfile(tokenB, Map.of("receiveMessages", false));

        ResponseEntity<ErrorResponse> resp = postConversation(tokenA,
            getUserId(tokenB), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void message_contenuXSS_devraitEtreNettoye_avantStockage() {
        String tokenA = registerAndLogin("a@pair.app");
        String tokenB = registerAndLogin("b@pair.app");
        UUID conversationId = createConversation(tokenA, getUserId(tokenB));

        sendMessageViaRest(tokenA, conversationId,
            "<script>alert('hack')</script>Salut !");

        List<MessageDto> messages = getMessages(tokenB, conversationId);
        assertThat(messages).isNotEmpty();
        assertThat(messages.get(0).content()).doesNotContain("<script>");
    }

    @Test
    void nonMembre_neDoitJamaisAccederALaConversation() {
        String tokenA = registerAndLogin("a@pair.app");
        String tokenB = registerAndLogin("b@pair.app");
        String tokenC = registerAndLogin("intrus@pair.app"); // pas membre
        UUID conversationId = createConversation(tokenA, getUserId(tokenB));

        ResponseEntity<ErrorResponse> resp = getMessagesRaw(tokenC, conversationId);

        assertThat(resp.getStatusCode())
            .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }
}
```

### SecurityInjectionIntegrationTest.java — tests d'attaque

```java
class SecurityInjectionIntegrationTest extends AbstractIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "'; DROP TABLE users; --",
        "' OR '1'='1",
        "admin'--",
        "1; DELETE FROM users WHERE '1'='1"
    })
    void champEmail_devraitResisterAuxInjectionsSQL(String payload) {
        RegisterRequest req = new RegisterRequest(
            payload + "@test.com", "Password123!", "Test");

        ResponseEntity<?> resp = restTemplate.postForEntity(
            "/api/auth/register", req, Object.class);

        // Ne doit jamais planter le serveur, doit retourner 201 ou 400 proprement
        assertThat(resp.getStatusCode()).isIn(
            HttpStatus.CREATED, HttpStatus.BAD_REQUEST, HttpStatus.CONFLICT);

        // Vérifier que la table users existe toujours (pas de DROP réussi)
        ResponseEntity<AuthResponse> loginCheck = restTemplate.postForEntity(
            "/api/auth/login",
            new LoginRequest("verification@test.com", "x"), AuthResponse.class);
        assertThat(loginCheck.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<script>alert('xss')</script>",
        "<img src=x onerror=alert(1)>",
        "javascript:alert(1)",
        "<svg onload=alert(1)>"
    })
    void bioProfil_devraitNeutraliserLeXSS(String payload) {
        String token = registerAndLogin("xsstest@pair.app");

        updateProfile(token, Map.of("bio", payload));

        UserPrivateDto profile = getMyProfile(token);
        assertThat(profile.bio()).doesNotContain("<script");
        assertThat(profile.bio()).doesNotContain("onerror");
        assertThat(profile.bio()).doesNotContain("onload");
        assertThat(profile.bio()).doesNotContain("javascript:");
    }

    @Test
    void uploadAvatar_devraitRejeter_fichierNonImage() {
        String token = registerAndLogin("upload@pair.app");
        byte[] fakeExeAsJpg = "MZ\u0090\u0000\u0003".getBytes(); // header .exe

        ResponseEntity<ErrorResponse> resp = uploadAvatarWithBytes(
            token, fakeExeAsJpg, "avatar.jpg", "image/jpeg"); // Content-Type mensonger

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadAvatar_devraitRejeter_fichierTropVolumineux() {
        String token = registerAndLogin("bigfile@pair.app");
        byte[] bigFile = new byte[6 * 1024 * 1024]; // 6 MB > limite de 5MB

        ResponseEntity<ErrorResponse> resp = uploadAvatarWithBytes(
            token, bigFile, "big.jpg", "image/jpeg");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rateLimiting_devraitBloquer_apresTropDeTentativesLogin() {
        for (int i = 0; i < 10; i++) {
            restTemplate.postForEntity("/api/auth/login",
                new LoginRequest("inconnu@pair.app", "wrong"), ErrorResponse.class);
        }

        ResponseEntity<ErrorResponse> resp = restTemplate.postForEntity(
            "/api/auth/login",
            new LoginRequest("inconnu@pair.app", "wrong"), ErrorResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
```

### WebSocketChatIntegrationTest.java

```java
class WebSocketChatIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort private int port;

    @Test
    void messageEnvoyeViaWebSocket_doitEtreRecuParLAutreMembre()
            throws Exception {
        String tokenA = registerAndLogin("wsA@pair.app");
        String tokenB = registerAndLogin("wsB@pair.app");
        UUID conversationId = createConversation(tokenA, getUserId(tokenB));

        WebSocketStompClient stompClient = new WebSocketStompClient(
            new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + tokenB);

        CompletableFuture<MessageDto> receivedMessage = new CompletableFuture<>();

        StompSession sessionB = stompClient.connectAsync(
            "ws://localhost:" + port + "/ws/chat",
            new WebSocketHttpHeaders(), connectHeaders,
            new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);

        sessionB.subscribe("/user/queue/messages", new StompFrameHandler() {
            public Type getPayloadType(StompHeaders headers) { return MessageDto.class; }
            public void handleFrame(StompHeaders headers, Object payload) {
                receivedMessage.complete((MessageDto) payload);
            }
        });

        // userA envoie un message via REST (déclenche le broadcast WebSocket)
        sendMessageViaRest(tokenA, conversationId, "Salut via WebSocket !");

        MessageDto received = receivedMessage.get(5, TimeUnit.SECONDS);
        assertThat(received.content()).isEqualTo("Salut via WebSocket !");
        assertThat(received.conversationId()).isEqualTo(conversationId.toString());
    }

    @Test
    void connexionWebSocket_devraitEchouer_sansTokenValide() {
        WebSocketStompClient stompClient = new WebSocketStompClient(
            new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer token.invalide");

        assertThatThrownBy(() ->
            stompClient.connectAsync("ws://localhost:" + port + "/ws/chat",
                new WebSocketHttpHeaders(), connectHeaders,
                new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(Exception.class);
    }
}
```

### SemanticSearchIntegrationTest.java

> Mocker l'appel LLM externe (coût + non-déterminisme), tester le pipeline
> géo + vectoriel avec un embedding fixe.

```java
class SemanticSearchIntegrationTest extends AbstractIntegrationTest {

    @MockBean LlmIntentExtractor intentExtractor;
    @MockBean EmbeddingService embeddingService;

    @Test
    void recherche_questionVague_devraitRetournerClarification() {
        when(intentExtractor.extractIntent("je veux faire du sport"))
            .thenReturn(new SearchIntent(null, "Sport", null, null, 5000,
                null, true, "Quel type de sport vous intéresse ?"));

        String token = registerAndLogin("searcher@pair.app");
        SearchResponse resp = search(token, "je veux faire du sport", 48.85, 2.35);

        assertThat(resp.type()).isEqualTo("clarification");
        assertThat(resp.clarificationQuestion()).isNotBlank();
        // Aucun embedding ne doit être généré inutilement
        verify(embeddingService, never()).generateEmbedding(any());
    }

    @Test
    void recherche_aucunResultat_devraitProposerAlternatives() {
        when(intentExtractor.extractIntent(any()))
            .thenReturn(new SearchIntent("escalade", "Sport", null, null,
                5000, null, false, null));
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[1536]);
        when(embeddingService.toVectorString(any())).thenReturn("[0,0,...]");

        String token = registerAndLogin("noresult@pair.app");
        SearchResponse resp = search(token,
            "je cherche un partenaire d'escalade", 48.85, 2.35);

        assertThat(resp.type()).isEqualTo("empty");
        assertThat(resp.suggestedAlternatives()).isNotEmpty();
    }

    @Test
    void recherche_neDoitJamaisRetourner_programmeNonPublic() {
        // Créer un programme privé proche, vérifier qu'il n'apparaît jamais
        // dans les résultats même avec un match sémantique parfait
        String ownerToken = registerAndLogin("owner@pair.app");
        updateLocation(ownerToken, 48.8566, 2.3522);
        UUID programId = createProgram(ownerToken, "Yoga privé", false /* isPublic */);

        when(intentExtractor.extractIntent(any()))
            .thenReturn(new SearchIntent("yoga", null, null, null,
                5000, null, false, null));
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[1536]);
        when(embeddingService.toVectorString(any())).thenReturn("[0,0,...]");

        String searcherToken = registerAndLogin("searcher2@pair.app");
        SearchResponse resp = search(searcherToken, "je cherche du yoga", 48.8566, 2.3522);

        assertThat(resp.results())
            .noneMatch(r -> r.id().equals(programId));
    }
}
```

---

## Module 3 — Tests des règles métier de la Phase 4 (Redis, RGPD)

### GdprServiceIntegrationTest.java

```java
class GdprServiceIntegrationTest extends AbstractIntegrationTest {

    @Test
    void purgeDeactivatedAccounts_devraitAnonymiser_pasSupprimerLesMessages() {
        String tokenA = registerAndLogin("toDelete@pair.app");
        String tokenB = registerAndLogin("remains@pair.app");
        UUID conversationId = createConversation(tokenA, getUserId(tokenB));
        sendMessageViaRest(tokenA, conversationId, "Message avant suppression");

        UUID userIdToDelete = getUserId(tokenA);
        deactivateAccount(tokenA);

        // Simuler le passage du délai de 30 jours en forçant le job
        gdprService.purgeUser(userRepository.findById(userIdToDelete).orElseThrow());

        // Les messages restent visibles pour userB mais l'expéditeur est anonymisé
        List<MessageDto> messages = getMessages(tokenB, conversationId);
        assertThat(messages).isNotEmpty();
        assertThat(messages.get(0).senderName()).isNotEqualTo("toDelete");
    }

    @Test
    void exportUserData_devraitInclureToutesLesCategoriesDeDonnees() {
        String token = registerAndLogin("export@pair.app");
        ResponseEntity<GdprExportDto> resp = restTemplate.exchange(
            "/api/gdpr/export", HttpMethod.GET,
            new HttpEntity<>(authHeaders(token)), GdprExportDto.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }
}
```

### RateLimiterServiceTest.java

```java
@SpringBootTest
@Testcontainers
class RateLimiterServiceTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry registry) {
        registry.add("redis.host", redis::getHost);
        registry.add("redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired RateLimiterService rateLimiterService;

    @Test
    void checkSearch_devraitBloquer_apres30RequetesParMinute() {
        UUID userId = UUID.randomUUID();
        for (int i = 0; i < 30; i++) {
            assertThatCode(() -> rateLimiterService.checkSearch(userId))
                .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> rateLimiterService.checkSearch(userId))
            .isInstanceOf(TooManyRequestsException.class);
    }
}
```

---

## Module 4 — Checklist manuelle de sécurité (à exécuter avant déploiement)

> À documenter dans un fichier `SECURITY_CHECKLIST.md` une fois validée.
> Chaque ligne doit être cochée manuellement avec Postman/curl en plus
> des tests automatisés ci-dessus.

```markdown
## Checklist sécurité — Pair Backend

### Authentification
- [ ] Un token JWT expiré est bien rejeté (401)
- [ ] Un token JWT modifié manuellement (signature invalide) est rejeté
- [ ] Le refresh token ne peut pas être utilisé comme access token
- [ ] Les mots de passe en base sont bien hashés (vérifier en BDD directement)
- [ ] Le rate limiting bloque après N tentatives de login

### Visibilité & vie privée
- [ ] Un utilisateur avec locationPublic=false n'apparaît jamais sur /api/map/users
- [ ] Un compte désactivé (is_active=false) n'apparaît dans aucun endpoint public
- [ ] L'adresse d'un Schedule PRIVATE sans showExactAddress reste null dans la réponse
- [ ] La position sur la carte est toujours flouttée (jamais lat/lng exacts)
- [ ] Le statut "en ligne" respecte onlineStatusVisible

### Contenu utilisateur
- [ ] Un payload XSS dans bio/description/message est neutralisé
- [ ] Un payload SQL dans n'importe quel champ texte ne casse rien
- [ ] Un upload de fichier non-image (renommé .jpg) est rejeté
- [ ] Un upload de fichier > 5MB est rejeté

### Crédibilité
- [ ] Impossible de laisser un avis sur son propre programme
- [ ] Impossible de laisser un avis sans conversation préalable
- [ ] Impossible de laisser 2 avis sur le même programme
- [ ] Impossible de se recommander soi-même
- [ ] Impossible de recommander sans conversation préalable

### Chat
- [ ] Impossible de rejoindre une conversation dont on n'est pas membre
- [ ] Un message ne peut pas être envoyé à un utilisateur receiveMessages=false
- [ ] La connexion WebSocket échoue sans token valide

### Infrastructure
- [ ] HTTPS est forcé en production (redirection HTTP → HTTPS)
- [ ] Les variables sensibles (DB, JWT secret, clés API) ne sont pas en dur dans le code
- [ ] Les logs ne contiennent jamais de mot de passe ni de token complet
- [ ] La stack trace n'est jamais exposée dans une réponse API en production
```

---

## Ordre d'exécution recommandé pour Claude Code

```
1. Configurer AbstractIntegrationTest + Testcontainers (PostGIS + pgvector)
2. Écrire et faire passer les tests unitaires Module 1 (rapides, sans Spring context)
   → AuthServiceTest, UserServiceTest, ProgramServiceTest,
     ChatServiceTest, ReviewServiceTest, PeerRecommendationServiceTest, BadgeServiceTest
3. Écrire et faire passer les tests d'intégration Module 2
   → AuthFlowIntegrationTest, MapVisibilityIntegrationTest (PRIORITÉ HAUTE),
     ChatFlowIntegrationTest, SecurityInjectionIntegrationTest,
     WebSocketChatIntegrationTest, SemanticSearchIntegrationTest
4. Tests Module 3 (Phase 4 spécifique)
   → GdprServiceIntegrationTest, RateLimiterServiceTest
5. Exécuter la checklist manuelle Module 4 et documenter les résultats
6. Corriger tout bug détecté — ne JAMAIS adapter un test pour le faire
   passer artificiellement
7. Générer un rapport de couverture (jacoco) et viser :
   - 80%+ sur les services (logique métier)
   - 100% sur les règles de visibilité, crédibilité et sécurité
```

---

## Commande de lancement

```bash
# Tests unitaires uniquement (rapide)
mvn test -Dtest=*ServiceTest

# Tests d'intégration (nécessite Docker pour Testcontainers)
mvn verify -Dtest=*IntegrationTest

# Tout avec rapport de couverture
mvn clean verify jacoco:report
# Rapport disponible dans target/site/jacoco/index.html
```

