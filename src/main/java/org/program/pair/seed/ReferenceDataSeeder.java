package org.program.pair.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.search.EmbeddingService;
import org.program.pair.domain.trust.Badge;
import org.program.pair.domain.trust.BadgeCategory;
import org.program.pair.domain.trust.BadgeConditionType;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.BadgeRepository;
import org.program.pair.repository.CategoryRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(1)
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
        log.info("=== [ReferenceDataSeeder] Démarrage ===");
        seedCategories();
        seedActivities();
        seedBadges();
        log.info("=== [ReferenceDataSeeder] Terminé ===");
    }

    private void seedCategories() throws IOException {
        log.info("Chargement des catégories...");
        List<CategorySeed> seeds = loadJson("seed/data/categories.json", new TypeReference<>() {});

        int created = 0;
        int skipped = 0;

        for (CategorySeed seed : seeds) {
            if (categoryRepository.existsByName(seed.name())) {
                skipped++;
                continue;
            }

            Category category = Category.builder()
                .name(seed.name())
                .icon(seed.icon())
                .colorRamp(seed.colorRamp())
                .build();

            categoryRepository.save(category);
            created++;
            log.debug("Catégorie créée: {}", seed.name());
        }

        log.info("Catégories: {} créées, {} déjà présentes (ignorées)", created, skipped);
    }

    private void seedActivities() throws IOException {
        log.info("Chargement des activités...");
        List<ActivitySeed> seeds = loadJson("seed/data/activities.json", new TypeReference<>() {});

        // Construire la map categoryCode -> Category
        Map<String, Category> categoryMap = buildCategoryCodeMap();

        int created = 0;
        int skipped = 0;

        // Première passe: créer toutes les activités sans parent
        for (ActivitySeed seed : seeds) {
            if (activityRepository.existsBySlug(seed.slug())) {
                skipped++;
                continue;
            }

            Category category = categoryMap.get(seed.categoryCode());
            if (category == null) {
                log.warn("Catégorie introuvable pour code: {}, activité {} ignorée", seed.categoryCode(), seed.slug());
                skipped++;
                continue;
            }

            Activity activity = Activity.builder()
                .slug(seed.slug())
                .name(seed.name())
                .description(seed.description())
                .category(category)
                .parent(null) // sera résolu dans la 2ème passe
                .build();

            activityRepository.save(activity);
            created++;
            log.debug("Activité créée: {} (sans parent pour l'instant)", seed.name());
        }

        // Deuxième passe: résoudre les parents
        for (ActivitySeed seed : seeds) {
            if (seed.parentSlug() != null) {
                activityRepository.findBySlug(seed.slug()).ifPresent(activity -> {
                    activityRepository.findBySlug(seed.parentSlug()).ifPresent(parent -> {
                        activity.setParent(parent);
                        activityRepository.save(activity);
                        log.debug("Parent résolu: {} -> {}", seed.slug(), seed.parentSlug());
                    });
                });
            }
        }

        log.info("Activités: {} créées, {} déjà présentes (ignorées)", created, skipped);

        // Générer les embeddings manquants
        generateMissingEmbeddings();
    }

    @Async
    public void generateMissingEmbeddings() {
        if (!embeddingService.isConfigured()) {
            log.info("EmbeddingService non configuré, génération des embeddings ignorée");
            return;
        }

        log.info("Génération des embeddings manquants...");
        List<Activity> activitiesWithoutEmbedding = activityRepository.findByEmbeddingIsNull();

        if (activitiesWithoutEmbedding.isEmpty()) {
            log.info("Aucun embedding à générer");
            return;
        }

        int generated = 0;
        int failed = 0;

        for (Activity activity : activitiesWithoutEmbedding) {
            try {
                String text = activity.getName() + " " + (activity.getDescription() != null ? activity.getDescription() : "");
                float[] embedding = embeddingService.generateEmbedding(text);

                if (embedding != null) {
                    String vectorString = embeddingService.toVectorString(embedding);
                    activityRepository.updateEmbedding(activity.getId(), vectorString);
                    generated++;
                    log.debug("Embedding généré pour: {}", activity.getName());
                } else {
                    failed++;
                    log.warn("Échec génération embedding pour: {}", activity.getName());
                }

                // Throttle entre chaque requête
                Thread.sleep(200);

            } catch (Exception e) {
                failed++;
                log.error("Erreur lors de la génération de l'embedding pour {}: {}", activity.getName(), e.getMessage());
            }
        }

        log.info("Embeddings générés: {}, échecs: {}", generated, failed);
    }

    private void seedBadges() throws IOException {
        log.info("Chargement des badges...");
        List<BadgeSeed> seeds = loadJson("seed/data/badges.json", new TypeReference<>() {});

        int created = 0;
        int skipped = 0;

        for (BadgeSeed seed : seeds) {
            if (badgeRepository.existsByCode(seed.code())) {
                skipped++;
                continue;
            }

            Badge badge = Badge.builder()
                .code(seed.code())
                .category(BadgeCategory.valueOf(seed.category()))
                .label(seed.label())
                .conditionType(BadgeConditionType.valueOf(seed.conditionType()))
                .conditionThreshold(seed.conditionThreshold())
                .icon(seed.icon())
                .build();

            badgeRepository.save(badge);
            created++;
            log.debug("Badge créé: {}", seed.code());
        }

        log.info("Badges: {} créés, {} déjà présents (ignorés)", created, skipped);
    }

    private Map<String, Category> buildCategoryCodeMap() {
        // Charge toutes les catégories et construit une map code->entity
        // Le "code" est déduit du name en minuscules avec underscores
        List<Category> categories = categoryRepository.findAll();
        Map<String, Category> map = new HashMap<>();

        for (Category cat : categories) {
            // Mapping manuel basé sur les données
            String code = deriveCategoryCode(cat.getName());
            map.put(code, cat);
        }

        return map;
    }

    private String deriveCategoryCode(String name) {
        // Mapping manuel pour correspondre aux codes du JSON
        return switch (name) {
            case "Sport" -> "sport";
            case "Arts & Création" -> "arts";
            case "Jeux" -> "jeux";
            case "Cuisine" -> "cuisine";
            case "Apprentissage" -> "apprentissage";
            case "Plein air" -> "plein_air";
            case "Musique" -> "musique";
            case "Bénévolat" -> "benevolat";
            case "Bien-être" -> "bien_etre";
            case "Tech & Numérique" -> "tech";
            default -> name.toLowerCase().replace(" ", "_").replace("é", "e").replace("è", "e");
        };
    }

    private <T> T loadJson(String path, TypeReference<T> typeRef) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        return objectMapper.readValue(resource.getInputStream(), typeRef);
    }

    // Records internes pour la désérialisation
    record CategorySeed(String code, String name, String icon, String colorRamp) {}

    record ActivitySeed(
        String slug,
        String name,
        String categoryCode,
        String parentSlug,
        String description
    ) {}

    record BadgeSeed(
        String code,
        String category,
        String label,
        String conditionType,
        Integer conditionThreshold,
        String icon
    ) {}
}
