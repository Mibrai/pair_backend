package org.program.pair.domain.invitation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Un lien d'invitation, à envoyer à une personne précise.")
public record InvitationLinkDto(

    @Schema(description = "Code d'invitation. Usage unique : c'est ce qui permet de "
        + "savoir laquelle de vos invitations a abouti.")
    String code,

    @Schema(description = "L'adresse à envoyer : la page publique du créneau, avec le "
        + "code en paramètre de suivi. Elle s'ouvre sans compte.")
    String url,

    /**
     * Ajouté le 12/09/2026, à la demande du chantier mobile.
     *
     * <p>Le DTO ne portait que le code et une adresse. L'application, qui doit
     * pouvoir composer elle-même le lien quand elle a déjà le jeton en cache,
     * n'avait aucun moyen de le relire ici : elle retombait sur {@code url}, qui
     * valait alors {@code …/i/{code}} — une adresse que rien ne servait, et qui
     * rendait 401 chez le destinataire. Le jeton est désormais dit.
     */
    @Schema(description = "Le jeton public du créneau, celui de /s/{token}.")
    String slotToken
) {}
