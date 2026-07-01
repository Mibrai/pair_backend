# Pair — Étape 4 : Données initiales (Seeds)
## Spécification d'implémentation pour Claude Code

> **Prérequis** : data-model + phases 1 à 4 implémentées et testées (étape 1 validée).
>
> **Objectif** : peupler la base avec les données de référence (catégories,
> activités, badges) sans lesquelles l'application est inutilisable, et
> fournir des comptes de démonstration réalistes pour tester la carte,
> la recherche sémantique et le chat sans attendre de vrais utilisateurs.
>
> **Règle d'or** : les seeds de référence (catégories/activités/badges)
> doivent pouvoir tourner en production sans danger (idempotents). Les
> comptes de démonstration ne doivent JAMAIS s'exécuter en production.

---

## Architecture des seeds

```
src/main/java/com/pair/
├── seed/
│   ├── SeedRunner.java                 ← orchestrateur, profil-aware
│   ├── ReferenceDataSeeder.java        ← catégories + activités + badges (TOUS environnements)
│   ├── DemoDataSeeder.java             ← comptes + activités + programmes fictifs (DEV/STAGING uniquement)
│   └── data/
│       ├── categories.json
│       ├── activities.json
│       └── badges.json
└── resources/
    └── db/migration/
        └── V20__seed_reference_data.sql   ← alternative SQL pure (voir Option B)
```

Deux approches possibles, je détaille les deux — choisis celle qui correspond à ton style :

- **Option A (recommandée)** : seeders Java avec `CommandLineRunner`, conditionnés par profil Spring. Plus flexible, permet de générer les embeddings à la volée via l'API.
- **Option B** : migration Flyway SQL pure pour les données de référence. Plus simple, mais ne génère pas les embeddings (à faire en job séparé après).

Je documente l'**Option A** en détail, avec l'option B en alternative pour les données de référence uniquement.

---

## Étape 1 — Données de référence (catégories + activités)

### Principe d'idempotence

Chaque seeder doit pouvoir tourner plusieurs fois sans dupliquer ni écraser les données existantes. Toujours vérifier l'existence par `slug` ou `code` avant insertion.

### data/categories.json

```json
[
  { "code": "sport",        "name": "Sport",              "icon": "dumbbell",        "colorRamp": "#EF4444" },
  { "code": "arts",         "name": "Arts & Création",    "icon": "palette",         "colorRamp": "#8B5CF6" },
  { "code": "jeux",         "name": "Jeux",                "icon": "dice-5",          "colorRamp": "#F59E0B" },
  { "code": "cuisine",      "name": "Cuisine",             "icon": "chef-hat",        "colorRamp": "#F97316" },
  { "code": "apprentissage","name": "Apprentissage",       "icon": "book-open",       "colorRamp": "#3B82F6" },
  { "code": "plein_air",    "name": "Plein air",           "icon": "mountain",        "colorRamp": "#10B981" },
  { "code": "musique",      "name": "Musique",             "icon": "music",           "colorRamp": "#EC4899" },
  { "code": "benevolat",    "name": "Bénévolat",           "icon": "heart-handshake", "colorRamp": "#06B6D4" },
  { "code": "bien_etre",    "name": "Bien-être",           "icon": "sparkles",        "colorRamp": "#A855F7" },
  { "code": "tech",         "name": "Tech & Numérique",    "icon": "cpu",             "colorRamp": "#6366F1" }
]
```

### data/activities.json

> Structure hiérarchique : `parentSlug: null` pour les activités racines.
> Une cinquantaine d'activités de départ réparties sur les 10 catégories,
> volontairement large pour couvrir le "toutes passions" dès le lancement.

