package org.program.pair.domain.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Système de notifications in-app, email et push")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {

    private final NotificationService notificationService;
    private final DeviceTokenService deviceTokenService;

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

    @PostMapping("/devices")
    @Operation(summary = "Enregistrer device token pour push",
        description = "Le champ locale fixe la langue des textes push de cet appareil. "
            + "Absent, l'Accept-Language de cette requête fait foi — c'est l'appareil "
            + "lui-même qui appelle, son en-tête dit sa langue. Sans l'un ni l'autre, "
            + "français.")
    public ResponseEntity<DeviceTokenDto> registerDevice(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @Valid @RequestBody RegisterDeviceRequest request) {

        DeviceToken token = deviceTokenService.registerToken(
            currentUser.getId(),
            request.getToken(),
            request.getPlatform(),
            request.getDeviceName(),
            resolveDeviceLocale(request.getLocale(), acceptLanguage)
        );

        return ResponseEntity.ok(DeviceTokenDto.fromEntity(token));
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
