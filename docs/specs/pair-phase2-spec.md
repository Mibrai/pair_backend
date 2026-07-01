# Pair — Phase 2 : Recherche intelligente & Richesse des activités
## Spécification d'implémentation pour Claude Code

> **Prérequis** : Phase 1 complète et fonctionnelle.
>
> **Objectif** : remplacer les filtres basiques par une recherche en langage naturel,
> enrichir les programmes avec médias et progressions, et sécuriser tous les
> champs de contenu enrichi.

---

## Nouvelles dépendances Maven

```xml
<!-- Client HTTP pour appels API LLM -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- OWASP HTML Sanitizer (XSS) -->
<dependency>
  <groupId>com.googlecode.owasp-java-html-sanitizer</groupId>
  <artifactId>owasp-java-html-sanitizer</artifactId>
  <version>20220608.1</version>
</dependency>

<!-- Apache Tika (validation type MIME fichiers uploadés) -->
<dependency>
  <groupId>org.apache.tika</groupId>
  <artifactId>tika-core</artifactId>
  <version>2.9.1</version>
</dependency>

<!-- AWS S3 (stockage médias) -->
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>s3</artifactId>
  <version>2.25.0</version>
</dependency>
```

---

## Module 1 — Recherche en langage naturel (chatbot)

### Architecture du pipeline de recherche

```
Phrase utilisateur
       ↓
[1] LlmIntentExtractor     → intent structuré (activité, rayon, format, niveau, quand)
       ↓
[2] EmbeddingService       → vecteur float[1536] de la requête
       ↓
[3] SemanticSearchService  → requête pgvector + PostGIS sur programs + users
       ↓
[4] SearchResultRanker     → trier par pertinence + distance + activité récente
       ↓
[5] SearchResponseBuilder  → construire la réponse (résultats ou relance)
```

### DTO SearchRequest / SearchResponse

```java
// Requête chatbot
public record SearchRequest(
    @NotBlank @Size(max = 500) String query,
    @NotNull Double lat,
    @NotNull Double lng,
    Integer radiusMeters   // override du rayon détecté par le LLM
) {}

// Intent extrait par le LLM
public record SearchIntent(
    String activityKeyword,  // "yoga", "escalade", "photographie"...
    String categoryHint,     // "Sport", "Arts"...
    String level,            // BEGINNER | INTERMEDIATE | ADVANCED | null
    String format,           // SOLO | DUO | GROUP | null
    Integer suggestedRadius, // en mètres, détecté dans la phrase
    String timeHint,         // "week-end", "matin", "soir"...
    boolean needsClarification,
    String clarificationQuestion // si trop vague
) {}

// Résultat d'une recherche
public record SearchResponse(
    String type,                         // "results" | "clarification" | "empty"
    List<SearchResultDto> results,
    String clarificationQuestion,        // si type == "clarification"
    List<String> suggestedAlternatives,  // si type == "empty"
    SearchIntent parsedIntent            // pour debug / affichage client
) {}

// Un résultat individuel
public record SearchResultDto(
    String resultType,       // "user" | "program"
    UUID id,
    String title,
    String description,
    String avatarUrl,
    Double lat,
    Double lng,
    Double distanceMeters,
    Float relevanceScore,
    String activityName,
    String level,
    String format,
    boolean isOnline,
    String verificationStatus
) {}
```

### LlmIntentExtractor.java