```json
[
  { "slug": "course-a-pied",       "name": "Course à pied",         "categoryCode": "sport", "parentSlug": null,
    "description": "Courir seul ou accompagné, sur route ou en sentier." },
  { "slug": "trail",                "name": "Trail",                  "categoryCode": "sport", "parentSlug": "course-a-pied",
    "description": "Course à pied en milieu naturel et terrain accidenté." },
  { "slug": "musculation",          "name": "Musculation",            "categoryCode": "sport", "parentSlug": null,
    "description": "Entraînement en salle ou à domicile pour la force et le volume musculaire." },
  { "slug": "escalade",             "name": "Escalade",               "categoryCode": "sport", "parentSlug": null,
    "description": "Escalade en salle ou en extérieur, bloc ou voie." },
  { "slug": "football",             "name": "Football",               "categoryCode": "sport", "parentSlug": null,
    "description": "Football en salle, à 5, à 7 ou à 11." },
  { "slug": "tennis",                "name": "Tennis",                  "categoryCode": "sport", "parentSlug": null,
    "description": "Tennis en simple ou en double, tous niveaux." },
  { "slug": "natation",             "name": "Natation",               "categoryCode": "sport", "parentSlug": null,
    "description": "Natation en piscine ou en eau libre." },
  { "slug": "velo",                  "name": "Vélo",                    "categoryCode": "sport", "parentSlug": null,
    "description": "Vélo route, VTT ou cyclotourisme." },
  { "slug": "vtt",                   "name": "VTT",                     "categoryCode": "sport", "parentSlug": "velo",
    "description": "Vélo tout-terrain sur sentiers et chemins." },
  { "slug": "yoga",                  "name": "Yoga",                    "categoryCode": "bien_etre", "parentSlug": null,
    "description": "Pratique du yoga sous toutes ses formes, tous niveaux." },
  { "slug": "meditation",            "name": "Méditation",              "categoryCode": "bien_etre", "parentSlug": null,
    "description": "Pratique de la méditation et de la pleine conscience." },
  { "slug": "danse",                 "name": "Danse",                   "categoryCode": "arts", "parentSlug": null,
    "description": "Danse de salon, hip-hop, contemporaine, classique..." },
  { "slug": "peinture",              "name": "Peinture",                "categoryCode": "arts", "parentSlug": null,
    "description": "Peinture à l'huile, aquarelle, acrylique..." },
  { "slug": "photographie",          "name": "Photographie",            "categoryCode": "arts", "parentSlug": null,
    "description": "Photographie argentique ou numérique, portrait, paysage, street." },
  { "slug": "ceramique",             "name": "Céramique & poterie",     "categoryCode": "arts", "parentSlug": null,
    "description": "Travail de l'argile, tournage, modelage." },
  { "slug": "ecriture",              "name": "Écriture créative",       "categoryCode": "arts", "parentSlug": null,
    "description": "Écriture de fiction, poésie, ateliers d'écriture." },
  { "slug": "echecs",                "name": "Échecs",                  "categoryCode": "jeux", "parentSlug": null,
    "description": "Échecs en club, en ligne ou en partie libre." },
  { "slug": "jeux-de-societe",       "name": "Jeux de société",         "categoryCode": "jeux", "parentSlug": null,
    "description": "Jeux de plateau modernes et classiques en groupe." },
  { "slug": "jeux-de-role",          "name": "Jeux de rôle",            "categoryCode": "jeux", "parentSlug": null,
    "description": "JDR sur table type Donjons & Dragons et univers variés." },
  { "slug": "jeux-video",            "name": "Jeux vidéo",              "categoryCode": "jeux", "parentSlug": null,
    "description": "Gaming en coopératif, compétitif ou découverte." },
  { "slug": "patisserie",            "name": "Pâtisserie",              "categoryCode": "cuisine", "parentSlug": null,
    "description": "Pâtisserie maison, viennoiserie, decoration de gâteaux." },
  { "slug": "cuisine-du-monde",      "name": "Cuisine du monde",        "categoryCode": "cuisine", "parentSlug": null,
    "description": "Découverte et pratique de cuisines internationales." },
  { "slug": "oenologie",             "name": "Œnologie",                "categoryCode": "cuisine", "parentSlug": null,
    "description": "Dégustation et découverte du vin." },
  { "slug": "langues",               "name": "Langues étrangères",      "categoryCode": "apprentissage", "parentSlug": null,
    "description": "Pratique conversationnelle de langues étrangères en tandem." },
  { "slug": "lecture",               "name": "Lecture & clubs de lecture", "categoryCode": "apprentissage", "parentSlug": null,
    "description": "Clubs de lecture et échanges littéraires." },
  { "slug": "programmation",         "name": "Programmation",           "categoryCode": "tech", "parentSlug": null,
    "description": "Développement logiciel, projets personnels, hackathons." },
  { "slug": "robotique",             "name": "Robotique & électronique","categoryCode": "tech", "parentSlug": null,
    "description": "Construction et programmation de robots, électronique DIY." },
  { "slug": "randonnee",             "name": "Randonnée",               "categoryCode": "plein_air", "parentSlug": null,
    "description": "Randonnée pédestre, day-hike ou plusieurs jours." },
  { "slug": "camping",               "name": "Camping & bivouac",       "categoryCode": "plein_air", "parentSlug": null,
    "description": "Camping sauvage, bivouac, vie en extérieur." },
  { "slug": "jardinage",             "name": "Jardinage",               "categoryCode": "plein_air", "parentSlug": null,
    "description": "Jardinage, permaculture, potager partagé." },
  { "slug": "peche",                 "name": "Pêche",                   "categoryCode": "plein_air", "parentSlug": null,
    "description": "Pêche en eau douce ou en mer." },
  { "slug": "guitare",               "name": "Guitare",                 "categoryCode": "musique", "parentSlug": null,
    "description": "Pratique de la guitare, acoustique ou électrique." },
  { "slug": "piano",                 "name": "Piano",                   "categoryCode": "musique", "parentSlug": null,
    "description": "Pratique du piano, classique ou moderne." },
  { "slug": "chant",                  "name": "Chant",                   "categoryCode": "musique", "parentSlug": null,
    "description": "Chant en solo, en groupe ou en chorale." },
  { "slug": "jam-session",           "name": "Jam session",             "categoryCode": "musique", "parentSlug": null,
    "description": "Sessions de musique improvisée entre musiciens." },
  { "slug": "benevolat-environnement","name": "Bénévolat environnemental", "categoryCode": "benevolat", "parentSlug": null,
    "description": "Actions de nettoyage, reforestation, sensibilisation." },
  { "slug": "benevolat-social",      "name": "Bénévolat social",        "categoryCode": "benevolat", "parentSlug": null,
    "description": "Aide alimentaire, accompagnement, maraudes." },
  { "slug": "mentorat",              "name": "Mentorat",                "categoryCode": "benevolat", "parentSlug": null,
    "description": "Accompagnement et transmission de compétences." }
]
```

