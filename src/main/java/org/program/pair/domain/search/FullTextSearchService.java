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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FullTextSearchService {

    private final JdbcTemplate jdbcTemplate;

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
            ST_Y(u.location) AS lat,
            ST_X(u.location) AS lng,
            a.id    AS activity_id,
            a.name  AS activity_name,
            cat.id  AS category_id,
            cat.name AS category_name,
            (SELECT pm.url
               FROM program_media pm
              WHERE pm.program_id = p.id AND pm.media_type = 'IMAGE'
              ORDER BY pm.sort_order ASC
              LIMIT 1)                     AS thumbnail_url,
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
     * Recherche full-text dans les programmes avec filtres géographiques.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<SearchResultDto> searchPrograms(String keywords, SearchRequest request, int limit) {
        String tsQuery = prepareTsQuery(keywords);
        int radius = request.radiusMeters() != null ? request.radiusMeters() : 5000;

        String sql = """
            SELECT
                %s,
                ST_Distance(
                    u.location::geography,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                ) AS distance_meters,
                ts_rank(p.search_vector, to_tsquery('french', ?)) AS rank
            FROM programs p
            INNER JOIN user_activities ua  ON p.user_activity_id = ua.id
            INNER JOIN users u             ON ua.user_id          = u.id
            INNER JOIN activities a        ON ua.activity_id      = a.id
            INNER JOIN categories cat      ON a.category_id       = cat.id
            WHERE
                p.status = 'ACTIVE'
                AND p.is_public = true
                AND p.search_vector @@ to_tsquery('french', ?)
                AND u.location IS NOT NULL
                AND ST_DWithin(
                    u.location::geography,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    ?
                )
            ORDER BY rank DESC, distance_meters ASC
            LIMIT ?
            """.formatted(PROGRAM_SELECT);

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                sql,
                request.lng(), request.lat(), tsQuery,
                tsQuery,
                request.lng(), request.lat(), radius,
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
                ST_Distance(
                    u.location::geography,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                ) AS distance_meters,
                0.0 AS rank
            FROM programs p
            INNER JOIN user_activities ua  ON p.user_activity_id = ua.id
            INNER JOIN users u             ON ua.user_id          = u.id
            INNER JOIN activities a        ON ua.activity_id      = a.id
            INNER JOIN categories cat      ON a.category_id       = cat.id
            WHERE
                p.status = 'ACTIVE'
                AND p.is_public = true
                AND LOWER(a.name) LIKE LOWER(?)
                AND u.location IS NOT NULL
                AND ST_DWithin(
                    u.location::geography,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    ?
                )
            ORDER BY distance_meters ASC
            LIMIT ?
            """.formatted(PROGRAM_SELECT);

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                sql,
                request.lng(), request.lat(),
                "%" + activityKeyword + "%",
                request.lng(), request.lat(), radius,
                limit
            );
            return rows.stream().map(r -> mapRowToSearchResult(r, request.lat(), request.lng())).toList();
        } catch (Exception e) {
            log.error("Activity search error: {}", e.getMessage(), e);
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

        Double distanceMeters = row.get("distance_meters") != null
            ? ((Number) row.get("distance_meters")).doubleValue() : null;
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
            row.get("lat") != null ? ((Number) row.get("lat")).doubleValue() : null,
            row.get("lng") != null ? ((Number) row.get("lng")).doubleValue() : null,
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
            updatedAt
        );
    }

    private static UUID toUuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }
}
