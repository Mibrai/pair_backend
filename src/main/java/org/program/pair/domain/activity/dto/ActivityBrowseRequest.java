package org.program.pair.domain.activity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Paramètres de {@code GET /api/activities/browse}.
 *
 * <p>La convention de distance est celle demandée pour toute nouvelle route de
 * découverte : {@code radiusMeters}, en mètres, en lowerCamelCase.
 */
public record ActivityBrowseRequest(

    @NotNull(message = "Le paramètre 'lat' est requis.")
    Double lat,

    @NotNull(message = "Le paramètre 'lng' est requis.")
    Double lng,

    @Schema(description = "Rayon en mètres. Défaut 25000, borné entre 1 et 200000, "
        + "comme sur /map/activities.", defaultValue = "25000")
    Integer radiusMeters,

    @Schema(description = "Page indexée à 0.", defaultValue = "0")
    Integer page,

    @Schema(description = "Taille de page. Défaut 20, plafonnée à 100.", defaultValue = "20")
    Integer size,

    @Schema(description = "Catégories retenues. Vide ou absent : toutes.")
    List<UUID> categoryIds,

    @Schema(description = "Niveaux retenus (BEGINNER, INTERMEDIATE, ADVANCED, EXPERT, ANY). "
        + "Vide ou absent : tous.")
    List<String> activityLevels,

    @Schema(description = "Inclure les entrées expirées — datées, mais sans séance à venir.",
        defaultValue = "false")
    Boolean includeExpired,

    @Schema(description = "distance (défaut) ou nextSession. 'relevance' est accepté et "
        + "traité comme 'distance' : sans terme de recherche, la pertinence n'a pas de "
        + "sens sur cette route.",
        allowableValues = {"distance", "nextSession", "relevance"}, defaultValue = "distance")
    String sort,

    @Schema(description = "Inclure les 3 prochains programmes de chaque entrée. La liste "
        + "de l'Explorer n'en a pas besoin ; seule la page de détail les consomme.",
        defaultValue = "false")
    Boolean includePrograms
) {
    public int effectiveRadiusMeters() {
        return radiusMeters != null ? radiusMeters : 25_000;
    }

    public int effectivePage() {
        return page != null ? page : 0;
    }

    public int effectiveSize() {
        return size != null ? size : 20;
    }

    public boolean effectiveIncludeExpired() {
        return Boolean.TRUE.equals(includeExpired);
    }

    public boolean effectiveIncludePrograms() {
        return Boolean.TRUE.equals(includePrograms);
    }

    /** Vrai pour {@code sort=nextSession} ; tout le reste trie par distance. */
    public boolean sortByNextSession() {
        return "nextSession".equalsIgnoreCase(sort);
    }
}