### data/badges.json

```json
[
  { "code": "VERIFIED_EMAIL",          "category": "TRUST",       "label": "Email vérifié",
    "conditionType": "VERIFICATION", "conditionThreshold": null, "icon": "mail-check" },
  { "code": "VERIFIED_PHONE",          "category": "TRUST",       "label": "Téléphone vérifié",
    "conditionType": "VERIFICATION", "conditionThreshold": null, "icon": "phone-check" },
  { "code": "VERIFIED_ID",             "category": "TRUST",       "label": "Identité vérifiée",
    "conditionType": "VERIFICATION", "conditionThreshold": null, "icon": "shield-check" },
  { "code": "FIRST_RECOMMENDATION",    "category": "TRUST",       "label": "Première recommandation",
    "conditionType": "RECOMMENDATION_COUNT", "conditionThreshold": 1, "icon": "thumbs-up" },
  { "code": "TRUSTED_5",               "category": "TRUST",       "label": "Confiance établie",
    "conditionType": "RECOMMENDATION_COUNT", "conditionThreshold": 5, "icon": "users" },
  { "code": "TRUSTED_20",              "category": "TRUST",       "label": "Pilier de confiance",
    "conditionType": "RECOMMENDATION_COUNT", "conditionThreshold": 20, "icon": "shield" },
  { "code": "FIRST_PROGRAM",           "category": "ACHIEVEMENT", "label": "Premier programme créé",
    "conditionType": "PROGRAM_COUNT", "conditionThreshold": 1, "icon": "flag" },
  { "code": "ACTIVE_ORGANIZER",        "category": "ACHIEVEMENT", "label": "Organisateur actif",
    "conditionType": "PROGRAM_COUNT", "conditionThreshold": 5, "icon": "calendar-check" },
  { "code": "STREAK_7",                "category": "ACHIEVEMENT", "label": "Une semaine de suite",
    "conditionType": "PROGRESSION_STREAK", "conditionThreshold": 7, "icon": "flame" },
  { "code": "STREAK_30",               "category": "ACHIEVEMENT", "label": "Un mois de régularité",
    "conditionType": "PROGRESSION_STREAK", "conditionThreshold": 30, "icon": "flame" },
  { "code": "STREAK_100",              "category": "ACHIEVEMENT", "label": "100 jours de constance",
    "conditionType": "PROGRESSION_STREAK", "conditionThreshold": 100, "icon": "trophy" },
  { "code": "MULTI_PASSION_3",         "category": "ACHIEVEMENT", "label": "Esprit curieux",
    "conditionType": "ACTIVITY_DIVERSITY", "conditionThreshold": 3, "icon": "compass" },
  { "code": "MULTI_PASSION_5",         "category": "ACHIEVEMENT", "label": "Touche-à-tout",
    "conditionType": "ACTIVITY_DIVERSITY", "conditionThreshold": 5, "icon": "compass" },
  { "code": "REGULAR_ORGANIZER",       "category": "ROLE",        "label": "Organise régulièrement",
    "conditionType": "PROGRAM_COUNT", "conditionThreshold": 3, "icon": "star" }
]
```

---

## Étape 2 — ReferenceDataSeeder.java (Option A — Java)

