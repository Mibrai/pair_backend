package org.program.pair.domain.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Point;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.dto.SearchResultDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ProgramRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FullTextSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final ProgramRepository programRepository;

    /**
     * Recherche full-text dans les programmes avec filtres géographiques
     */
    public List<SearchResultDto> searchPrograms(String keywords, SearchRequest request, int limit) {
        // Préparer la query tsquery pour PostgreSQL full-text search
        String tsQuery = prepareTsQuery(keywords);

        // Rayon par défaut 5km
        int radius = request.radiusMeters() != null ? request.radiusMeters() : 5000;

        String sql = """
            SELECT
                p.id,
                p.title,
                p.description,
                u.id as user_id,
                u.display_name,
                u.avatar_url,
                u.verification_status,
                u.last_active_at,
                ST_Y(u.location) as lat,
                ST_X(u.location) as lng,
                ST_Distance(
                    u.location::geography,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                ) as distance_meters,
                ts_rank(p.search_vector, to_tsquery('french', ?)) as rank,
                a.name as activity_name,
                ua.level,
                ua.format
            FROM programs p
            INNER JOIN user_activities ua ON p.user_activity_id = ua.id
            INNER JOIN users u ON ua.user_id = u.id
            INNER JOIN activities a ON ua.activity_id = a.id
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
            """;

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                sql,
                request.lng(), request.lat(), tsQuery,
                tsQuery,
                request.lng(), request.lat(), radius,
                limit
            );

            return rows.stream()
                .map(this::mapRowToSearchResult)
                .toList();

        } catch (Exception e) {
            log.error("Full-text search error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Recherche alternative par activité exacte (fallback si full-text échoue)
     */
    public List<SearchResultDto> searchByActivity(String activityKeyword, SearchRequest request, int limit) {
        int radius = request.radiusMeters() != null ? request.radiusMeters() : 5000;

        String sql = """
            SELECT
                p.id,
                p.title,
                p.description,
                u.id as user_id,
                u.display_name,
                u.avatar_url,
                u.verification_status,
                u.last_active_at,
                ST_Y(u.location) as lat,
                ST_X(u.location) as lng,
                ST_Distance(
                    u.location::geography,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                ) as distance_meters,
                a.name as activity_name,
                ua.level,
                ua.format
            FROM programs p
            INNER JOIN user_activities ua ON p.user_activity_id = ua.id
            INNER JOIN users u ON ua.user_id = u.id
            INNER JOIN activities a ON ua.activity_id = a.id
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
            """;

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                sql,
                request.lng(), request.lat(),
                "%" + activityKeyword + "%",
                request.lng(), request.lat(), radius,
                limit
            );

            return rows.stream()
                .map(this::mapRowToSearchResult)
                .toList();

        } catch (Exception e) {
            log.error("Activity search error: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Prépare une requête tsquery pour PostgreSQL
     * Nettoie et transforme les mots-clés en format accepté
     */
    private String prepareTsQuery(String keywords) {
        if (keywords == null || keywords.trim().isEmpty()) {
            return "";
        }

        // Nettoyer et séparer les mots
        String[] words = keywords.trim().toLowerCase()
            .replaceAll("[^a-zàâäéèêëïîôùûüÿœæç\\s]", " ")
            .split("\\s+");

        // Joindre avec l'opérateur OR pour plus de résultats
        return String.join(" | ", words);
    }

    private SearchResultDto mapRowToSearchResult(Map<String, Object> row) {
        boolean isOnline = false;
        if (row.get("last_active_at") != null) {
            Instant lastActive = ((java.sql.Timestamp) row.get("last_active_at")).toInstant();
            isOnline = lastActive.isAfter(Instant.now().minusSeconds(300));
        }

        Double distanceMeters = row.get("distance_meters") != null
            ? ((Number) row.get("distance_meters")).doubleValue()
            : null;

        Float rank = row.get("rank") != null
            ? ((Number) row.get("rank")).floatValue()
            : 0f;

        return new SearchResultDto(
            "program",
            (java.util.UUID) row.get("id"),
            (String) row.get("title"),
            (String) row.get("description"),
            (String) row.get("avatar_url"),
            row.get("lat") != null ? ((Number) row.get("lat")).doubleValue() : null,
            row.get("lng") != null ? ((Number) row.get("lng")).doubleValue() : null,
            distanceMeters,
            rank,
            (String) row.get("activity_name"),
            (String) row.get("level"),
            (String) row.get("format"),
            isOnline,
            (String) row.get("verification_status")
        );
    }
}
