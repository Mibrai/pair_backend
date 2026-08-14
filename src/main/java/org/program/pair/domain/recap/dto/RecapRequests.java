package org.program.pair.domain.recap.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Les corps de requête des routes de contribution, en camelCase comme le reste
 * de l'API.
 */
public final class RecapRequests {

    private RecapRequests() {}

    /**
     * Ambiances choisies. <b>Remplace</b> la contribution de l'appelant, elle
     * ne s'y ajoute pas : le client envoie l'ensemble de sa sélection à chaque
     * tap, et une liste vide vaut suppression.
     *
     * <p>{@code List<String>} et non {@code List<SlotVibe>} : une valeur hors
     * du vocabulaire doit ressortir en {@code 422 RECAP_INVALID_VIBES}, nommé
     * et traduisible. Typée, elle échouerait à la désérialisation et
     * produirait un {@code 400 INVALID_JSON} que le client ne saurait pas
     * transformer en phrase.
     */
    public record VibesRequest(
        @Schema(description = "Deux valeurs de SlotVibe au maximum. Vide = retirer ma contribution.")
        List<String> vibes
    ) {}

    /** Acceptation — ou retrait — d'apparaître nommé sur la carte. */
    public record ConsentRequest(
        @Schema(description = "Faux par défaut, et retirable à tout moment, y compris "
            + "après publication : la carte se régénère alors sans moi.")
        Boolean showIdentity
    ) {}

    /** Le mot de l'hôte, rendu tel quel sur une carte potentiellement publique. */
    public record HostNoteRequest(
        @Size(max = 400) String note
    ) {}

    /** Portée de la carte : {@code PRIVATE}, {@code PARTICIPANTS} ou {@code PUBLIC}. */
    public record VisibilityRequest(
        String visibility
    ) {}

    /**
     * Souvenir photo de l'appelant pour ce créneau.
     *
     * <p>L'URL est celle rendue par le chemin d'upload existant
     * ({@code POST /api/media/upload/image}) : il n'y a toujours qu'un seul
     * chemin d'upload, et celui-ci n'en est pas un — il ne fait que rattacher
     * un fichier déjà stocké à une présence confirmée.
     */
    public record MemoryPhotoRequest(
        @Size(max = 500) String photoUrl,

        @Schema(description = "La photo peut-elle figurer sur la carte ? Fausse ou absente, "
            + "elle reste privée. Distinct de showIdentity : partager une image du moment "
            + "et accepter d'être nommé sont deux décisions.")
        Boolean isPublic
    ) {}
}