```java
@Service
@RequiredArgsConstructor
public class LlmIntentExtractor {

    @Value("${llm.api-url:https://api.anthropic.com/v1/messages}")
    private String llmApiUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.model:claude-sonnet-4-6}")
    private String model;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public SearchIntent extractIntent(String userQuery) {
        String systemPrompt = """
            Tu es un assistant d'extraction d'intention pour un réseau social d'activités.
            Analyse la phrase de l'utilisateur et retourne UNIQUEMENT un JSON valide,
            sans texte avant ou après, avec exactement ces champs :
            {
              "activityKeyword": "string ou null",
              "categoryHint": "string ou null",
              "level": "BEGINNER|INTERMEDIATE|ADVANCED|ANY ou null",
              "format": "SOLO|DUO|GROUP|ANY ou null",
              "suggestedRadius": nombre en mètres (défaut 5000),
              "timeHint": "string ou null",
              "needsClarification": boolean,
              "clarificationQuestion": "string ou null"
            }
            Exemples :
            - "je veux faire du sport" → needsClarification: true,
              clarificationQuestion: "Quel type de sport vous intéresse ?"
            - "cherche partenaire escalade débutant" → activityKeyword: "escalade",
              level: "BEGINNER", needsClarification: false
            - "qq1 pour courir dimanche matin près de chez moi" → activityKeyword: "course",
              timeHint: "dimanche matin", format: "DUO", needsClarification: false
            """;

        Map<String, Object> requestBody = Map.of(
            "model", model,
            "max_tokens", 300,
            "system", systemPrompt,
            "messages", List.of(Map.of("role", "user", "content", userQuery))
        );

        try {
            String response = webClient.post()
                .uri(llmApiUrl)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));

            // Extraire le contenu texte de la réponse Anthropic
            JsonNode root = objectMapper.readTree(response);
            String json = root.path("content").get(0).path("text").asText();
            return objectMapper.readValue(json, SearchIntent.class);

        } catch (Exception e) {
            log.warn("LLM intent extraction échoué, fallback sur recherche textuelle : {}",
                e.getMessage());
            // Fallback : utiliser la phrase brute comme mot-clé
            return new SearchIntent(userQuery, null, null, null,
                5000, null, false, null);
        }
    }
}
```

### EmbeddingService.java

```java
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    @Value("${llm.api-key}")
    private String apiKey;

    // Anthropic ne propose pas encore d'endpoint embeddings natif au moment de la spec —
    // utiliser OpenAI text-embedding-3-small (1536 dims) ou Cohere
    @Value("${embedding.api-url:https://api.openai.com/v1/embeddings}")
    private String embeddingApiUrl;

    @Value("${embedding.api-key}")
    private String embeddingApiKey;

    @Value("${embedding.model:text-embedding-3-small}")
    private String embeddingModel;

    private final WebClient webClient;

    public float[] generateEmbedding(String text) {
        // Tronquer à 8000 caractères max
        String truncated = text.length() > 8000 ? text.substring(0, 8000) : text;

        Map<String, Object> body = Map.of(
            "model", embeddingModel,
            "input", truncated
        );

        try {
            String response = webClient.post()
                .uri(embeddingApiUrl)
                .header("Authorization", "Bearer " + embeddingApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(15));

            JsonNode root = new ObjectMapper().readTree(response);
            JsonNode embeddingNode = root.path("data").get(0).path("embedding");

            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }
            return embedding;

        } catch (Exception e) {
            log.error("Génération embedding échouée : {}", e.getMessage());
            return new float[1536]; // Vecteur nul en cas d'erreur
        }
    }

    // Convertir float[] en format pgvector "[0.1, 0.2, ...]"
    public String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
```

### SemanticSearchService.java