```java
@Component
@Order(1) // s'exécute avant DemoDataSeeder
@RequiredArgsConstructor
@Slf4j
public class ReferenceDataSeeder implements CommandLineRunner {

    private final CategoryRepository categoryRepository;
    private final ActivityRepository activityRepository;
    private final BadgeRepository badgeRepository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Démarrage du seed des données de référence ===");
        seedCategories();
        seedActivities();
        seedBadges();
        log.info("=== Seed des données de référence terminé ===");
    }

    private void seedCategories() throws IOException {
        List<CategorySeed> seeds = loadJson("seed/data/categories.json", CategorySeed[].class);
        int created = 0, skipped = 0;

        for (CategorySeed seed : seeds) {
            if (categoryRepository.existsByName(seed.name())) {
                skipped++;
                continue;
            }
            Category category = new Category();
            category.setName(seed.name());
            category.setIcon(seed.icon());
            category.setColorRamp(seed.colorRamp());
            categoryRepository.save(category);
            created++;
        }
        log.info("Catégories : {} créées, {} déjà présentes (ignorées)", created, skipped);
    }

    private void seedActivities() throws IOException {
        List<ActivitySeed> seeds = loadJson("seed/data/activities.json", ActivitySeed[].class);
        Map<String, Category> categoriesByCode = buildCategoryCodeMap();
        int created = 0, skipped = 0;

        // Premier passage : créer toutes les activités sans parent
        // Deuxième passage : résoudre les parentSlug
        Map<String, Activity> activitiesBySlug = new HashMap<>();

        for (ActivitySeed seed : seeds) {
            if (activityRepository.existsBySlug(seed.slug())) {
                skipped++;
                activitiesBySlug.put(seed.slug(),
                    activityRepository.findBySlug(seed.slug()).orElseThrow());
                continue;
            }
            Activity activity = new Activity();
            activity.setName(seed.name());
            activity.setSlug(seed.slug());
            activity.setDescription(seed.description());
            activity.setCategory(categoriesByCode.get(seed.categoryCode()));
            // parent résolu au 2e passage
            Activity saved = activityRepository.save(activity);
            activitiesBySlug.put(seed.slug(), saved);
            created++;
        }

        // Résoudre les hiérarchies parent/enfant
        for (ActivitySeed seed : seeds) {
            if (seed.parentSlug() != null) {
                Activity child = activitiesBySlug.get(seed.slug());
                Activity parent = activitiesBySlug.get(seed.parentSlug());
                if (child != null && parent != null && child.getParent() == null) {
                    child.setParent(parent);
                    activityRepository.save(child);
                }
            }
        }

        log.info("Activités : {} créées, {} déjà présentes (ignorées)", created, skipped);

        // Générer les embeddings pour les nouvelles activités (asynchrone, ne bloque pas le démarrage)
        generateMissingEmbeddings();
    }

    @Async
    public void generateMissingEmbeddings() {
        List<Activity> withoutEmbedding = activityRepository.findByEmbeddingIsNull();
        log.info("Génération des embeddings pour {} activités", withoutEmbedding.size());

        for (Activity activity : withoutEmbedding) {
            try {
                String text = activity.getName() + ". " + activity.getDescription();
                float[] embedding = embeddingService.generateEmbedding(text);
                activityRepository.updateEmbedding(activity.getId(), embedding);
                // Throttle pour ne pas saturer l'API LLM/embeddings
                Thread.sleep(200);
            } catch (Exception e) {
                log.warn("Échec génération embedding pour activité {} : {}",
                    activity.getSlug(), e.getMessage());
            }
        }
        log.info("Génération des embeddings terminée");
    }

    private void seedBadges() throws IOException {
        List<BadgeSeed> seeds = loadJson("seed/data/badges.json", BadgeSeed[].class);
        int created = 0, skipped = 0;

        for (BadgeSeed seed : seeds) {
            if (badgeRepository.existsByCode(seed.code())) {
                skipped++;
                continue;
            }
            Badge badge = new Badge();
            badge.setCode(seed.code());
            badge.setCategory(BadgeCategory.valueOf(seed.category()));
            badge.setLabel(seed.label());
            badge.setConditionType(BadgeConditionType.valueOf(seed.conditionType()));
            badge.setConditionThreshold(seed.conditionThreshold());
            badge.setIcon(seed.icon());
            badgeRepository.save(badge);
            created++;
        }
        log.info("Badges : {} créés, {} déjà présents (ignorés)", created, skipped);
    }

    private Map<String, Category> buildCategoryCodeMap() {
        // Reconstruire la correspondance code → entité à partir du JSON catégories
        // (le code n'est pas stocké en base, seulement le name — mapper via name)
        try {
            List<CategorySeed> catSeeds = loadJson("seed/data/categories.json", CategorySeed[].class);
            Map<String, String> codeToName = catSeeds.stream()
                .collect(Collectors.toMap(CategorySeed::code, CategorySeed::name));
            List<Category> allCategories = categoryRepository.findAll();
            Map<String, Category> byName = allCategories.stream()
                .collect(Collectors.toMap(Category::getName, c -> c));
            Map<String, Category> result = new HashMap<>();
            codeToName.forEach((code, name) -> result.put(code, byName.get(name)));
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de charger les catégories", e);
        }
    }

    private <T> T loadJson(String classpathLocation, Class<T> type) throws IOException {
        try (InputStream is = new ClassPathResource(classpathLocation).getInputStream()) {
            return objectMapper.readValue(is, type);
        }
    }

    // Records de désérialisation JSON
    record CategorySeed(String code, String name, String icon, String colorRamp) {}
    record ActivitySeed(String slug, String name, String categoryCode,
                        String parentSlug, String description) {}
    record BadgeSeed(String code, String category, String label,
                     String conditionType, Integer conditionThreshold, String icon) {}
}
```

### Repository — méthodes additionnelles nécessaires

```java
// CategoryRepository.java — ajouter
boolean existsByName(String name);

// ActivityRepository.java — ajouter
boolean existsBySlug(String slug);
Optional<Activity> findBySlug(String slug);
List<Activity> findByEmbeddingIsNull();

@Modifying
@Query(value = "UPDATE activities SET embedding = CAST(:embedding AS vector) WHERE id = :id",
       nativeQuery = true)
void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embeddingVectorString);

// BadgeRepository.java — ajouter
boolean existsByCode(String code);
```

---

## Étape 3 — Configuration par profil (sécurité du seed)

### application.properties (configuration commune)

```properties
# Activer/désactiver explicitement les seeders
pair.seed.reference-data.enabled=true
pair.seed.demo-data.enabled=false
```

### application-dev.properties

```properties
pair.seed.reference-data.enabled=true
pair.seed.demo-data.enabled=true
```

### application-staging.properties

```properties
pair.seed.reference-data.enabled=true
pair.seed.demo-data.enabled=true
```

### application-prod.properties

```properties
pair.seed.reference-data.enabled=true
# JAMAIS true en production — données fictives interdites
pair.seed.demo-data.enabled=false
```

