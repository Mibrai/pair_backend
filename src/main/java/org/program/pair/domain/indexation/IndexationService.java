package org.program.pair.domain.indexation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexationService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Update search vector for a single program (async)
     */
    @Async("indexationExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgramSearchVector(UUID programId) {
        try {
            String sql = """
                UPDATE programs
                SET search_vector =
                    setweight(to_tsvector('french', coalesce(title, '')), 'A') ||
                    setweight(to_tsvector('french', coalesce(description, '')), 'B')
                WHERE id = ?
                """;

            int updated = jdbcTemplate.update(sql, programId);
            log.debug("Updated search vector for program: {} (rows: {})", programId, updated);

        } catch (Exception e) {
            log.error("Error updating search vector for program: {}", programId, e);
        }
    }

    /**
     * Update search vector for a single activity (async)
     */
    @Async("indexationExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateActivitySearchVector(UUID activityId) {
        try {
            String sql = """
                UPDATE activities
                SET search_vector =
                    setweight(to_tsvector('french', coalesce(name, '')), 'A') ||
                    setweight(to_tsvector('french', coalesce(description, '')), 'B')
                WHERE id = ?
                """;

            int updated = jdbcTemplate.update(sql, activityId);
            log.debug("Updated search vector for activity: {} (rows: {})", activityId, updated);

        } catch (Exception e) {
            log.error("Error updating search vector for activity: {}", activityId, e);
        }
    }

    /**
     * Batch update all programs (for migrations)
     */
    @Transactional
    public int reindexAllPrograms() {
        log.info("Starting batch reindex of all programs");

        String sql = """
            UPDATE programs
            SET search_vector =
                setweight(to_tsvector('french', coalesce(title, '')), 'A') ||
                setweight(to_tsvector('french', coalesce(description, '')), 'B')
            WHERE search_vector IS NULL OR updated_at > NOW() - INTERVAL '1 hour'
            """;

        int updated = jdbcTemplate.update(sql);
        log.info("Batch reindex completed: {} programs updated", updated);

        return updated;
    }

    /**
     * Batch update all activities (for migrations)
     */
    @Transactional
    public int reindexAllActivities() {
        log.info("Starting batch reindex of all activities");

        String sql = """
            UPDATE activities
            SET search_vector =
                setweight(to_tsvector('french', coalesce(name, '')), 'A') ||
                setweight(to_tsvector('french', coalesce(description, '')), 'B')
            WHERE search_vector IS NULL
            """;

        int updated = jdbcTemplate.update(sql);
        log.info("Batch reindex completed: {} activities updated", updated);

        return updated;
    }

    /**
     * Get statistics about indexed content
     */
    public IndexationStats getStats() {
        String programsSql = "SELECT COUNT(*) FROM programs WHERE search_vector IS NOT NULL";
        String activitiesSql = "SELECT COUNT(*) FROM activities WHERE search_vector IS NOT NULL";

        Long indexedPrograms = jdbcTemplate.queryForObject(programsSql, Long.class);
        Long indexedActivities = jdbcTemplate.queryForObject(activitiesSql, Long.class);

        return new IndexationStats(
            indexedPrograms != null ? indexedPrograms : 0L,
            indexedActivities != null ? indexedActivities : 0L
        );
    }

    public record IndexationStats(long indexedPrograms, long indexedActivities) {}
}
