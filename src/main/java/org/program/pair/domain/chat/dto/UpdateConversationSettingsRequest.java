package org.program.pair.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Réglages d'une conversation, propres à l'appelant. Un champ "
    + "absent reste inchangé : les deux commandes vivent sur deux écrans différents, "
    + "et régler l'une ne doit pas remettre l'autre à sa valeur par défaut.")
public record UpdateConversationSettingsRequest(

    @Schema(description = "En sourdine. La sourdine coupe l'émission, pas la réception : "
        + "le message arrive, s'affiche dans le fil ouvert et compte dans le décompte "
        + "de ce fil — il ne déclenche plus de notification, et il sort du total de "
        + "/api/conversations/unread-count.")
    Boolean muted,

    @Schema(description = "Archivée. Le fil quitte GET /api/conversations et n'apparaît "
        + "plus que dans GET /api/conversations?archived=true. Un message reçu ne l'en "
        + "fait pas ressortir : archiver le fil dont on veut se débarrasser n'aurait "
        + "sinon aucun effet, puisque c'est justement celui qui reçoit.")
    Boolean archived
) {}