```java
@Service
@RequiredArgsConstructor
public class SemanticSearchService {

    private final LlmIntentExtractor intentExtractor;
    private final EmbeddingService embeddingService;
    private final ProgramRepository programRepository;
    private final UserRepository userRepository;
    private final SearchLogRepository searchLogRepository;

    @Transactional
    public SearchResponse search(SearchRequest request, UUID userId) {

        // 1. Logger la recherche brute
        SearchLog log = new SearchLog();
        log.setUser(userRepository.getReferenceById(userId));
        log.setRawQuery(request.query());

        // 2. Extraire l'intention
        SearchIntent intent = intentExtractor.extractIntent(request.query());
        log.setParsedIntent(intent.toString());

        // 3. Si clarification nécessaire → répondre immédiatement
        if (intent.needsClarification()) {
            searchLogRepository.save(log);
            return new SearchResponse("clarification", List.of(),
                intent.clarificationQuestion(), List.of(), intent);
        }

        // 4. Générer l'embedding de la requête
        float[] queryEmbedding = embeddingService.generateEmbedding(request.query());
        log.setQueryEmbedding(queryEmbedding);

        // 5. Déterminer le rayon
        int radius = request.radiusMeters() != null
            ? request.radiusMeters()
            : (intent.suggestedRadius() != null ? intent.suggestedRadius() : 5000);

        // 6. Recherche sémantique dans la base
        String vectorStr = embeddingService.toVectorString(queryEmbedding);
        List<Program> programs = programRepository.semanticSearchInRadius(
            vectorStr, request.lat(), request.lng(), radius, 20);

        log.setResultsCount(programs.size());
        searchLogRepository.save(log);

        // 7. Si aucun résultat → suggestions alternatives
        if (programs.isEmpty()) {
            return new SearchResponse("empty", List.of(), null,
                buildAlternativeSuggestions(intent, request), intent);
        }

        // 8. Construire les résultats
        List<SearchResultDto> results = programs.stream()
            .map(p -> toResultDto(p, request.lat(), request.lng()))
            .toList();

        return new SearchResponse("results", results, null, List.of(), intent);
    }

    private List<String> buildAlternativeSuggestions(SearchIntent intent,
                                                       SearchRequest request) {
        // Proposer : élargir le rayon, activité similaire, créer un créneau
        return List.of(
            "Élargir la zone de recherche à " + (request.radiusMeters() * 2 / 1000) + " km",
            "Être le premier à proposer cette activité ici",
            "Recevoir une alerte quand quelqu'un arrive"
        );
    }

    private SearchResultDto toResultDto(Program p, double lat, double lng) {
        User owner = p.getUserActivity().getUser();
        Point ownerLoc = owner.getLocation();
        double distanceMeters = 0;
        if (ownerLoc != null) {
            distanceMeters = calculateDistance(lat, lng,
                ownerLoc.getY(), ownerLoc.getX());
        }
        return new SearchResultDto(
            "program", p.getId(), p.getTitle(),
            p.getDescription() != null
                ? p.getDescription().substring(0, Math.min(200, p.getDescription().length()))
                : null,
            owner.getAvatarUrl(),
            ownerLoc != null ? ownerLoc.getY() : null,
            ownerLoc != null ? ownerLoc.getX() : null,
            distanceMeters, 0f,
            p.getUserActivity().getActivity().getName(),
            p.getUserActivity().getLevel().name(),
            p.getUserActivity().getFormat().name(),
            owner.getLastActiveAt() != null
                && owner.getLastActiveAt().isAfter(Instant.now().minusSeconds(300)),
            owner.getVerificationStatus().name()
        );
    }

    private double calculateDistance(double lat1, double lng1,
                                      double lat2, double lng2) {
        final int R = 6371000; // mètres
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng/2) * Math.sin(dLng/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }
}
```

### SearchController.java

```java
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SemanticSearchService searchService;

    // POST /api/search
    // Corps : { "query": "je cherche quelqu'un pour faire du yoga le matin",
    //           "lat": 48.8566, "lng": 2.3522, "radiusMeters": 5000 }
    @PostMapping
    public SearchResponse search(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody SearchRequest request) {
        return searchService.search(request, principal.getId());
    }
}
```

---

## Module 2 — Progressions

### DTOs Progression

```java
// Créer une entrée de progression
public record CreateProgressionRequest(
    @NotNull UUID programId,
    @Size(max = 150) String title,
    @Size(max = 2000) String content,
    float[] metrics,    // ex: [5.2, 32.0] pour [distance_km, duration_min]
    String[] metricLabels, // ex: ["Distance (km)", "Durée (min)"]
    Boolean isPublic
) {}

// Entrée de progression complète
public record ProgressionEntryDto(
    UUID id,
    UUID programId,
    String programTitle,
    String title,
    String content,
    float[] metrics,
    String[] metricLabels,
    Boolean isPublic,
    Instant createdAt
) {}

// Résumé de progression (pour affichage programme)
public record ProgressionSummaryDto(
    int totalEntries,
    Instant firstEntryAt,
    Instant lastEntryAt,
    int currentStreak,      // jours consécutifs
    List<ProgressionEntryDto> recentEntries
) {}
```

