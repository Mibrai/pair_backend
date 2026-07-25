package org.program.pair.domain.notification.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.program.pair.domain.notification.Notification;
import org.program.pair.domain.notification.NotificationChannel;
import org.program.pair.domain.notification.NotificationType;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Il n'y a volontairement pas de title/message : le libellé affiché "
    + "est dérivé de `type` côté client. Les identifiants métier utiles au deep-link "
    + "(scheduleId, programId, ...) sont dans `payload`.")
public class NotificationDto {

    // Types pour lesquels le payload peut porter l'instant d'une séance à venir.
    // Aucun code applicatif ne crée encore ces notifications (V12/V13/V27 seed
    // uniquement) ; la clé "sessionAt" est la convention déjà utilisée par ce seed.
    private static final Set<NotificationType> SCHEDULE_RELATED_TYPES =
        EnumSet.of(NotificationType.PROGRAM_REMINDER, NotificationType.PROGRESSION_REMINDER);

    private UUID id;
    private NotificationType type;
    private NotificationChannel channel;
    // Map plutôt que JsonNode : un JsonNode sérialisé par certains chemins
    // Jackson (selon l'ObjectMapper effectivement utilisé pour la réponse
    // HTTP) peut être introspecté comme un bean au lieu d'être traité comme
    // un arbre JSON, produisant un dump de ses accesseurs internes
    // (isArray/isBigDecimal/...) plutôt que le contenu réel. Une Map se
    // sérialise toujours comme un objet JSON standard, sans ambiguïté.
    @Schema(description = "Identifiants métier pour le deep-link (ex. scheduleId, "
        + "programId), dépendant de `type`. Peut être absent.")
    private Map<String, Object> payload;
    @Schema(description = "Nom de propriété réel : `isRead` (pas `read`).")
    private Boolean isRead;
    @Schema(description = "Nom de propriété réel : `sentAt` (pas `createdAt`).")
    private Instant sentAt;
    private Instant readAt;
    @Schema(description = "Dérivé du payload pour PROGRAM_REMINDER/PROGRESSION_REMINDER "
        + "uniquement ; null sinon, jamais fabriqué artificiellement.")
    private Instant scheduledAt;

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    public static NotificationDto fromEntity(Notification notification) {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> payloadMap = null;

        if (notification.getPayload() != null) {
            try {
                payloadMap = mapper.readValue(notification.getPayload(), PAYLOAD_TYPE);
            } catch (Exception e) {
                // Ignore parse errors
            }
        }

        return NotificationDto.builder()
            .id(notification.getId())
            .type(notification.getType())
            .channel(notification.getChannel())
            .payload(payloadMap)
            .isRead(notification.getIsRead())
            .sentAt(notification.getSentAt())
            .readAt(notification.getReadAt())
            .scheduledAt(extractScheduledAt(notification.getType(), payloadMap))
            .build();
    }

    /**
     * Best-effort : ne fabrique jamais scheduledAt, le laisse à null si le
     * payload ne porte pas déjà "sessionAt" en ISO-8601. Le client retombe sur
     * son heuristique textuelle dans ce cas.
     */
    private static Instant extractScheduledAt(NotificationType type, Map<String, Object> payloadMap) {
        if (payloadMap == null || !SCHEDULE_RELATED_TYPES.contains(type)) {
            return null;
        }
        Object sessionAt = payloadMap.get("sessionAt");
        if (!(sessionAt instanceof String text)) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
