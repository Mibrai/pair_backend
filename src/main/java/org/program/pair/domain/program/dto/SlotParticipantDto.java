package org.program.pair.domain.program.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.user.dto.UserPublicDto;

import java.time.Instant;
import java.util.UUID;

public record SlotParticipantDto(
    UUID participationId,
    UserPublicDto user,
    String status,
    String joinMessage,
    Instant createdAt,

    @Schema(description = "Où en est l'arrivée de cet inscrit. Jamais nul : un inscrit sans "
        + "veille porte NONE, comme un inscrit qui en a armé une sans déclarer son arrivée.")
    Arrival arrival
) {

    /**
     * L'arrivée d'un inscrit, telle que l'organisateur la voit.
     *
     * <p><b>{@code NONE} veut dire exactement la même chose pour quelqu'un qui n'a
     * pas armé de veille et pour quelqu'un qui en a armé une sans déclarer son
     * arrivée.</b> C'est la condition sans laquelle l'insigne de présence
     * deviendrait un détecteur : l'organisateur apprendrait qui se protège, ce que
     * personne n'a accepté de lui dire. Le type ne peut pas les distinguer, et
     * c'est voulu — la frontière est tenue par la forme, pas par la discipline de
     * qui écrira l'écran après nous.
     *
     * <p><b>Trois champs, et pas un de plus</b>, comme {@code PendingArrivalDto} :
     * ni retard, ni motif, ni durée. L'insigne dit « vue à cette séance », pas
     * comment. Et il ne sort pas d'ici : ce bloc n'a sa place dans aucun DTO
     * public — un compteur de présences validées sur un profil deviendrait une
     * réputation, donc une pression à valider, donc un organisateur qu'on ne
     * contrarie pas.
     */
    @Schema(description = "L'état d'arrivée d'un inscrit, vu par l'organisateur.")
    public record Arrival(

        @Schema(description = "NONE, CLAIMED (déclarée, en attente de validation) ou CONFIRMED.")
        String state,

        @Schema(description = "Quand l'inscrit a déclaré son arrivée. Null en NONE.")
        Instant claimedAt,

        @Schema(description = "Quand l'arrivée a été validée. Null hors CONFIRMED.")
        Instant confirmedAt
    ) {

        /** L'absence de veille, et l'absence de déclaration, rendent la même chose. */
        public static final Arrival NONE = new Arrival("NONE", null, null);
    }
}
