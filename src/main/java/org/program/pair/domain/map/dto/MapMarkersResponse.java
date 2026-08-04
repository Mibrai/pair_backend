package org.program.pair.domain.map.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Marqueurs d'une zone rectangulaire, par couche.
 *
 * <p>{@code truncated} et {@code totalInBounds} reprennent la sémantique de
 * {@link MapActivitiesResponse}, avec une réserve propre à cette route et
 * énoncée ci-dessous.
 */
public record MapMarkersResponse(
    List<MapUserDto> users,
    List<MapActivityDto> activities,
    List<MapProgramDto> programs,

    @Schema(description = "Vrai si au moins une des trois couches a écarté des marqueurs "
        + "présents dans la zone, que ce soit par le paramètre limit ou par le plafond "
        + "interne d'agrégation des activités.")
    boolean truncated,

    @Schema(description = "Nombre de marqueurs dans la zone avant application de limit, "
        + "toutes couches confondues. Exact pour les personnes et les programmes. Pour "
        + "les activités, qui sont agrégées depuis les personnes sous un plafond interne, "
        + "c'est le nombre effectivement agrégé — donc un minorant quand truncated vaut "
        + "true. Ne jamais l'utiliser comme un total exact sans vérifier truncated.")
    int totalInBounds
) {}