### SeedRunner.java — garde-fou explicite

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SeedRunner implements CommandLineRunner {

    @Value("${pair.seed.reference-data.enabled:false}")
    private boolean referenceDataEnabled;

    @Value("${pair.seed.demo-data.enabled:false}")
    private boolean demoDataEnabled;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    private final ReferenceDataSeeder referenceDataSeeder;
    private final DemoDataSeeder demoDataSeeder;

    @Override
    public void run(String... args) throws Exception {
        if (referenceDataEnabled) {
            referenceDataSeeder.run(args);
        }

        if (demoDataEnabled) {
            // Garde-fou supplémentaire : refuser explicitement en profil prod
            // même si la config a été mal positionnée par erreur
            if (activeProfiles.contains("prod")) {
                throw new IllegalStateException(
                    "REFUS DE SÉCURITÉ : pair.seed.demo-data.enabled=true " +
                    "détecté en profil 'prod'. Les données de démonstration " +
                    "ne doivent jamais être créées en production.");
            }
            demoDataSeeder.run(args);
        }
    }
}
```

---

## Étape 4 — DemoDataSeeder.java (DEV / STAGING uniquement)

### Principe

Créer 15 à 20 utilisateurs fictifs avec des profils réalistes, répartis géographiquement autour d'une ville de test (Paris par défaut, configurable), avec des activités, programmes, créneaux et quelques progressions — pour pouvoir tester immédiatement la carte, la recherche sémantique et le chat.

### DemoDataSeeder.java

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final UserActivityRepository userActivityRepository;
    private final ProgramRepository programRepository;
    private final ScheduleRepository scheduleRepository;
    private final ActivityRepository activityRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmbeddingService embeddingService;
    private final GeometryFactory geometryFactory =
        new GeometryFactory(new PrecisionModel(), 4326);

    @Value("${pair.seed.demo-data.center-lat:48.8566}")
    private double centerLat;

    @Value("${pair.seed.demo-data.center-lng:2.3522}")
    private double centerLng;

    private final Random random = new Random(42); // seed fixe → reproductible

    @Override
    public void run(String... args) throws Exception {
        if (userRepository.existsByEmail("demo1@pair.app")) {
            log.info("Données de démonstration déjà présentes — seed ignoré.");
            return;
        }

        log.info("=== Création des comptes de démonstration ===");
        List<User> demoUsers = createDemoUsers();
        log.info("=== {} comptes de démonstration créés ===", demoUsers.size());
    }

    private List<User> createDemoUsers() {
        List<DemoProfile> profiles = buildDemoProfiles();
        List<User> created = new ArrayList<>();

        for (DemoProfile profile : profiles) {
            User user = new User();
            user.setEmail(profile.email());
            user.setPasswordHash(passwordEncoder.encode("Demo1234!"));
            user.setDisplayName(profile.displayName());
            user.setBio(profile.bio());
            user.setVerificationStatus(VerificationStatus.EMAIL_VERIFIED);
            user.setVerifiedAt(Instant.now());
            user.setLocationPublic(true);
            user.setOnlineStatusVisible(true);
            user.setReceiveMessages(true);
            user.setBlurRadiusM(300);
            user.setCreatedAt(Instant.now().minus(
                random.nextInt(180), ChronoUnit.DAYS)); // ancienneté variée
            user.setLastActiveAt(Instant.now().minus(
                random.nextInt(72), ChronoUnit.HOURS));

            // Position dispersée aléatoirement dans un rayon de ~8km du centre
            double[] coords = randomPointNear(centerLat, centerLng, 8000);
            user.setLocation(geometryFactory.createPoint(
                new Coordinate(coords[1], coords[0])));

            User saved = userRepository.save(user);
            created.add(saved);

            // Ajouter 1 à 3 activités par utilisateur
            attachActivitiesAndPrograms(saved, profile);
        }
        return created;
    }

    private void attachActivitiesAndPrograms(User user, DemoProfile profile) {
        for (DemoActivityProfile actProfile : profile.activities()) {
            Activity activity = activityRepository.findBySlug(actProfile.activitySlug())
                .orElseThrow(() -> new IllegalStateException(
                    "Activité de référence introuvable : " + actProfile.activitySlug() +
                    " — exécuter ReferenceDataSeeder avant DemoDataSeeder"));

            UserActivity ua = new UserActivity();
            ua.setUser(user);
            ua.setActivity(activity);
            ua.setVisibleOnMap(true);
            ua.setCustomDescription(actProfile.customDescription());
            ua.setLevel(actProfile.level());
            ua.setFormat(actProfile.format());
            UserActivity savedUa = userActivityRepository.save(ua);

            // Créer un programme actif avec un créneau pour cette activité
            Program program = new Program();
            program.setUserActivity(savedUa);
            program.setTitle(actProfile.programTitle());
            program.setDescription(actProfile.programDescription());
            program.setStatus(ProgramStatus.ACTIVE);
            program.setIsPublic(true);
            Program savedProgram = programRepository.save(program);

            // Générer l'embedding pour activer la recherche sémantique immédiatement
            try {
                String text = program.getTitle() + ". " + program.getDescription();
                float[] embedding = embeddingService.generateEmbedding(text);
                programRepository.updateEmbedding(savedProgram.getId(), embedding);
            } catch (Exception e) {
                log.warn("Embedding non généré pour programme démo {} : {}",
                    savedProgram.getId(), e.getMessage());
            }

            Schedule schedule = new Schedule();
            schedule.setProgram(savedProgram);
            schedule.setPlaceName(actProfile.placeName());
            schedule.setPlaceType(PlaceType.PUBLIC);
            schedule.setLocation(user.getLocation());
            schedule.setAddressPublic(actProfile.placeName() + ", Paris");
            schedule.setStartsAt(nextWeekday(actProfile.dayOfWeek(), actProfile.hour()));
            schedule.setRecurrenceRule("RRULE:FREQ=WEEKLY;BYDAY=" + actProfile.dayOfWeek());
            schedule.setMaxParticipants(actProfile.maxParticipants());
            scheduleRepository.save(schedule);
        }
    }

    // Génère un point aléatoire dans un rayon donné (mètres) autour d'un centre
    private double[] randomPointNear(double lat, double lng, int radiusMeters) {
        double radiusDeg = radiusMeters / 111320.0;
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = Math.sqrt(random.nextDouble()) * radiusDeg; // distribution uniforme en surface
        double newLat = lat + distance * Math.cos(angle);
        double newLng = lng + distance * Math.sin(angle) / Math.cos(Math.toRadians(lat));
        return new double[]{newLat, newLng};
    }

    private Instant nextWeekday(String dayCode, int hour) {
        DayOfWeek target = switch (dayCode) {
            case "MO" -> DayOfWeek.MONDAY; case "TU" -> DayOfWeek.TUESDAY;
            case "WE" -> DayOfWeek.WEDNESDAY; case "TH" -> DayOfWeek.THURSDAY;
            case "FR" -> DayOfWeek.FRIDAY; case "SA" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
        LocalDate date = LocalDate.now();
        while (date.getDayOfWeek() != target) date = date.plusDays(1);
        return date.atTime(hour, 0).atZone(ZoneId.of("Europe/Paris")).toInstant();
    }

    // ====== Données des profils de démonstration ======

    private List<DemoProfile> buildDemoProfiles() {
        return List.of(
            new DemoProfile("demo1@pair.app", "Camille Bertrand",
                "Passionnée de yoga et de céramique, toujours partante pour découvrir.",
                List.of(
                    new DemoActivityProfile("yoga", ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP,
                        "Yoga vinyasa, ambiance détendue", "Séance yoga matinale",
                        "Yoga doux pour bien commencer la journée, tous niveaux bienvenus.",
                        "Studio Lumière", "SA", 9, 8),
                    new DemoActivityProfile("ceramique", ActivityLevel.BEGINNER, ActivityFormat.DUO,
                        "J'apprends encore, ouverte aux conseils", "Atelier céramique du dimanche",
                        "Tournage et modelage, ambiance conviviale et sans pression.",
                        "Atelier Terre & Feu", "SU", 14, 4)
                )),
            new DemoProfile("demo2@pair.app", "Karim Haddad",
                "Coureur régulier, je cherche des partenaires pour les sorties longues.",
                List.of(
                    new DemoActivityProfile("course-a-pied", ActivityLevel.ADVANCED, ActivityFormat.GROUP,
                        "10-15km, rythme soutenu", "Sortie longue du dimanche",
                        "Sortie longue en groupe, prépa semi-marathon.",
                        "Parc des Buttes-Chaumont", "SU", 8, 6),
                    new DemoActivityProfile("trail", ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP,
                        "Découverte du trail", "Initiation trail",
                        "Sortie trail découverte sur sentiers, dénivelé modéré.",
                        "Forêt de Meudon", "SA", 8, 10)
                )),
            new DemoProfile("demo3@pair.app", "Léa Moreau",
                "Échecs et jeux de société, je cherche du monde pour jouer régulièrement.",
                List.of(
                    new DemoActivityProfile("echecs", ActivityLevel.INTERMEDIATE, ActivityFormat.DUO,
                        "Niveau club, parties rapides ou longues", "Parties d'échecs du jeudi",
                        "Parties amicales, tous niveaux, ambiance détendue.",
                        "Café Le Roi", "TH", 19, 2),
                    new DemoActivityProfile("jeux-de-societe", ActivityLevel.ANY, ActivityFormat.GROUP,
                        "Jeux modernes, stratégie et coopératif", "Soirée jeux de société",
                        "Découverte de nouveaux jeux chaque semaine.",
                        "Ludothèque du Marais", "WE", 19, 8)
                )),
            new DemoProfile("demo4@pair.app", "Thomas Girard",
                "Musicien amateur, guitare et un peu de chant. Toujours partant pour une jam.",
                List.of(
                    new DemoActivityProfile("guitare", ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP,
                        "Folk et blues principalement", "Jam session guitare",
                        "Session libre, apportez votre instrument.",
                        "Local associatif Bastille", "FR", 20, 12),
                    new DemoActivityProfile("jam-session", ActivityLevel.ANY, ActivityFormat.GROUP,
                        "Tous instruments bienvenus", "Bœuf musical mensuel",
                        "Improvisation collective, tous styles et niveaux.",
                        "Studio Rive Gauche", "SA", 18, 15)
                )),
            new DemoProfile("demo5@pair.app", "Sophie Lefebvre",
                "Escalade et randonnée, j'aime être dehors le plus possible.",
                List.of(
                    new DemoActivityProfile("escalade", ActivityLevel.ADVANCED, ActivityFormat.DUO,
                        "Bloc principalement, niveau 6a+", "Session bloc en salle",
                        "Recherche partenaire régulier niveau confirmé.",
                        "Arkose Poissonnière", "TU", 18, 2),
                    new DemoActivityProfile("randonnee", ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP,
                        "Journée complète, 15-20km", "Rando du week-end",
                        "Randonnée à la journée, niveau intermédiaire.",
                        "Forêt de Fontainebleau", "SU", 8, 8)
                )),
            new DemoProfile("demo6@pair.app", "Antoine Petit",
                "Photographe argentique, toujours en quête de nouveaux spots.",
                List.of(
                    new DemoActivityProfile("photographie", ActivityLevel.INTERMEDIATE, ActivityFormat.DUO,
                        "Argentique, noir et blanc principalement", "Balade photo urbaine",
                        "Exploration photo de quartiers méconnus.",
                        "Parvis Notre-Dame", "SA", 10, 3)
                )),
            new DemoProfile("demo7@pair.app", "Marine Dubois",
                "Cuisine du monde, je teste une nouvelle recette par semaine.",
                List.of(
                    new DemoActivityProfile("cuisine-du-monde", ActivityLevel.BEGINNER, ActivityFormat.GROUP,
                        "Spécialités asiatiques et moyen-orientales", "Atelier cuisine partagée",
                        "On cuisine ensemble puis on déguste, convivial.",
                        "Cuisine collective Belleville", "WE", 19, 6)
                )),
            new DemoProfile("demo8@pair.app", "Hugo Rousseau",
                "Développeur, je code des side-projects et j'aime en discuter.",
                List.of(
                    new DemoActivityProfile("programmation", ActivityLevel.ADVANCED, ActivityFormat.GROUP,
                        "Web et IA principalement", "Coworking dev du mardi",
                        "Session de code en groupe, projets personnels ou open source.",
                        "Mutinerie Coworking", "TU", 18, 10)
                )),
            new DemoProfile("demo9@pair.app", "Inès Benali",
                "Bénévole engagée, actions environnementales le week-end.",
                List.of(
                    new DemoActivityProfile("benevolat-environnement", ActivityLevel.ANY, ActivityFormat.GROUP,
                        "Nettoyage et sensibilisation", "Nettoyage des berges",
                        "Action de nettoyage collectif, matériel fourni.",
                        "Berges de Seine", "SU", 10, 20)
                )),
            new DemoProfile("demo10@pair.app", "Nicolas Faure",
                "Danse swing depuis 2 ans, je cherche des partenaires d'entraînement.",
                List.of(
                    new DemoActivityProfile("danse", ActivityLevel.INTERMEDIATE, ActivityFormat.DUO,
                        "Lindy hop principalement", "Pratique swing du mercredi",
                        "Entraînement libre, musique swing, tous niveaux acceptés.",
                        "Salle Edison", "WE", 19, 4)
                ))
            // Ajouter demo11 à demo20 sur le même modèle pour varier davantage
            // les activités (musculation, natation, tennis, méditation, peche,
            // langues, lecture, robotique, mentorat, vtt...) — répartir pour
            // couvrir un maximum de catégories disponibles.
        );
    }

    record DemoProfile(String email, String displayName, String bio,
                       List<DemoActivityProfile> activities) {}

    record DemoActivityProfile(
        String activitySlug, ActivityLevel level, ActivityFormat format,
        String customDescription, String programTitle, String programDescription,
        String placeName, String dayOfWeek, int hour, int maxParticipants) {}
}
```

