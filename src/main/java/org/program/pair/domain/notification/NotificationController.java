package org.program.pair.domain.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.notification.dto.NotificationDto;
import org.program.pair.domain.notification.dto.RegisterDeviceRequest;
import org.program.pair.domain.notification.dto.UpdatePreferenceRequest;
import org.program.pair.repository.DeviceTokenRepository;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
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
    public ResponseEntity<Page<NotificationDto>> getNotifications(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<NotificationDto> notifications = notificationService
            .getNotifications(currentUser.getId(), PageRequest.of(page, Math.min(size, 50)))
            .map(NotificationDto::fromEntity);

        return ResponseEntity.ok(notifications);
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
    public ResponseEntity<List<NotificationPref>> getPreferences(
            @AuthenticationPrincipal UserPrincipal currentUser) {

        List<NotificationPref> prefs = notificationService.getUserPreferences(currentUser.getId());
        return ResponseEntity.ok(prefs);
    }

    @PutMapping("/preferences")
    @Operation(summary = "Mettre à jour les préférences")
    public ResponseEntity<NotificationPref> updatePreference(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody UpdatePreferenceRequest request) {

        NotificationPref pref = notificationService.updatePreference(
            currentUser.getId(),
            request.getType(),
            request.getEmailEnabled(),
            request.getPushEnabled(),
            request.getFrequency()
        );

        return ResponseEntity.ok(pref);
    }

    @PostMapping("/devices")
    @Operation(summary = "Enregistrer device token pour push")
    public ResponseEntity<DeviceToken> registerDevice(
            @AuthenticationPrincipal UserPrincipal currentUser,
            @Valid @RequestBody RegisterDeviceRequest request) {

        DeviceToken token = deviceTokenService.registerToken(
            currentUser.getId(),
            request.getToken(),
            request.getPlatform(),
            request.getDeviceName()
        );

        return ResponseEntity.ok(token);
    }

    @DeleteMapping("/devices/{token}")
    @Operation(summary = "Supprimer device token")
    public ResponseEntity<Void> unregisterDevice(@PathVariable String token) {
        deviceTokenService.unregisterToken(token);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/devices")
    @Operation(summary = "Mes device tokens")
    public ResponseEntity<List<DeviceToken>> getMyDevices(
            @AuthenticationPrincipal UserPrincipal currentUser) {

        List<DeviceToken> tokens = deviceTokenService.getUserTokens(currentUser.getId());
        return ResponseEntity.ok(tokens);
    }
}
