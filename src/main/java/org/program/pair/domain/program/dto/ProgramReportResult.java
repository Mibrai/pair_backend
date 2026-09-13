package org.program.pair.domain.program.dto;

/**
 * Réponse de {@code POST /api/programs/{programId}/report}. Remplace une
 * {@code Map<String, String>} (P-BA-16, étape 6) : même JSON, {@code {"message": …}},
 * mais un schéma que la spec peut nommer.
 */
public record ProgramReportResult(String message) {}
