package org.program.pair.domain.watch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ce que rend {@code POST /watches/{id}/arrival} : la veille, et le code en clair.
 *
 * <p><b>Le code n'apparaît qu'ici, une seule fois.</b> Il n'est stocké nulle part
 * en clair et ne pourra pas être relu : c'est ce qui rend vraie la phrase « connu
 * de lui seul ». Le client doit l'afficher à l'utilisateur pour qu'il le mémorise,
 * et ne pas le conserver au-delà.
 */
@Schema(description = "La veille passée sur place, et le code de retour en clair (une seule fois).")
public record ArrivalResponse(

    WatchDto watch,

    @Schema(description = "Le code de retour, en clair. Rendu une seule fois ; à mémoriser, "
        + "jamais reservi par le serveur.")
    String returnCode
) {}