### ProgressionController.java

```java
@RestController
@RequestMapping("/api/progressions")
@RequiredArgsConstructor
public class ProgressionController {

    private final ProgressionService progressionService;

    // GET /api/progressions?programId=&page=&size=
    @GetMapping
    public Page<ProgressionEntryDto> getProgressions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam UUID programId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return progressionService.getProgressions(
            principal.getId(), programId,
            PageRequest.of(page, Math.min(size, 50)));
    }

    // GET /api/progressions/summary?programId=
    @GetMapping("/summary")
    public ProgressionSummaryDto getSummary(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam UUID programId) {
        return progressionService.getSummary(principal.getId(), programId);
    }

    // POST /api/progressions
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProgressionEntryDto create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateProgressionRequest request) {
        return progressionService.create(principal.getId(), request);
    }

    // PUT /api/progressions/{id}
    @PutMapping("/{id}")
    public ProgressionEntryDto update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProgressionRequest request) {
        return progressionService.update(principal.getId(), id, request);
    }

    // PATCH /api/progressions/{id}/visibility
    @PatchMapping("/{id}/visibility")
    public ProgressionEntryDto toggleVisibility(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody VisibilityRequest request) {
        return progressionService.toggleVisibility(principal.getId(), id, request.visible());
    }

    // DELETE /api/progressions/{id}
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        progressionService.delete(principal.getId(), id);
    }
}
```

---

## Module 3 — Médias (upload S3 sécurisé)

### StorageService.java

```java
@Service
@RequiredArgsConstructor
public class StorageService {

    @Value("${aws.s3.bucket}") private String bucket;
    @Value("${aws.s3.region}") private String region;
    @Value("${aws.s3.cdn-base-url}") private String cdnBaseUrl;

    private final S3Client s3Client;
    private final Tika tika = new Tika(); // Apache Tika

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
        "image/jpeg", "image/png", "image/webp", "image/gif"
    );
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    public String uploadAndReencode(MultipartFile file, String keyPrefix) {
        // 1. Valider la taille
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidFileException("Fichier trop volumineux (max 5 MB).");
        }

        // 2. Détecter le MIME réel via magic bytes (pas le Content-Type déclaré)
        String detectedMime;
        try {
            detectedMime = tika.detect(file.getInputStream());
        } catch (IOException e) {
            throw new InvalidFileException("Impossible de lire le fichier.");
        }

        if (!ALLOWED_MIME_TYPES.contains(detectedMime)) {
            throw new InvalidFileException(
                "Type de fichier non autorisé : " + detectedMime);
        }

        // 3. Ré-encoder l'image (retire métadonnées EXIF et strips malicious content)
        //    Utiliser ImageIO ou Thumbnailator
        byte[] reencodedBytes = reencodeImage(file, detectedMime);

        // 4. Générer un nom de fichier unique
        String extension = detectedMime.equals("image/gif") ? "gif" : "jpg";
        String key = keyPrefix + "/" + UUID.randomUUID() + "." + extension;

        // 5. Upload vers S3
        PutObjectRequest putRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType("image/jpeg")
            .build();

        s3Client.putObject(putRequest,
            RequestBody.fromBytes(reencodedBytes));

        return cdnBaseUrl + "/" + key;
    }

    private byte[] reencodeImage(MultipartFile file, String mimeType) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // Utiliser Thumbnailator pour ré-encoder proprement
            // net.coobird:thumbnailator:0.4.20
            Thumbnails.of(file.getInputStream())
                .size(1920, 1920) // Max 1920px
                .keepAspectRatio(true)
                .outputFormat("jpg")
                .outputQuality(0.85)
                .toOutputStream(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new InvalidFileException("Erreur lors du ré-encodage de l'image.");
        }
    }
}
```

