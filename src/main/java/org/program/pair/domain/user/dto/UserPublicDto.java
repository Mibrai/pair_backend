package org.program.pair.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record UserPublicDto(
    UUID id,
    String displayName,
    String bio,
    String avatarUrl,
    String verificationStatus,
    List<String> badgeCodes,
    List<UserActivitySummaryDto> activities,
    boolean isOnline,

    @Schema(description = "Nombre d'abonnés de type AUTHOR. Compte cette personne seule : "
        + "il n'agrège pas les abonnés de ses activités, et l'additionner avec eux ne "
        + "donnerait pas la portée d'une publication — la déduplication à l'émission rend "
        + "ce nombre plus petit que la somme. Nul quand le DTO est rendu hors contexte "
        + "d'abonnement, par exemple comme fiche d'identité dans une conversation.")
    Long subscriberCount,

    @Schema(description = "L'appelant suit-il cette personne ? Reste vrai pour un "
        + "abonnement en sourdine (MUTED) : la sourdine coupe l'émission, pas le lien. "
        + "Nul dans les mêmes cas que subscriberCount. Absent aussi sur GET /users/me, "
        + "qui rend un UserPrivateDto — on ne s'abonne pas à soi-même.")
    Boolean subscribed,

    @Schema(description = "Signal de fiabilité, ou null. Un libellé, jamais un chiffre : "
        + "renvoyer « 12 venues sur 15 inscriptions » laisserait n'importe quel client "
        + "afficher 80 %, puis classer les gens par ce nombre, puis en faire un filtre. "
        + "Une seule valeur existe, USUALLY_SHOWS_UP, et elle n'aura jamais de contraire "
        + "— l'absence de signal n'est pas un mauvais signal, c'est l'état de qui vient "
        + "d'arriver. Ne rien afficher quand il est nul.")
    String reliabilitySignal
) {

    /**
     * Fiche d'identité d'une personne, hors de tout contexte d'abonnement :
     * membre d'une conversation, par exemple.
     *
     * <p>Ni compteur ni état — {@code null} dit « non calculé ici », là où un
     * {@code 0} dirait « aucun abonné » et un {@code false} « pas abonné ». Les
     * calculer sur chaque membre d'un fil coûterait deux requêtes par personne,
     * pour un écran qui ne les affiche pas.
     */
    public static UserPublicDto identity(UUID id, String displayName, String bio,
                                         String avatarUrl, String verificationStatus) {
        return new UserPublicDto(id, displayName, bio, avatarUrl, verificationStatus,
            List.of(), List.of(), false, null, null, null);
    }
}
