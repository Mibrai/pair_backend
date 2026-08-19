package org.program.pair.domain.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.LocationType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAddressVisibility;
import org.program.pair.domain.block.BlockFilterService;
import org.program.pair.domain.search.dto.*;
import org.program.pair.domain.search.embedding.LocalEmbeddingService;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SearchLogRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.GeoUtils;
import org.program.pair.shared.i18n.Messages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticSearchService {

    /**
     * Fuseau de référence pour rapprocher un instant UTC d'une case de
     * disponibilité. Le même que celui du développement des récurrences : deux
     * fuseaux différents rangeraient la même séance dans « mardi soir » ici et
     * « mardi après-midi » là.
     */
    @Value("${pair.recurrence.zone:Europe/Paris}")
    private String zoneId;

    private final BlockFilterService blockFilterService;
    private final RuleBasedIntentExtractor intentExtractor;
    private final Messages messages;
    private final LocalEmbeddingService embeddingService;
    private final FullTextSearchService fullTextSearchService;
    private final ProgramRepository programRepository;
    private final ActivityRepository activityRepository;
    private final ScheduleRepository scheduleRepository;
    private final SlotParticipationRepository slotParticipationRepository;
    private final SearchLogRepository searchLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ActivityTaxonomy activityTaxonomy;

    /** Nombre maximal de résultats "slot" mélangés aux résultats programme/personne. */
    private static final int MAX_SLOT_RESULTS = 10;
    /**
     * Nombre de candidats que le moteur produit avant découpe. C'était 20, la
     * taille d'une page : au-delà de la première, il n'y avait rien à servir.
     * totalCount est donc exact jusqu'à ce plafond, et un « au moins N »
     * au-delà — c'est l'arbitrage (a) retenu avec le client.
     */
    private static final int CANDIDATE_LIMIT = 200;
    private static final int MAX_PAGE_SIZE = 100;

    /** Similarité cosine minimale (0-1) pour qu'un résultat vectoriel soit retenu. */
    @Value("${search.embedding.min-similarity:0.25}")
    private double minSimilarity;

    @Transactional
    public SearchResponse search(SearchRequest request, UUID userId) {
        log.info("Search request from user {}: '{}'", userId, request.query());

        // 1. Logger la recherche brute
        String method = "semantic";
        SearchLog searchLog = SearchLog.builder()
            .user(userRepository.getReferenceById(userId))
            .rawQuery(request.query())
            .searchMethod(method)
            .build();

        // 2. Extraire l'intention via LLM (ou fallback)
        SearchIntent intent = intentExtractor.extractIntent(request.query());

        try {
            searchLog.setParsedIntent(objectMapper.writeValueAsString(intent));
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize intent: {}", e.getMessage());
        }

        // 3. Si clarification nécessaire → répondre immédiatement
        if (intent.needsClarification()) {
            searchLog.setResultsCount(0);
            searchLogRepository.save(searchLog);
            log.info("Clarification needed for query: '{}'", request.query());
            return SearchResponse.clarification(intent.clarificationQuestion(), intent);
        }

        // 4. Déterminer le rayon de recherche
        int radius = determineRadius(request, intent);

        // 5. Recherche full-text dans la base
        List<SearchResultDto> results = performSearch(request, intent, radius, userId);

        searchLog.setResultsCount(results.size());
        searchLogRepository.save(searchLog);

        // 6. Si aucun résultat → suggestions alternatives
        if (results.isEmpty()) {
            log.info("No results found for query: '{}'", request.query());
            List<EmptyStateActionDto> actions = buildEmptyStateActions(request, intent, radius);
            return SearchResponse.empty(actions, intent);
        }

        // 7. Découpe. Les compteurs portent sur la requête entière, pas sur la
        // page : un onglet « Personnes (3) » qui afficherait 3 puis 0 en page 2
        // serait pire que pas de compteur du tout.
        int pageSize = Math.min(Math.max(request.effectivePageSize(), 1), MAX_PAGE_SIZE);
        int page = Math.max(request.effectivePage(), 0);
        int from = Math.min((int) Math.min((long) page * pageSize, Integer.MAX_VALUE), results.size());
        int to = Math.min(from + pageSize, results.size());

        List<SearchResultDto> pageResults = results.subList(from, to);
        log.info("Found {} results for query: '{}' (page {}, {} servis)",
            results.size(), request.query(), page, pageResults.size());

        return SearchResponse.results(pageResults, intent, results.size(), page, pageSize,
            countsByType(results));
    }

    /**
     * Total par type sur la liste entière. Les clés reprennent l'énumération
     * {@code resultType} — {@code user}, {@code program}, {@code slot} — et les
     * trois sont toujours présentes, à zéro le cas échéant : un client qui
     * affiche des onglets ne doit pas avoir à distinguer « zéro » de « absent ».
     */
    private Map<String, Integer> countsByType(List<SearchResultDto> all) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("user", 0);
        counts.put("program", 0);
        counts.put("slot", 0);
        for (SearchResultDto result : all) {
            counts.merge(result.resultType(), 1, Integer::sum);
        }
        return counts;
    }

    private int determineRadius(SearchRequest request, SearchIntent intent) {
        if (request.radiusMeters() != null) {
            return request.radiusMeters();
        }
        if (intent.suggestedRadius() != null) {
            return intent.suggestedRadius();
        }
        return 5000; // Default 5km
    }

    private List<SearchResultDto> performSearch(SearchRequest request, SearchIntent intent, int radius, UUID requesterId) {
        SearchRequest searchRequest = new SearchRequest(
            request.query(), request.lat(), request.lng(), radius);

        // 1. Couche déterministe : taxonomie d'activités canonique EN/DE/FR.
        // Garantit le matching cross-lingue sur les activités connues (ex: "Laufen"
        // -> slug "running" -> "course à pied"/"marche à pied"/"running"),
        // indépendamment de la qualité du rappel sémantique.
        Set<String> taxonomyLabels = activityTaxonomy.matchLabels(
            request.query(), intent.activityKeyword(), intent.canonicalActivitySlug());
        List<SearchResultDto> taxonomyResults = taxonomyLabels.isEmpty()
            ? List.of()
            : fullTextSearchService.searchByTaxonomyLabels(taxonomyLabels, searchRequest, CANDIDATE_LIMIT);

        // 2. Couche de rappel : embeddings multilingues (ou full-text en fallback si
        // le modèle local n'a pas pu être chargé/n'a rien produit).
        float[] embedding = embeddingService.generateEmbedding(request.query());
        List<SearchResultDto> recallResults;
        if (LocalEmbeddingService.isZeroVector(embedding)) {
            recallResults = fulltextFallback(intent, searchRequest);
        } else {
            List<org.program.pair.domain.program.Program> candidates =
                programRepository.semanticSearchInRadius(
                    embeddingService.toVectorString(embedding),
                    request.lat(), request.lng(), radius, 1 - minSimilarity, CANDIDATE_LIMIT);
            recallResults = toSearchResultDtos(candidates,
                resolveVenues(candidates, request.lat(), request.lng()));
        }

        // 3. Fusion : les matchs taxonomiques (précision) priment, complétés par le
        // rappel sémantique/full-text, dédupliqués par programme.
        List<SearchResultDto> results = mergeResults(taxonomyResults, recallResults, CANDIDATE_LIMIT);

        // Blocage : appliqué ici, avant la pagination et avant countsByType, donc
        // les compteurs d'onglets portent bien sur ce que l'appelant peut voir.
        // Filtrer plus tard ferait annoncer « Programmes (12) » puis en servir 9.
        //
        // C'est le seul endroit du lot où le filtre reste en mémoire. Les trois
        // requêtes de rappel plein texte sont bâties à la main avec des
        // paramètres positionnels, et y insérer un prédicat au mauvais rang est
        // le mode de panne que ce fichier documente déjà. La contrepartie est
        // bornée et connue : la limite de candidats s'applique avant ce filtre,
        // donc quelqu'un qui a beaucoup bloqué peut recevoir un peu moins de
        // candidats — jamais un résultat qu'il ne devrait pas voir.
        if (requesterId != null && !results.isEmpty()) {
            Set<UUID> invisible = blockFilterService.invisibleTo(requesterId);
            if (!invisible.isEmpty()) {
                results = results.stream()
                    .filter(r -> r.organizerId() == null || !invisible.contains(r.organizerId()))
                    .toList();
            }
        }

        // Filtrer par niveau si spécifié par le LLM
        if (intent.level() != null && !results.isEmpty()) {
            results = results.stream()
                .filter(r -> r.level() != null && r.level().equalsIgnoreCase(intent.level()))
                .toList();
        }

        // Filtrer par format si spécifié
        if (intent.format() != null && !results.isEmpty()) {
            results = results.stream()
                .filter(r -> r.format() != null &&
                    (r.format().equalsIgnoreCase(intent.format()) || r.format().equalsIgnoreCase("BOTH")))
                .toList();
        }

        // 4. Créneaux correspondant à l'intention temporelle — priorité absolue :
        // un créneau dans 2h vaut plus qu'un programme sans date. Triés par
        // date croissante, puis complétés par les programmes (le reste de la
        // liste ci-dessus), jamais tronqués par la limite globale.
        List<SearchResultDto> slotResults = searchSlots(request, intent, radius, requesterId);
        int programBudget = Math.max(0, CANDIDATE_LIMIT - slotResults.size());
        List<SearchResultDto> boundedPrograms = results.size() > programBudget
            ? results.subList(0, programBudget)
            : results;

        return java.util.stream.Stream.concat(slotResults.stream(), boundedPrograms.stream()).toList();
    }

    /**
     * Créneaux ouverts correspondant à l'activité résolue, dans la fenêtre
     * temporelle déduite de `timeHint`. Réutilise exactement la même requête
     * de filtrage que GET /api/slots/feed (ScheduleRepository.findOpenSlotsInRadius)
     * pour que les deux surfaces ne divergent jamais : hôte actif, programme
     * public et actif, activité non masquée, statut OPEN/FULL, créneaux de
     * l'appelant exclus.
     */
    private List<SearchResultDto> searchSlots(SearchRequest request, SearchIntent intent, int radius, UUID requesterId) {
        UUID activityId = resolveActivityId(intent);
        if (activityId == null) {
            return List.of();
        }

        TimeHintParser.Window window = TimeHintParser.resolveWindow(intent.timeHint(), Instant.now());

        // Le filtre d'accessibilité ne porte que sur les créneaux : une étiquette
        // décrit une séance et un lieu, pas un programme.
        java.util.Set<String> tags = request.effectiveAccessibilityTags();
        boolean filterByTags = !tags.isEmpty();

        // La recherche cible une activité précise, donc jamais une catégorie, et ne
        // filtre pas sur la date de publication : les deux paramètres sont neutres.
        List<Schedule> slots = scheduleRepository.findOpenSlotsInRadius(
            request.lat(), request.lng(), radius, window.from(), window.to(), activityId,
            false, ScheduleRepository.NO_CATEGORY_FILTER, null, MAX_SLOT_RESULTS, requesterId,
            false, ScheduleRepository.NO_LANGUAGE_FILTER,
            filterByTags, filterByTags ? tags : ScheduleRepository.NO_TAG_FILTER, tags.size(),
            zoneId);

        // L'ordre de la requête est conservé tel quel. Il trie déjà par jour puis
        // par heure ; le retrier ici par startsAt seul écraserait la pondération
        // par disponibilité que la requête vient d'appliquer, sans rien changer
        // d'autre — les deux ordres coïncident pour qui n'a rien déclaré.
        return slots.stream()
            .filter(s -> !s.getProgram().getUserActivity().getUser().getId().equals(requesterId))
            .map(s -> toSlotResultDto(s, request.lat(), request.lng(), requesterId))
            .toList();
    }

    private SearchResultDto toSlotResultDto(Schedule schedule, double viewerLat, double viewerLng, UUID requesterId) {
        Program program = schedule.getProgram();
        UserActivity userActivity = program.getUserActivity();
        Activity activity = userActivity.getActivity();
        Category category = activity.getCategory();
        User host = userActivity.getUser();

        SlotAddressVisibility.Resolved place =
            SlotAddressVisibility.resolve(schedule, requesterId, slotParticipationRepository);

        Double distanceMeters = schedule.getLocation() != null
            ? GeoUtils.haversineMeters(viewerLat, viewerLng, schedule.getLocation().getY(), schedule.getLocation().getX())
            : null;

        return new SearchResultDto(
            "slot",
            schedule.getId(),
            program.getTitle(),
            schedule.getWelcomeNote(),
            host.getAvatarUrl(),
            place.lat(),
            place.lng(),
            distanceMeters,
            timeProximityScore(schedule.getStartsAt()),
            activity.getName(),
            userActivity.getLevel() != null ? userActivity.getLevel().name() : null,
            userActivity.getFormat() != null ? userActivity.getFormat().name() : null,
            false,
            host.getVerificationStatus().name(),
            userActivity.getId(),
            category != null ? category.getId() : null,
            category != null ? category.getName() : null,
            host.getId(),
            host.getDisplayName(),
            host.getAvatarUrl(),
            program.getImageUrl(),
            null, null, // averageScore, reviewCount : non pertinents pour un créneau
            schedule.getParticipantCount(),
            schedule.getStatus().name(),
            null,   // locationType : notion programme, pas créneau
            null,   // city : non dénormalisé (voir toSearchResultDtos)
            schedule.getStartsAt(), // createdAt réutilisé pour un tri générique côté client
            null,
            schedule.getStartsAt(),
            schedule.getEndsAt(),
            schedule.getMaxParticipants()
        );
    }

    /** Score décroissant avec l'éloignement temporel : un créneau proche prime. */
    private Float timeProximityScore(Instant startsAt) {
        long hoursUntilStart = Math.max(0, java.time.Duration.between(Instant.now(), startsAt).toHours());
        return (float) (1.0 / (1.0 + hoursUntilStart));
    }

    /**
     * Résout un identifiant d'activité concret à partir de l'intention : le
     * slug canonique de la taxonomie en priorité, puis un match approximatif
     * sur le mot-clé brut en repli. Partagé entre la recherche de créneaux et
     * les actions d'état vide pour qu'elles s'accordent sur la même activité.
     */
    private UUID resolveActivityId(SearchIntent intent) {
        if (intent.canonicalActivitySlug() != null) {
            UUID bySlug = activityRepository.findBySlug(intent.canonicalActivitySlug())
                .map(Activity::getId).orElse(null);
            if (bySlug != null) return bySlug;
        }
        if (intent.activityKeyword() != null) {
            return activityRepository
                .findByNameContainingIgnoreCase(intent.activityKeyword(),
                    org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst().map(Activity::getId).orElse(null);
        }
        return null;
    }

    private List<SearchResultDto> mergeResults(
            List<SearchResultDto> primary, List<SearchResultDto> secondary, int limit) {
        LinkedHashMap<UUID, SearchResultDto> merged = new LinkedHashMap<>();
        primary.forEach(r -> merged.put(r.id(), r));
        secondary.forEach(r -> merged.putIfAbsent(r.id(), r));
        return merged.values().stream().limit(limit).toList();
    }

    private List<SearchResultDto> fulltextFallback(SearchIntent intent, SearchRequest searchRequest) {
        String keywords = intent.activityKeyword() != null ? intent.activityKeyword() : searchRequest.query();
        List<SearchResultDto> results = fullTextSearchService.searchPrograms(keywords, searchRequest, 20);

        if (results.isEmpty() && intent.activityKeyword() != null) {
            log.debug("Full-text returned nothing, trying exact activity match");
            results = fullTextSearchService.searchByActivity(intent.activityKeyword(), searchRequest, 20);
        }
        return results;
    }

    /**
     * Coordonnée de recherche de chaque programme, résolue en une seule requête.
     *
     * <p>Les programmes à distance sont écartés d'emblée : ils n'ont pas de lieu,
     * et interroger la base pour eux ne rendrait rien. Ceux qui restent sans
     * séance localisée n'auront pas d'entrée, ce que
     * {@link #toSearchResultDtos} traduit en coordonnées nulles.
     */
    private Map<UUID, ProgramVenue> resolveVenues(
            List<org.program.pair.domain.program.Program> programs, double lat, double lng) {

        List<UUID> located = programs.stream()
            .filter(p -> !isRemote(p))
            .map(org.program.pair.domain.program.Program::getId)
            .toList();
        if (located.isEmpty()) {
            return Map.of();
        }

        Map<UUID, ProgramVenue> venues = new LinkedHashMap<>();
        for (Object[] row : scheduleRepository.findNearestVenuesByProgramIds(located, lat, lng)) {
            venues.put((UUID) row[0], new ProgramVenue(
                ((Number) row[1]).doubleValue(),
                ((Number) row[2]).doubleValue(),
                ((Number) row[3]).doubleValue()));
        }
        return venues;
    }

    /**
     * Un programme à distance n'a pas de lieu, donc pas de distance.
     *
     * <p>À ne pas confondre avec le champ {@code isOnline} de la réponse, qui
     * dit tout autre chose — la présence récente de l'organisateur. La confusion
     * des deux notions sous un même mot est signalée au client ; c'est
     * {@code locationType} qui porte la modalité du programme.
     */
    private static boolean isRemote(org.program.pair.domain.program.Program program) {
        LocationType type = program.getLocationType();
        return type == LocationType.REMOTE || type == LocationType.ONLINE;
    }

    // Package-private (au lieu de private) pour permettre un test unitaire ciblé
    // du mapping — priorité imageUrl / media[0], et situation du programme — sans
    // dépendances Spring/DB.
    List<SearchResultDto> toSearchResultDtos(
            List<org.program.pair.domain.program.Program> programs,
            Map<UUID, ProgramVenue> venues) {

        return programs.stream().map(p -> {
            var ua    = p.getUserActivity();
            var owner = ua.getUser();
            var act   = ua.getActivity();
            var cat   = act.getCategory();

            // Le programme est situé à sa séance, jamais au domicile de son
            // organisateur : c'est tout l'objet de la correction. Absence de
            // séance localisée ou programme à distance ⇒ null, pas de repli.
            ProgramVenue venue = isRemote(p) ? null : venues.get(p.getId());

            boolean isOnline = owner.getLastActiveAt() != null
                && owner.getLastActiveAt().isAfter(java.time.Instant.now().minusSeconds(300));

            // Image de couverture dédiée en priorité (cohérent avec la page détail),
            // repli sur le premier média IMAGE de la galerie si absente.
            String thumbnailUrl = p.getImageUrl() != null
                ? p.getImageUrl()
                : p.getMedia().stream()
                    .filter(m -> m.getMediaType() == org.program.pair.domain.program.MediaType.IMAGE)
                    .min(java.util.Comparator.comparingInt(
                        org.program.pair.domain.program.ProgramMedia::getSortOrder))
                    .map(org.program.pair.domain.program.ProgramMedia::getUrl)
                    .orElse(null);

            return new SearchResultDto(
                "program",
                p.getId(),
                p.getTitle(),
                p.getDescription() != null
                    ? p.getDescription().substring(0, Math.min(200, p.getDescription().length()))
                    : null,
                owner.getAvatarUrl(),
                venue != null ? venue.lat() : null,
                venue != null ? venue.lng() : null,
                venue != null ? venue.distanceMeters() : null,
                0f,
                act.getName(),
                ua.getLevel() != null ? ua.getLevel().name() : null,
                ua.getFormat() != null ? ua.getFormat().name() : null,
                isOnline,
                owner.getVerificationStatus().name(),
                // champs enrichis
                ua.getId(),
                cat != null ? cat.getId() : null,
                cat != null ? cat.getName() : null,
                owner.getId(),
                owner.getDisplayName(),
                owner.getAvatarUrl(),
                thumbnailUrl,
                null,   // averageScore : non chargé en JPA (évite N+1)
                null,   // reviewCount
                null,   // enrolledCount
                p.getStatus().name(),
                p.getLocationType() != null ? p.getLocationType().name() : null,
                null,   // city
                p.getCreatedAt(),
                p.getUpdatedAt(),
                null, null, null // startsAt/endsAt/maxParticipants : spécifiques aux résultats "slot"
            );
        }).toList();
    }

    private List<EmptyStateActionDto> buildEmptyStateActions(SearchRequest request, SearchIntent intent, int radius) {
        List<EmptyStateActionDto> actions = new java.util.ArrayList<>();

        // 1. Élargir le rayon (libellé conservé à l'identique : des clients/tests
        // existants font correspondre ce texte, seul le type devient structuré)
        int expanded = Math.min(radius * 3, 50000);
        if (expanded > radius) {
            actions.add(new EmptyStateActionDto("EXPAND_RADIUS",
                messages.get("search.action.expandRadius", expanded / 1000),
                Map.of("radiusMeters", expanded)));
        }

        // Même résolution que la recherche de créneaux, pour rendre
        // CREATE_SLOT/SET_ALERT actionnables sur la même activité.
        UUID activityId = resolveActivityId(intent);

        if (activityId != null) {
            // 2. Créer soi-même un créneau (transformer le vide en action)
            actions.add(new EmptyStateActionDto("CREATE_SLOT",
                messages.get("search.action.createSlot.here"),
                Map.of("activityId", activityId)));

            // 3. Poser une alerte
            actions.add(new EmptyStateActionDto("SET_ALERT",
                messages.get("search.action.setAlert"),
                Map.of("activityId", activityId,
                       "lat", request.lat(), "lng", request.lng(),
                       "radiusMeters", radius)));

            // 4. Activités de la même catégorie ayant du monde à proximité
            List<Activity> neighbours = activityRepository
                .findSimilarActivitiesWithNearbyUsers(activityId, request.lat(), request.lng(), radius, 3);
            for (Activity a : neighbours) {
                actions.add(new EmptyStateActionDto("SIMILAR_ACTIVITY",
                    messages.get("search.action.similarActivity", a.getName()),
                    Map.of("activityId", a.getId().toString(), "name", a.getName())));
            }
        } else if (intent.activityKeyword() != null) {
            actions.add(new EmptyStateActionDto("CREATE_SLOT",
                messages.get("search.action.createSlot.keyword", intent.activityKeyword()),
                Map.of()));
        } else {
            actions.add(new EmptyStateActionDto("CREATE_SLOT",
                messages.get("search.action.createSlot.generic"),
                Map.of()));
        }

        return actions;
    }
}
