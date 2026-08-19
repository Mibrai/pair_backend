package org.program.pair.domain.invitation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Un lien d'invitation, à envoyer à une personne précise.")
public record InvitationLinkDto(

    @Schema(description = "Code d'invitation. Usage unique : c'est ce qui permet de "
        + "savoir laquelle de vos invitations a abouti.")
    String code,

    String url
) {}