---

## Étape 5 — Script de réinitialisation (utile en dev/staging)

### ResetDemoDataCommand.java (commande manuelle, pas auto-exécutée)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ResetDemoDataCommand {

    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    // Appelé manuellement via un endpoint admin protégé OU un profil Maven dédié
    // JAMAIS exposé publiquement
    public void resetDemoData() {
        if (activeProfiles.contains("prod")) {
            throw new IllegalStateException(
                "REFUS DE SÉCURITÉ : réinitialisation impossible en production.");
        }
        log.warn("=== SUPPRESSION de toutes les données démo (emails LIKE 'demo%@pair.app') ===");

        // Supprimer dans l'ordre des dépendances FK
        jdbcTemplate.update("""
            DELETE FROM messages WHERE sender_id IN
              (SELECT id FROM users WHERE email LIKE 'demo%@pair.app')
            """);
        jdbcTemplate.update("""
            DELETE FROM conversation_members WHERE user_id IN
              (SELECT id FROM users WHERE email LIKE 'demo%@pair.app')
            """);
        jdbcTemplate.update("""
            DELETE FROM schedules WHERE program_id IN
              (SELECT p.id FROM programs p
               JOIN user_activities ua ON p.user_activity_id = ua.id
               JOIN users u ON ua.user_id = u.id
               WHERE u.email LIKE 'demo%@pair.app')
            """);
        jdbcTemplate.update("""
            DELETE FROM programs WHERE user_activity_id IN
              (SELECT ua.id FROM user_activities ua
               JOIN users u ON ua.user_id = u.id
               WHERE u.email LIKE 'demo%@pair.app')
            """);
        jdbcTemplate.update("""
            DELETE FROM user_activities WHERE user_id IN
              (SELECT id FROM users WHERE email LIKE 'demo%@pair.app')
            """);
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'demo%@pair.app'");

        log.warn("=== Données démo supprimées ===");
    }
}
```

### Endpoint admin protégé (optionnel, profil dev/staging uniquement)

```java
@RestController
@RequestMapping("/api/admin/seed")
@RequiredArgsConstructor
@Profile({"dev", "staging"}) // n'existe même pas en production
@PreAuthorize("hasRole('ADMIN')")
public class AdminSeedController {

