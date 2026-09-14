package org.program.pair.domain.visibility.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.visibility.VisibilityChannel;

import java.time.Instant;
import java.util.List;

/** Les corps de « Qui me voit » côté serveur. Des booléens, jamais des comptes. */
public final class VisibilityDtos {

    private VisibilityDtos() {
    }

    @Schema(description = "Résultat de la coupure. Un canal à cut=true ne laisse plus rien passer, "
        + "qu'il ait été ouvert ou non.")
    public record CutAllResponse(
        @Schema(description = "Instant de la coupure, UTC.") Instant cutAt,
        List<ChannelCut> channels
    ) {}

    public record ChannelCut(
        @Schema(description = "Nom du canal. De nouvelles valeurs peuvent apparaître ; aucune "
            + "ne sera retirée.")
        VisibilityChannel channel,
        @Schema(description = "Vrai quand plus rien ne passe par ce canal. La coupure est une "
            + "transaction unique : elle rend tout à true, ou échoue sans rien changer.")
        boolean cut
    ) {}

    @Schema(description = "L'état réel de chaque canal, et les dernières coupures.")
    public record VisibilityStateResponse(
        List<ChannelState> channels,
        @Schema(description = "Les dix dernières coupures, de la plus récente à la plus ancienne. "
            + "Le journal de « tout couper » : un événement de compte, rattaché à aucune veille.")
        List<Instant> recentCuts
    ) {}

    public record ChannelState(
        VisibilityChannel channel,
        @Schema(description = "Vrai quand quelque chose passe encore par ce canal : au moins une "
            + "affiche ouverte, un lien de veille ouvrable, un partage de position non échu, ou "
            + "l'un des trois réglages de présence (locationPublic, showLocation, showOnMap).")
        boolean open
    ) {}
}
