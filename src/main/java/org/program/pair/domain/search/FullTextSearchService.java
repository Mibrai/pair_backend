package org.program.pair.domain.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.dto.SearchResultDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FullTextSearchService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Modalités sans lieu, au sens de {@code LocationType}.
     *
     * <p>À ne pas confondre avec le champ {@code isOnline} de la réponse, qui dit
     * tout autre chose — la présence récente de l'organisateur.
     */
    private static final Set<String> REMOTE_LOCATION_TYPES = Set.of("REMOTE", "ONLINE");

    // Fragment SQL commun pour les colonnes enrichies
    private static final String PROGRAM_SELECT = """
            p.id,
            p.title,
            p.description,
            p.status,
            p.location_type,
            p.created_at,
            p.updated_at,
            p.user_activity_id,
            ua.id   AS ua_id,
            ua.level,
            ua.format,
            u.id    AS user_id,
            u.display_name,
            u.avatar_url,
            u.verification_status,
            u.last_active_at,
            ST_Y(venue.location) AS lat,
            ST_X(venue.location) AS lng,
            a.id    AS activity_id,
            a.name  AS activity_name,
            cat.id  AS category_id,
            cat.name AS category_name,
            COALESCE(
                p.image_url,
                (SELECT pm.url
                   FROM program_media pm
                  WHERE pm.program_id = p.id AND pm.media_type = 'IMAGE'
                  ORDER BY pm.sort_order ASC
                  LIMIT 1)
            )                              AS thumbnail_url,
            (SELECT AVG(r.score)::float
               FROM reviews r
              WHERE r.program_id = p.id)   AS average_score,
            (SELECT COUNT(*)::int
               FROM reviews r
              WHERE r.program_id = p.id)   AS review_count,
            (SELECT COUNT(*)::int
               FROM user_programs up
              WHERE up.program_id = p.id AND up.status = 'ACTIVE') AS enrolled_count
        """;

    /**
     * Situe le programme à sa séance localisée la plus proche du point interrogé.
     *
     * <p>Les trois recherches de ce service lisaient {@code u.location}, la
     * position du <b>compte organisateur</b> : coordonnée rendue, distance
     * affichée, filtre de rayon et tri en dépendaient tous. Un cours à Munich
     * s'annonçait donc à Brême, et — moins visible mais plus grave — un
     * programme dont les séances se tiennent à deux kilomètres n'entrait pas
     * dans une recherche à cinq quand son organisateur habitait ailleurs.
     *
     * <p>Le {@code LEFT JOIN LATERAL} rend une ligne au plus par programme. Il
     * n'exclut donc rien par lui-même : c'est {@link #VENUE_WITHIN_RADIUS} qui
     * décide, et il le fait sur le lieu de la séance.
     *
     * <p>Deux paramètres, dans cet ordre : {@code lng}, {@code lat}.
     */
    private static final String VENUE_JOIN = """
        LEFT JOIN LATERAL (
            SELECT s.location AS location,
                   ST_Distance(
                       s.location::geography,
                       ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                   ) AS distance_meters
              FROM schedules s
             WHERE s.program_id = p.id
               AND s.location IS NOT NULL
             ORDER BY 2
             LIMIT 1
        ) venue ON TRUE
        """;

    /**
     * Le filtre de rayon, porté par le lieu de la séance.
     *
     * <p>Le principe : <b>un rayon ne peut exclure que ce qu'on sait situer.</b>
     * Un programme à distance, ou sans aucune séance localisée, y échappe donc
     * plutôt que d'y échouer — il est rendu, sans coordonnées, et le tri le place
     * après les résultats situés. Le borner sur la position de son organisateur
     * serait revenir au défaut corrigé ici ; l'exclure reviendrait à le filtrer
     * sur un critère qu'on est incapable d'évaluer pour lui.
     *
     * <p>C'est aussi ce que le client a demandé explicitement : « nous savons ne
     * rien afficher, nous ne savons pas deviner qu'un chiffre est faux ». Rendre
     * le programme sans lieu le laisse trouvable ; c'est l'affichage d'une
     * distance inventée qui posait problème, pas la présence du résultat.
     *
     * <p>Un paramètre : le rayon en mètres.
     */
    private static final String VENUE_WITHIN_RADIUS = """
        (
            p.location_type IN ('REMOTE', 'ONLINE')
            OR venue.distance_meters IS NULL
            OR venue.distance_meters <= ?
        )
        """;

    /**
     * Recherche full-text dans les programmes avec filtres géographiques.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<SearchResultDto> searchPrograms(String keywords, SearchRequest request, int limit) {
        String tsQuery = prepareTsQuery(keywords);
        int radius = request.radiusMeters() != null ? request.radiusMeters() : 5000;

        String sql = """
            SELECT
                %s,
                venue.distance_meters,
                ts_rank(p.search_vector, to_tsquery('french', ?)) AS rank
            FROM programs p
            INNER JOIN user_activities ua  ON p.user_activity_id = ua.id
            INNER JOIN users u             ON ua.user_id          = u.id
            INNER JOIN activities a        ON ua.activity_id      = a.id
            INNER JOIN categories cat      ON a.category_id       = cat.id
            %s
            WHERE
                p.status = 'ACTIVE'
                AND p.is_public = true
                AND p.search_vector @@ to_tsquery('french', ?)
                AND %s
            -- p.id en dernier critère : sans lui, deux programmes de même rang
            -- et même distance peuvent sortir dans un ordre différent d'un appel
            -- à l'autre, ce qui ferait bouger les pages sous le client.
            -- NULLS LAST est explicite : les programmes à distance n'ont pas de
            -- distance, et doivent suivre les résultats situés, pas les précéder.
            ORDER BY rank DESC, venue.distance_meters ASC NULLS LAST, p.id
            LIMIT ?
            """.formatted(PROGRAM_SELECT, VENUE_JOIN, VENUE_WITHIN_RADIUS);

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                sql,
                tsQuery,
                request.lng(), request.lat(),
                tsQuery,
                radius,
                limit
            );
            return rows.stream().map(r -> mapRowToSearchResult(r, request.lat(), request.lng())).toList();
        } catch (Exception e) {
            log.error("Full-text search error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Recherche alternative par activité exacte (fallback si full-text échoue).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<SearchResultDto> searchByActivity(String activityKeyword, SearchRequest request, int limit) {
        int radius = request.radiusMeters() != null ? request.radiusMeters() : 5000;

        String sql = """
            SELECT
                %s,
                venue.distance_meters,
                0.0 AS rank
            FROM programs p
            INNER JOIN user_activities ua  ON p.user_activity_id = ua.id
            INNER JOIN users u             ON ua.user_id          = u.id
            INNER JOIN activities a        ON ua.activity_id      = a.id
            INNER JOIN categories cat      ON a.category_id       = cat.id
            %s
            WHERE
                p.status = 'ACTIVE'
                AND p.is_public = true
                AND LOWER(a.name) LIKE LOWER(?)
                AND %s
            ORDER BY venue.distance_meters ASC NULLS LAST, p.id
            LIMIT ?
            """.formatted(PROGRAM_SELECT, VENUE_JOIN, VENUE_WITHIN_RADIUS);

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                sql,
                request.lng(), request.lat(),
                "%" + activityKeyword + "%",
                radius,
                limit
            );
            return rows.stream().map(r -> mapRowToSearchResult(r, request.lat(), request.lng())).toList();
        } catch (Exception e) {
            log.error("Activity search error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Recherche déterministe via la taxonomie d'activités canonique EN/DE/FR :
     * matche les programmes dont l'activité liée porte, dans son nom ou sa
     * description (langue de stockage d'origine), l'un des libellés multilingues
     * résolus par {@link ActivityTaxonomy}. Garantit le cross-lingue sur les
     * activités connues indépendamment de la qualité des embeddings.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<SearchResultDto> searchByTaxonomyLabels(Set<String> labels, SearchRequest request, int limit) {
        if (labels == null || labels.isEmpty()) return List.of();
        int radius = request.radiusMeters() != null ? request.radiusMeters() : 5000;

        String labelConditions = labels.stream()
            .map(l -> "(LOWER(a.name) LIKE ? OR LOWER(a.description) LIKE ?)")
            .collect(Collectors.joining(" OR "));

        String sql = """
            SELECT
                %s,
                venue.distance_meters,
                1.0 AS rank
            FROM programs p
            INNER JOIN user_activities ua  ON p.user_activity_id = ua.id
            INNER JOIN users u             ON ua.user_id          = u.id
            INNER JOIN activities a        ON ua.activity_id      = a.id
            INNER JOIN categories cat      ON a.category_id       = cat.id
            %s
            WHERE
                p.status = 'ACTIVE'
                AND p.is_public = true
                AND (%s)
                AND %s
            ORDER BY venue.distance_meters ASC NULLS LAST, p.id
            LIMIT ?
            """.formatted(PROGRAM_SELECT, VENUE_JOIN, labelConditions, VENUE_WITHIN_RADIUS);

        // L'ordre suit le texte SQL : la jointure latérale précède le WHERE.
        List<Object> params = new ArrayList<>();
        params.add(request.lng());
        params.add(request.lat());
        for (String label : labels) {
            String pattern = "%" + label.toLowerCase() + "%";
            params.add(pattern);
            params.add(pattern);
        }
        params.add(radius);
        params.add(limit);

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
            return rows.stream().map(r -> mapRowToSearchResult(r, request.lat(), request.lng())).toList();
        } catch (Exception e) {
            log.error("Taxonomy search error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private String prepareTsQuery(String keywords) {
        if (keywords == null || keywords.trim().isEmpty()) return "";
        String[] words = keywords.trim().toLowerCase()
            .replaceAll("[^a-zàâäéèêëïîôùûüÿœæç\\s]", " ")
            .split("\\s+");
        return String.join(" | ", words);
    }

    private SearchResultDto mapRowToSearchResult(Map<String, Object> row, double reqLat, double reqLng) {
        boolean isOnline = false;
        if (row.get("last_active_at") != null) {
            Instant lastActive = ((java.sql.Timestamp) row.get("last_active_at")).toInstant();
            isOnline = lastActive.isAfter(Instant.now().minusSeconds(300));
        }

        // Un programme à distance n'a pas de lieu : ni coordonnée, ni distance.
        // La jointure latérale peut malgré tout avoir trouvé une séance
        // localisée — un programme HYBRID mal saisi, par exemple — et c'est ici
        // qu'on tranche, en un seul endroit pour les trois requêtes.
        // Le test de nullité n'est pas décoratif : location_type est nullable en
        // base, et Set.of(...).contains(null) lève une NullPointerException — que
        // le catch de l'appelant transformerait en « aucun résultat », c'est-à-dire
        // en un défaut indiscernable d'une recherche légitimement vide.
        String locationType = (String) row.get("location_type");
        boolean remote = locationType != null && REMOTE_LOCATION_TYPES.contains(locationType);

        Double distanceMeters = !remote && row.get("distance_meters") != null
            ? ((Number) row.get("distance_meters")).doubleValue() : null;
        Double lat = !remote && row.get("lat") != null
            ? ((Number) row.get("lat")).doubleValue() : null;
        Double lng = !remote && row.get("lng") != null
            ? ((Number) row.get("lng")).doubleValue() : null;
        Float rank = row.get("rank") != null
            ? ((Number) row.get("rank")).floatValue() : 0f;
        Float avgScore = row.get("average_score") != null
            ? ((Number) row.get("average_score")).floatValue() : null;
        Integer reviewCount = row.get("review_count") != null
            ? ((Number) row.get("review_count")).intValue() : 0;
        Integer enrolledCount = row.get("enrolled_count") != null
            ? ((Number) row.get("enrolled_count")).intValue() : 0;

        Instant createdAt = row.get("created_at") != null
            ? ((java.sql.Timestamp) row.get("created_at")).toInstant() : null;
        Instant updatedAt = row.get("updated_at") != null
            ? ((java.sql.Timestamp) row.get("updated_at")).toInstant() : null;

        UUID userActivityId = toUuid(row.get("user_activity_id"));
        UUID categoryId     = toUuid(row.get("category_id"));
        UUID organizerId    = toUuid(row.get("user_id"));

        return new SearchResultDto(
            "program",
            toUuid(row.get("id")),
            (String) row.get("title"),
            (String) row.get("description"),
            (String) row.get("avatar_url"),   // organizerAvatarUrl dans le champ avatarUrl hérité
            lat,
            lng,
            distanceMeters,
            rank,
            (String) row.get("activity_name"),
            (String) row.get("level"),
            (String) row.get("format"),
            isOnline,
            (String) row.get("verification_status"),
            // champs program enrichis
            userActivityId,
            categoryId,
            (String) row.get("category_name"),
            organizerId,
            (String) row.get("display_name"),
            (String) row.get("avatar_url"),
            (String) row.get("thumbnail_url"),
            avgScore,
            reviewCount,
            enrolledCount,
            (String) row.get("status"),
            (String) row.get("location_type"),
            null,   // city : non dénormalisé en DB, nullable
            createdAt,
            updatedAt,
            null, null, null // startsAt/endsAt/maxParticipants : spécifiques aux résultats "slot"
        );
    }

    private static UUID toUuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }
}
