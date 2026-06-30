package org.program.pair.domain.indexation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.search.EmbeddingService;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
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
    private final EmbeddingService embeddingService;
    private final ProgramRepository programRepository;
    private final ActivityRepository activityRepository;

    /**
     * Update search vector for a single program (async)
     */
    @Async("indexationExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgramSearchVector(UUID programId) {
        try {
            jdbcTemplate.update("""
                UPDATE programs
                SET search_vector =
                    setweight(to_tsvector('french', coalesce(title, '')), 'A') ||
                    setweight(to_tsvector('french', coalesce(description, '')), 'B')
                WHERE id = ?
                """, programId);
            log.debug("Updated tsvector for program {}", programId);

            if (embeddingService.isConfigured()) {
                programRepository.findById(programId).ifPresent(p -> {
                    String text = buildProgramText(p);
                    float[] embedding = embeddingService.generateEmbedding(text);
                    if (embedding != null) {
                        jdbcTemplate.update(
                            "UPDATE programs SET embedding = CAST(? AS vector) WHERE id = ?",
                            embeddingService.toVectorString(embedding), programId);
                        log.debug("Updated embedding for program {}", programId);
                    }
                });
            }

        } catch (Exception e) {
            log.error("Error updating index for program {}: {}", programId, e.getMessage());
        }
    }

    /**
     * Update search vector for a single activity (async)
     */
    @Async("indexationExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateActivitySearchVector(UUID activityId) {
        try {
            jdbcTemplate.update("""
                UPDATE activities
                SET search_vector =
                    setweight(to_tsvector('french', coalesce(name, '')), 'A') ||
                    setweight(to_tsvector('french', coalesce(description, '')), 'B')
                WHERE id = ?
                """, activityId);
            log.debug("Updated tsvector for activity {}", activityId);

            if (embeddingService.isConfigured()) {
                activityRepository.findById(activityId).ifPresent(a -> {
                    String text = a.getName() + " " + (a.getDescription() != null ? a.getDescription() : "");
                    float[] embedding = embeddingService.generateEmbedding(text);
                    if (embedding != null) {
                        jdbcTemplate.update(
                            "UPDATE activities SET embedding = CAST(? AS vector) WHERE id = ?",
                            embeddingService.toVectorString(embedding), activityId);
                        log.debug("Updated embedding for activity {}", activityId);
                    }
                });
            }

        } catch (Exception e) {
            log.error("Error updating index for activity {}: {}", activityId, e.getMessage());
        }
    }

    private String buildProgramText(Program p) {
        StringBuilder sb = new StringBuilder(p.getTitle()).append(". ");
        if (p.getDescription() != null) sb.append(p.getDescription()).append(". ");
        if (p.getUserActivity() != null) {
            sb.append(p.getUserActivity().getActivity().getName()).append(". ");
            if (p.getUserActivity().getCustomDescription() != null) {
                sb.append(p.getUserActivity().getCustomDescription());
            }
        }
        return sb.toString();
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
