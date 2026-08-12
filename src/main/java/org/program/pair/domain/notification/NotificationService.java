package org.program.pair.domain.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.repository.NotificationPrefRepository;
import org.program.pair.repository.NotificationRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.email.EmailService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPrefRepository prefRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PushNotificationServiceInterface pushService;
    private final ObjectMapper objectMapper;
    private final UnreadCounter unreadCounter;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Point d'entrée unique pour toutes les notifications
     */
    @Async
    public void notify(UUID userId, NotificationType type, Map<String, Object> payload) {
        log.debug("Sending notification {} to user {}", type, userId);

        // 1. Récupérer les préférences
        NotificationPref pref = prefRepository
            .findByUserIdAndNotificationType(userId, type)
            .orElse(defaultPref(userId, type));

        // 2. Notification in-app (toujours)
        saveInAppNotification(userId, type, payload);

        // Compté après l'enregistrement, donc celle qui part est comprise dedans :
        // c'est la valeur que le badge d'icône doit afficher à la réception.
        // Messages compris — il n'y a qu'un badge par app (voir UnreadCounter).
        long badgeCount = unreadCounter.badge(userId);

        // 3. Email selon préférence
        if (Boolean.TRUE.equals(pref.getEmailEnabled())) {
            if (pref.getFrequency() == NotificationFrequency.IMMEDIATE) {
                try {
                    emailService.sendNotificationEmail(userId, type, payload);
                } catch (Exception e) {
                    log.error("Failed to send email notification: {}", e.getMessage());
                }
            }
            // DAILY_DIGEST et WEEKLY_DIGEST géré par jobs Quartz
        }

        // 4. Push selon préférence
        if (Boolean.TRUE.equals(pref.getPushEnabled())) {
            try {
                pushService.sendPush(userId, type, payload, badgeCount);
            } catch (Exception e) {
                log.error("Failed to send push notification: {}", e.getMessage());
            }
        }
    }

    /**
     * Envoyer une push <b>sans</b> créer de notification in-app.
     *
     * <p>Chemin de la messagerie. Un message a déjà son écran, son fil et son
     * compteur : le doubler d'une notification in-app le ferait compter deux fois
     * dans le badge — une fois comme message, une fois comme notification — et
     * remplirait le centre de notifications d'entrées que personne n'a demandées.
     *
     * <p>Ce qui reste de {@link #notify} : la préférence {@code pushEnabled} du
     * destinataire, qui continue de faire foi, et le badge, qui porte le total
     * relu après écriture du message.
     */
    @Async
    public void notifyPushOnly(UUID userId, NotificationType type, Map<String, Object> payload) {
        NotificationPref pref = prefRepository
            .findByUserIdAndNotificationType(userId, type)
            .orElse(defaultPref(userId, type));

        if (!Boolean.TRUE.equals(pref.getPushEnabled())) {
            return;
        }

        try {
            pushService.sendPush(userId, type, payload, unreadCounter.badge(userId));
        } catch (Exception e) {
            log.error("Failed to send push notification: {}", e.getMessage());
        }
    }

    /**
     * Sauvegarder notification in-app
     */
    private void saveInAppNotification(UUID userId, NotificationType type, Map<String, Object> payload) {
        try {
            Notification notif = Notification.builder()
                .user(userRepository.getReferenceById(userId))
                .type(type)
                .channel(NotificationChannel.IN_APP)
                .payload(objectMapper.writeValueAsString(payload))
                .isRead(false)
                .sentAt(Instant.now())
                .build();

            notificationRepository.save(notif);
            log.debug("In-app notification saved for user {}", userId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize notification payload: {}", e.getMessage());
        }
    }

    /**
     * Préférences par défaut
     */
    private NotificationPref defaultPref(UUID userId, NotificationType type) {
        return NotificationPref.builder()
            .user(userRepository.getReferenceById(userId))
            .notificationType(type)
            .emailEnabled(true)
            .pushEnabled(true)
            .frequency(NotificationFrequency.IMMEDIATE)
            .build();
    }

    /**
     * Récupérer notifications d'un utilisateur
     */
    @Transactional(readOnly = true)
    public Page<Notification> getNotifications(UUID userId, Pageable pageable) {
        return notificationRepository.findByUserIdOrderBySentAtDesc(userId, pageable);
    }

    /**
     * Compter notifications non lues
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    /**
     * Marquer une notification comme lue
     */
    public void markAsRead(UUID notificationId, UUID userId) {
        notificationRepository.findById(notificationId).ifPresent(notif -> {
            if (notif.getUser().getId().equals(userId) && !notif.getIsRead()) {
                notif.setIsRead(true);
                notif.setReadAt(Instant.now());
                notificationRepository.save(notif);
                eventPublisher.publishEvent(new UnreadChangedEvent(userId));
            }
        });
    }

    /**
     * Marquer toutes les notifications comme lues
     *
     * <p>Publie {@link UnreadChangedEvent} : c'est le cas typique de la lecture
     * « ailleurs » — sur le web ou un second appareil —, après laquelle le
     * téléphone resté fermé annoncerait du non-lu qui n'existe plus.
     */
    public int markAllAsRead(UUID userId) {
        List<Notification> unread = notificationRepository.findByUserIdAndIsReadFalse(userId);
        unread.forEach(notif -> {
            notif.setIsRead(true);
            notif.setReadAt(Instant.now());
        });
        notificationRepository.saveAll(unread);

        if (!unread.isEmpty()) {
            eventPublisher.publishEvent(new UnreadChangedEvent(userId));
        }
        return unread.size();
    }

    /**
     * Supprimer une notification
     */
    public void deleteNotification(UUID notificationId, UUID userId) {
        notificationRepository.findById(notificationId).ifPresent(notif -> {
            if (notif.getUser().getId().equals(userId)) {
                notificationRepository.delete(notif);
            }
        });
    }

    /**
     * Récupérer les préférences d'un utilisateur
     */
    @Transactional(readOnly = true)
    public List<NotificationPref> getUserPreferences(UUID userId) {
        return prefRepository.findByUserId(userId);
    }

    /**
     * Mettre à jour les préférences
     */
    public NotificationPref updatePreference(UUID userId, NotificationType type,
                                             Boolean emailEnabled, Boolean pushEnabled,
                                             NotificationFrequency frequency) {
        NotificationPref pref = prefRepository
            .findByUserIdAndNotificationType(userId, type)
            .orElse(NotificationPref.builder()
                .user(userRepository.getReferenceById(userId))
                .notificationType(type)
                .build());

        if (emailEnabled != null) pref.setEmailEnabled(emailEnabled);
        if (pushEnabled != null) pref.setPushEnabled(pushEnabled);
        if (frequency != null) pref.setFrequency(frequency);

        return prefRepository.save(pref);
    }
}
