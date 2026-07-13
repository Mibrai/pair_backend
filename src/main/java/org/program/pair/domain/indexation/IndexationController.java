package org.program.pair.domain.indexation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/indexation")
@RequiredArgsConstructor
@Slf4j
public class IndexationController {

    private final IndexationService indexationService;

    /**
     * Get indexation statistics
     */
    @GetMapping("/stats")
    public IndexationService.IndexationStats getStats() {
        return indexationService.getStats();
    }

    /**
     * Trigger full reindex of all programs
     * Admin endpoint - should be protected with proper authorization in production
     */
    @PostMapping("/reindex/programs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> reindexPrograms() {
        log.info("Manual reindex of programs triggered");
        int updated = indexationService.reindexAllPrograms();
        return Map.of(
            "status", "completed",
            "programsUpdated", updated
        );
    }

    /**
     * Trigger full reindex of all activities
     * Admin endpoint - should be protected with proper authorization in production
     */
    @PostMapping("/reindex/activities")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> reindexActivities() {
        log.info("Manual reindex of activities triggered");
        int updated = indexationService.reindexAllActivities();
        return Map.of(
            "status", "completed",
            "activitiesUpdated", updated
        );
    }

    /**
     * Trigger full reindex of everything
     */
    @PostMapping("/reindex/all")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> reindexAll() {
        log.info("Full reindex triggered");
        int programs = indexationService.reindexAllPrograms();
        int activities = indexationService.reindexAllActivities();

        return Map.of(
            "status", "completed",
            "programsUpdated", programs,
            "activitiesUpdated", activities,
            "totalUpdated", programs + activities
        );
    }

    /**
     * Backfill embeddings for all existing programs and activities that don't
     * have one yet (embedding IS NULL). Idempotent: safe to call repeatedly,
     * only processes rows still missing an embedding. Requires OPENAI_API_KEY
     * to be configured (embedding.api-key) — otherwise a no-op.
     *
     * Run after deploying the multilingual embedding pipeline to backfill
     * pre-existing content:
     *   curl -X POST https://<host>/api/indexation/backfill-embeddings
     */
    @PostMapping("/backfill-embeddings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> backfillEmbeddings() {
        log.info("Embedding backfill triggered");
        int programs = indexationService.backfillProgramEmbeddings();
        int activities = indexationService.backfillActivityEmbeddings();

        return Map.of(
            "status", "completed",
            "programsUpdated", programs,
            "activitiesUpdated", activities,
            "totalUpdated", programs + activities
        );
    }
}
