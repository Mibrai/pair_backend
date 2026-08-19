package org.program.pair.domain.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.program.pair.domain.notification.dto.UpdateQuietHoursRequest;
import org.program.pair.domain.notification.dto.QuietHoursDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.notification.dto.DeviceTokenDto;
import org.program.pair.domain.notification.dto.NotificationDto;
import org.program.pair.domain.notification.dto.NotificationPrefDto;
import org.program.pair.domain.notification.dto.RegisterDeviceRequest;
import org.program.pair.domain.notification.dto.UpdatePreferenceRequest;
import org.program.pair.config.LocaleConfig;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Système de notifications in-app, email et push")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class NotificationController {

    private final NotificationService notificationService;
    private final DeviceTokenService deviceTokenService;
    private final QuietHoursService quietHoursService;

    @GetMapping
    @Operation(summary = "Mes notifications", description = "Liste paginée des notifications in-app")
    public Page<NotificationDto> getNotifications(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return notificationService
            .getNotifications(currentUser.getId(), PageRequest.of(page, Math.min(size, 50)))
            .map(NotificationDto::fromEntity);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Nombre de non lues", description = "Compte des notifications non lues")
    public ResponseEntity<Map<String, Long>> getUnreadCount(
            @AuthenticationPrincipal UserPrincipal currentUser) {

        long count = notificationService.getUnreadCount(currentUser.getId());
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "Marquer comme lue")
    public ResponseEntity<Void> markAsRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        notificationService.markAsRead(id, currentUser.getId());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/read-all")
    @Operation(summary = "Marquer toutes comme lues")
    public ResponseEntity<Map<String, Integer>> markAllAsRead(
            @AuthenticationPrincipal UserPrincipal currentUser) {

        int count = notificationService.markAllAsRead(currentUser.getId());
        return ResponseEntity.ok(Map.of("markedCount", count));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Supprimer une notification")
    public ResponseEntity<Void> deleteNotification(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        notificationService.deleteNotification(id, currentUser.getId());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/preferences")
    @Operation(summary = "Mes préférences de notification")
    public ResponseEntity<List<NotificationPrefDto>> getPreferences(
            @AuthenticationPrincipal UserPrincipal currentUser) {

        List<NotificationPrefDto> prefs = notificationService.getUserPreferences(currentUser.getId())
            .stream()
            .map(NotificationPrefDto::fromEntity)
            .toList();
        return ResponseEntity.ok(prefs);
    }

    @PutMapping("/preferences")
    @Operation(summary = "Mettre à jour les préférences")
    public ResponseEntity<NotificationPrefDto> updatePreference(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody UpdatePreferenceRequest request) {

        NotificationPref pref = notificationService.updatePreference(
            currentUser.getId(),
            request.getType(),
            request.getEmailEnabled(),
            request.getPushEnabled(),
            request.getFrequency()
        );

        return ResponseEntity.ok(NotificationPrefDto.fromEntity(pref));
    }

    @GetMapping("/quiet-hours")
    @Operation(summary = "Mes heures de silence.",
        description = "Nulles si aucun silence n'est demandé. Les heures sont pleines et "
            + "locales : le fuseau retenu est celui de l'appareil qui reçoit, relevé à "
            + "l'enregistrement du jeton — deux appareils dans deux fuseaux ne se taisent "
            + "donc pas au même moment, ce qui est le comportement voulu.")
    public QuietHoursDto getQuietHours(@AuthenticationPrincipal UserPrincipal currentUser) {
        return quietHoursService.get(currentUser.getId());
    }

    @PutMapping("/quiet-hours")
    @Operation(summary = "Règle ou retire les heures de silence.",
        description = "start et end vont ensemble : deux valeurs nulles retirent le "
            + "silence, une seule est refusée. La fenêtre peut traverser minuit — « 22 → 7 » "
            + "décrit une nuit, et c'est le réglage courant.\n\n"
            + "Le silence coupe la notification push, jamais la notification elle-même : "
            + "elle est écrite et attend au réveil. Les faits qui rendent un déplacement "
            + "inutile passent outre — annulation d'une séance ou d'un programme, "
            + "changement d'horaire — ainsi que le rappel de séance, qui part deux heures "
            + "avant quelque chose qu'on a choisi de rejoindre.")
    public QuietHoursDto updateQuietHours(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody UpdateQuietHoursRequest request) {
        return quietHoursService.update(currentUser.getId(), request.start(), request.end());
    }

    @PostMapping("/devices")
    @Operation(summary = "Enregistrer device token pour push",
        description = "Le champ locale fixe la langue des textes push de cet appareil. "
            + "Absent, l'Accept-Language de cette requête fait foi — c'est l'appareil "
            + "lui-même qui appelle, son en-tête dit sa langue. Sans l'un ni l'autre, "
            + "français. Le champ timezone (étiquette IANA) fixe le fuseau dans lequel "
            + "les heures des textes push Android sont composées ; absent ou non reconnu, "
            + "le fuseau de référence du serveur fait foi. La réponse renvoie les deux "
            + "valeurs effectivement retenues, ce qui rend un repli visible.")
    public ResponseEntity<DeviceTokenDto> registerDevice(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @Valid @RequestBody RegisterDeviceRequest request) {

        DeviceToken token = deviceTokenService.registerToken(
            currentUser.getId(),
            request.getToken(),
            request.getPlatform(),
            request.getDeviceName(),
            resolveDeviceLocale(request.getLocale(), acceptLanguage),
            resolveDeviceTimezone(request.getTimezone())
        );

        return ResponseEntity.ok(DeviceTokenDto.fromEntity(token));
    }

    /**
     * Fuseau à persister pour l'appareil, ou {@code null}.
     *
     * <p><b>Une étiquette qu'on ne reconnaît pas n'est pas une erreur de
     * requête.</b> Enregistrer un jeton est ce qui permet à un appareil de
     * recevoir quoi que ce soit : refuser l'enregistrement entier pour un fuseau
     * mal orthographié coûterait toutes les notifications de cet appareil, pour
     * une heure d'affichage. On la laisse tomber, on la journalise, et le
     * formatage retombe sur le fuseau de référence — le comportement d'avant ce
     * champ.
     *
     * <p>La validation est faite ici et non à l'envoi : {@code ZoneId.of} lève
     * sur une étiquette inconnue, et une valeur illisible en base ferait échouer
     * la composition d'une push des mois plus tard, loin de sa cause.
     */
    private static String resolveDeviceTimezone(String declared) {
        if (declared == null || declared.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(declared.strip()).getId();
        } catch (DateTimeException e) {
            log.warn("Device registered with an unrecognised timezone '{}' — falling back "
                + "to the server reference zone", declared);
            return null;
        }
    }

    /**
     * Langue à persister pour l'appareil : le champ explicite d'abord, sinon
     * l'en-tête de la requête d'enregistrement — qui vient de l'appareil
     * lui-même —, sinon rien. On stocke la langue <b>servie</b> ({@code fr},
     * {@code en}, {@code de}), pas l'étiquette reçue : c'est elle que le moment
     * de l'envoi doit relire sans re-négocier.
     */
    private static String resolveDeviceLocale(String explicit, String acceptLanguage) {
        Locale resolved = LocaleConfig.closestSupported(
            explicit != null && !explicit.isBlank() ? explicit : acceptLanguage);
        return resolved != null ? resolved.toLanguageTag() : null;
    }

    @DeleteMapping("/devices/{token}")
    @Operation(summary = "Supprimer device token")
    public ResponseEntity<Void> unregisterDevice(@PathVariable String token) {
        deviceTokenService.unregisterToken(token);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/devices")
    @Operation(summary = "Mes device tokens")
    public ResponseEntity<List<DeviceTokenDto>> getMyDevices(
            @AuthenticationPrincipal UserPrincipal currentUser) {

        List<DeviceTokenDto> tokens = deviceTokenService.getUserTokens(currentUser.getId())
            .stream()
            .map(DeviceTokenDto::fromEntity)
            .toList();
        return ResponseEntity.ok(tokens);
    }
}
