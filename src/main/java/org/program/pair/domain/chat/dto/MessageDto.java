package org.program.pair.domain.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record MessageDto(
    UUID id,
    UUID conversationId,
    UUID senderId,
    String senderName,
    String senderAvatarUrl,
    String content,
    String status,
    Instant sentAt,

    @Schema(description = "Position partagée ponctuellement, et l'instant où elle cesse "
        + "d'être servie. Les trois champs vont ensemble : ou bien ils sont tous les "
        + "trois renseignés, ou bien le message ne porte pas de position.\n\n"
        + "Un partage échu rend ces trois champs nuls, y compris sur un message qui en "
        + "portait un — le message reste dans le fil, sa position n'y est plus. Le client "
        + "n'a donc pas à comparer une échéance à l'heure courante pour savoir s'il doit "
        + "afficher le point : si les champs sont là, le point est valable. Il lui reste à "
        + "le faire disparaître de lui-même à l'échéance, pour un fil resté ouvert.")
    Double locationLat,
    Double locationLng,
    Instant locationExpiresAt
) {}
