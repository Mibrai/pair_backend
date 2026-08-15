package org.program.pair.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.program.pair.domain.notification.DevicePlatform;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterDeviceRequest {

    @NotBlank(message = "Token est requis")
    private String token;

    @NotNull(message = "Platform est requise")
    private DevicePlatform platform;

    private String deviceName;

    @Schema(description = "Langue des textes push envoyés à cet appareil, étiquette BCP 47 "
        + "(ex. \"de\", \"en-GB\"). Ramenée à la langue servie la plus proche (fr, en, de) ; "
        + "hors des trois, anglais. Absente, l'Accept-Language de cette requête fait foi ; "
        + "sans lui non plus, l'appareil reçoit le français. Ré-enregistrer le token met la "
        + "langue à jour — à faire quand l'utilisateur change la langue de l'app.")
    @Size(max = 35)
    private String locale;

    @Schema(description = "Fuseau de cet appareil, étiquette IANA (ex. \"Europe/Paris\"). "
        + "Jamais un décalage : \"+02:00\" décrit un instant et non une règle, et un rappel "
        + "émis fin octobre pour une séance de novembre serait décalé d'une heure. Sert à "
        + "composer les heures des textes push Android, qu'aucune extension ne réécrit sur "
        + "l'appareil. Absente ou non reconnue, le fuseau de référence du serveur fait foi. "
        + "Ré-enregistrer le jeton met le fuseau à jour — à faire quand l'appareil change de "
        + "fuseau, donc quand on voyage.",
        example = "Europe/Paris")
    @Size(max = 64)
    private String timezone;
}