    private final ResetDemoDataCommand resetCommand;
    private final DemoDataSeeder demoDataSeeder;

    @PostMapping("/demo/reset")
    public ResponseEntity<Void> resetDemoData() throws Exception {
        resetCommand.resetDemoData();
        demoDataSeeder.run();
        return ResponseEntity.ok().build();
    }
}
```

---

## Option B — Alternative SQL pure (données de référence uniquement)

> À utiliser si tu préfères une migration Flyway plutôt qu'un seeder Java.
> Ne couvre PAS la génération des embeddings (à faire séparément après).

### V20__seed_reference_data.sql

```sql
-- Catégories (idempotent via ON CONFLICT)
INSERT INTO categories (id, name, icon, color_ramp) VALUES
  (gen_random_uuid(), 'Sport', 'dumbbell', '#EF4444'),
  (gen_random_uuid(), 'Arts & Création', 'palette', '#8B5CF6'),
  (gen_random_uuid(), 'Jeux', 'dice-5', '#F59E0B'),
  (gen_random_uuid(), 'Cuisine', 'chef-hat', '#F97316'),
  (gen_random_uuid(), 'Apprentissage', 'book-open', '#3B82F6'),
  (gen_random_uuid(), 'Plein air', 'mountain', '#10B981'),
  (gen_random_uuid(), 'Musique', 'music', '#EC4899'),
  (gen_random_uuid(), 'Bénévolat', 'heart-handshake', '#06B6D4'),
  (gen_random_uuid(), 'Bien-être', 'sparkles', '#A855F7'),
  (gen_random_uuid(), 'Tech & Numérique', 'cpu', '#6366F1')