### ProgramMediaController.java

```java
@RestController
@RequestMapping("/api/programs/{programId}/media")
@RequiredArgsConstructor
public class ProgramMediaController {

    private final ProgramMediaService mediaService;

    // GET /api/programs/{programId}/media
    @GetMapping
    public List<ProgramMediaDto> getMedia(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId) {
        return mediaService.getMedia(programId, principal.getId());
    }

    // POST /api/programs/{programId}/media
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProgramMediaDto uploadMedia(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "IMAGE") ProgramMedia.MediaType mediaType) {
        return mediaService.uploadMedia(principal.getId(), programId, file, mediaType);
    }

    // PATCH /api/programs/{programId}/media/reorder
    @PatchMapping("/reorder")
    public List<ProgramMediaDto> reorder(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @RequestBody ReorderRequest request) {
        return mediaService.reorder(principal.getId(), programId, request.orderedIds());
    }

    // DELETE /api/programs/{programId}/media/{mediaId}
    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMedia(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @PathVariable UUID mediaId) {
        mediaService.deleteMedia(principal.getId(), programId, mediaId);
    }
}
```

---

## Module 4 — Indexation embedding à la création/modification

### EmbeddingIndexingListener.java (JPA Event Listener)

```java
@Component
@RequiredArgsConstructor
public class EmbeddingIndexingListener {

    private final EmbeddingService embeddingService;
    private final ProgramRepository programRepository;
    private final ActivityRepository activityRepository;

    // Appelé lors de la création ou modification d'un Programme
    @Async
    @EventListener
    public void onProgramSaved(ProgramSavedEvent event) {
        Program program = event.getProgram();
        if (program.getStatus() == ProgramStatus.ACTIVE
                && Boolean.TRUE.equals(program.getIsPublic())) {
            String textToEmbed = buildProgramText(program);
            float[] embedding = embeddingService.generateEmbedding(textToEmbed);
            programRepository.updateEmbedding(program.getId(), embedding);
        }
    }

    // Appelé lors de la création d'une Activity
    @Async
    @EventListener
    public void onActivitySaved(ActivitySavedEvent event) {
        Activity activity = event.getActivity();
        String text = activity.getName() + " " + activity.getDescription();
        float[] embedding = embeddingService.generateEmbedding(text);
        activityRepository.updateEmbedding(activity.getId(), embedding);
    }

    private String buildProgramText(Program program) {
        StringBuilder sb = new StringBuilder();
        sb.append(program.getTitle()).append(". ");
        if (program.getDescription() != null) sb.append(program.getDescription()).append(". ");
        sb.append(program.getUserActivity().getActivity().getName()).append(". ");
        if (program.getUserActivity().getCustomDescription() != null) {
            sb.append(program.getUserActivity().getCustomDescription());
        }
        return sb.toString();
    }
}
```

---

## Récapitulatif des endpoints Phase 2

### Recherche sémantique
| Méthode | Route | Description |
|---------|-------|-------------|
| POST | /api/search | Recherche en langage naturel |

### Progressions
| Méthode | Route | Description |
|---------|-------|-------------|
| GET    | /api/progressions | Liste des entrées |
| GET    | /api/progressions/summary | Résumé + streak |
| POST   | /api/progressions | Créer une entrée |
| PUT    | /api/progressions/{id} | Modifier |
| PATCH  | /api/progressions/{id}/visibility | Toggle visibilité |
| DELETE | /api/progressions/{id} | Supprimer |

### Médias
| Méthode | Route | Description |
|---------|-------|-------------|
| GET    | /api/programs/{id}/media | Lister les médias |
| POST   | /api/programs/{id}/media | Upload un média |
| PATCH  | /api/programs/{id}/media/reorder | Réordonner |
| DELETE | /api/programs/{id}/media/{mid} | Supprimer |

