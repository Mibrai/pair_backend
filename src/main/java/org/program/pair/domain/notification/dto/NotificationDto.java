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
@Schema(description = "Il n'y a volontairement pas de title/message : le libellé affiché est "
    + "composé par le client depuis `type` + `payload`. C'est un choix, pas un manque — un "
    + "texte traduit à l'émission fige la langue de l'utilisateur au moment de l'envoi, et "
    + "un texte déjà en base ne peut plus être retraduit. En échange, le serveur garantit "
    + "que `payload` porte de quoi écrire la phrase (`programTitle`, `activityName`) en plus "
    + "des identifiants de deep-link.")
public class NotificationDto {

    // Types dont le payload porte l'instant d'une séance *à venir* — c'est ce que
    // le client met en avant (teinte, compte à rebours, remontée en tête de liste).
    // La clé lue est "sessionAt", convention du seed V12/V13/V27 et désormais
    // produite par NotificationPayload.ofSchedule.
    //
    // SLOT_CANCELLED et ATTENDANCE_PROMPT portent aussi un sessionAt mais n'entrent
    // pas ici : l'un désigne une séance annulée, l'autre une séance passée. Faire
    // un compte à rebours vers l'une ou l'autre serait faux.
    private static final Set<NotificationType> SCHEDULE_RELATED_TYPES = EnumSet.of(
        NotificationType.PROGRAM_REMINDER,
        NotificationType.PROGRESSION_REMINDER,
        NotificationType.SLOT_JOINED,
        NotificationType.ACTIVITY_ALERT_MATCH);

    private UUID id;
    private NotificationType type;
    private NotificationChannel channel;
    // Map plutôt que JsonNode : un JsonNode sérialisé par certains chemins
    // Jackson (selon l'ObjectMapper effectivement utilisé pour la réponse
    // HTTP) peut être introspecté comme un bean au lieu d'être traité comme
    // un arbre JSON, produisant un dump de ses accesseurs internes
    // (isArray/isBigDecimal/...) plutôt que le contenu réel. Une Map se
    // sérialise toujours comme un objet JSON standard, sans ambiguïté.
    @Schema(description = "Contexte métier de la notification, dépendant de `type`. "
        + "Toute notification portant sur une séance ou un programme porte `programId`, "
        + "`programTitle`, `activityId`, `activityName`, `categoryId` et "
        + "`categoryColorRamp` ; celles portant sur une séance y ajoutent `scheduleId`, "
        + "`placeName` et `sessionAt`. `categoryColorRamp` est la même valeur que celle "
        + "servie par les DTO de la carte : teinter une notification et son pin depuis "
        + "ce champ donne la même couleur par construction. Une clé dont la valeur serait "
        + "nulle est absente, jamais présente à null. Voir NotificationPayload.")
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
    // Une seule instance : ObjectMapper est thread-safe une fois configuré, et en
    // construire un par notification faisait payer l'introspection Jackson à
    // chaque ligne d'une page de cinquante.
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    public static NotificationDto fromEntity(Notification notification) {
        Map<String, Object> payloadMap = null;

        if (notification.getPayload() != null) {
            try {
                payloadMap = PAYLOAD_MAPPER.readValue(notification.getPayload(), PAYLOAD_TYPE);
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