ON CONFLICT (name) DO NOTHING; -- nécessite une contrainte UNIQUE(name)

-- Activités (exemple pour Sport — répéter le motif pour chaque catégorie)
INSERT INTO activities (id, name, slug, description, category_id)
SELECT gen_random_uuid(), 'Course à pied', 'course-a-pied',
       'Courir seul ou accompagné, sur route ou en sentier.', c.id
FROM categories c WHERE c.name = 'Sport'
ON CONFLICT (slug) DO NOTHING;

-- Badges
INSERT INTO badges (id, code, category, label, condition_type, condition_threshold, icon) VALUES
  (gen_random_uuid(), 'VERIFIED_EMAIL', 'TRUST', 'Email vérifié', 'VERIFICATION', NULL, 'mail-check'),
  (gen_random_uuid(), 'FIRST_RECOMMENDATION', 'TRUST', 'Première recommandation', 'RECOMMENDATION_COUNT', 1, 'thumbs-up'),
  (gen_random_uuid(), 'STREAK_7', 'ACHIEVEMENT', 'Une semaine de suite', 'PROGRESSION_STREAK', 7, 'flame')
  -- ... compléter avec le reste de badges.json
ON CONFLICT (code) DO NOTHING;
```

---

## Récapitulatif des commandes

```bash
# Lancer l'app en dev (seed référence + démo automatique)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Lancer en staging (seed référence + démo)
mvn spring-boot:run -Dspring-boot.run.profiles=staging

# Lancer en production (seed référence UNIQUEMENT, jamais de démo)
java -jar pair-backend.jar --spring.profiles.active=prod

# Réinitialiser les données démo manuellement (dev/staging uniquement)
curl -X POST http://localhost:8080/api/admin/seed/demo/reset \
  -H "Authorization: Bearer <token-admin>"
```

---

## Checklist de validation après implémentation

```markdown
- [ ] Au démarrage en profil dev, les 10 catégories sont créées une seule fois
- [ ] Relancer l'application ne duplique aucune catégorie/activité/badge
- [ ] Les activités hiérarchiques (trail → course-a-pied) ont bien leur parent_id renseigné
- [ ] Chaque activité de référence a un embedding non-null après quelques secondes
- [ ] En profil dev, 10+ comptes de démonstration apparaissent avec des positions
      dispersées autour du centre configuré
- [ ] La carte (/api/map/users) retourne bien les comptes démo avec floutage actif
- [ ] La recherche sémantique ("je veux faire du yoga") retourne au moins
      un résultat parmi les comptes démo
- [ ] Tenter de démarrer avec demo-data.enabled=true ET profile=prod lève
      une exception au démarrage (test manuel)
- [ ] Le endpoint /api/admin/seed/demo/reset n'existe pas (404) en profil prod
```

