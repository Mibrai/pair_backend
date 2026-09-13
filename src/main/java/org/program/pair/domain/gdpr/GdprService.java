package org.program.pair.domain.gdpr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.audit.AuditActionType;
import org.program.pair.domain.audit.AuditLogRepository;
import org.program.pair.domain.audit.AuditLogService;
import org.program.pair.domain.gdpr.dto.GdprExportDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.*;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * GDPR Compliance Service
 * Implements EU GDPR requirements (Articles 15, 17, 20)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GdprService {

    private final UserRepository userRepository;
    private final UserActivityRepository userActivityRepository;
    private final ProgramRepository programRepository;
    private final MessageRepository messageRepository;
    private final ReviewRepository reviewRepository;
    private final PeerRecommendationRepository recommendationRepository;
    private final ProgressionRepository progressionRepository;
    private final NotificationRepository notificationRepository;
    private final AuditLogRepository auditLogRepository;
    private final ConversationMemberRepository conversationMemberRepository;
    private final AuditLogService auditLogService;

    /**
     * L'effacement d'un compte vit dans un <b>autre</b> bean, et c'est la seule
     * façon d'obtenir une transaction par compte : un appel sur {@code this}
     * n'aurait pas franchi le proxy, et l'annotation de la méthode appelée aurait
     * été ignorée — le défaut que ce lot ferme.
     */
    private final GdprAccountEraser eraser;

    private final MetriquesPurgeRgpd metriques;

    /**
     * Export all user data (GDPR Article 15: Right of access)
     */
    @Transactional(readOnly = true)
    public GdprExportDto exportUserData(UUID userId) {
        log.info("Exporting GDPR data for user {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Log the export action
        auditLogService.log(userId, AuditActionType.GDPR_EXPORT, "USER", userId);

        // Collect all user data
        GdprExportDto export = GdprExportDto.builder()
                .exportDate(Instant.now())
                .exportedBy(user.getEmail())
                .user(buildUserData(user))
                .activities(buildActivitiesData(userId))
                .programs(buildProgramsData(userId))
                .messages(buildMessagesData(userId))
                .reviews(buildReviewsData(userId))
                .recommendations(buildRecommendationsData(userId))
                .progressions(buildProgressionsData(userId))
                .notifications(buildNotificationsData(userId))
                .auditLogs(buildAuditLogsData(userId))
                .statistics(buildStatistics(userId))
                .build();

        log.info("GDPR export completed for user {}: {} data points",
                userId, calculateDataPoints(export));

        return export;
    }

    /** Le délai de la décision D2, compté depuis la demande de suppression. */
    public static final int DELAI_DE_RETENTION_JOURS = 30;

    /**
     * Ce qu'une passe de purge a réellement fait.
     *
     * <p>La méthode rendait auparavant {@code inactiveUsers.size()}, c'est-à-dire
     * le nombre de <b>candidats</b>, et le journal l'annonçait comme un nombre de
     * comptes purgés. Il pouvait donc dire « 7 comptes purgés » pour sept comptes
     * toujours en base. Les deux nombres sont désormais séparés, parce qu'ils ne
     * disent pas la même chose et que c'est leur écart qui se surveille.
     *
     * @param effaces comptes dont la ligne {@code users} n'existe plus
     * @param echecs  comptes que la purge a laissés derrière elle, chacun
     *                journalisé avec sa cause et compté dans
     *                {@code gdpr.purge.failures}
     */
    public record ResultatDePurge(int effaces, int echecs) {

        public int candidats() {
            return effaces + echecs;
        }
    }

    /**
     * Efface les comptes dont la suppression a été demandée il y a plus de
     * {@link #DELAI_DE_RETENTION_JOURS} jours (RGPD articles 17 et 5.1.e).
     *
     * <p><b>Cette méthode n'est plus {@code @Transactional}, et c'est le cœur du
     * correctif.</b> Elle l'était, et elle appelait l'effacement sur
     * {@code this} : l'auto-invocation ne franchit pas le proxy Spring, si bien
     * que l'annotation de la méthode appelée était décorative et que toute la
     * nuit tenait dans une seule transaction. Un seul compte bloqué par une clé
     * étrangère la marquait {@code rollback-only}, et le commit final levait
     * {@code UnexpectedRollbackException} : <b>aucun</b> compte n'était effacé,
     * pas même ceux qui n'avaient rien de bloquant, et l'échec était attribué au
     * compte suivant dans le journal. Voir {@link GdprAccountEraser} pour le
     * détail de l'enchaînement.
     *
     * <p>La boucle ne tient donc aucune transaction : elle lit une liste
     * d'identifiants, puis appelle un <b>autre bean</b> qui ouvre la sienne par
     * compte. Un échec reste un échec de ce compte-là.
     *
     * <p><b>{@code Exception} et non {@code RuntimeException}</b> dans le
     * {@code catch} : une violation de contrainte remonte en
     * {@code DataIntegrityViolationException}, mais le point de cette boucle est
     * qu'<i>aucun</i> échec d'un compte ne fasse tomber les autres — y compris un
     * échec qu'on n'a pas prévu. {@code Error} n'est volontairement pas attrapé.
     */
    public ResultatDePurge purgeInactiveAccounts() {
        Instant limite = Instant.now().minus(DELAI_DE_RETENTION_JOURS, ChronoUnit.DAYS);
        List<UUID> candidats = userRepository.findDeactivatedBefore(limite);
        log.info("Purge RGPD : {} compte(s) dont la suppression a été demandée avant {}",
            candidats.size(), limite);

        int effaces = 0;
        int echecs = 0;
        for (UUID userId : candidats) {
            try {
                eraser.eraseOne(userId);
                effaces++;
                metriques.compteEfface();
            } catch (Exception echec) {
                echecs++;
                metriques.compteEnEchec();
                // L'identifiant et la cause racine : sans la racine, le journal
                // rend une pile de proxys transactionnels où la violation de
                // contrainte est à vingt lignes du message.
                log.error("Purge RGPD : compte {} non effacé — {}",
                    userId, causeRacine(echec).toString(), echec);
            }
        }

        log.info("Purge RGPD terminée : {} effacé(s), {} échec(s)", effaces, echecs);
        return new ResultatDePurge(effaces, echecs);
    }

    private static Throwable causeRacine(Throwable echec) {
        Throwable racine = echec;
        while (racine.getCause() != null && racine.getCause() != racine) {
            racine = racine.getCause();
        }
        return racine;
    }

    // ========== Private Helper Methods ==========

    private GdprExportDto.UserDataDto buildUserData(User user) {
        return GdprExportDto.UserDataDto.builder()
                .id(user.getId().toString())
                .email(user.getEmail())
                .nom(user.getDisplayName()) // Using displayName for nom
                .prenom("") // No separate prenom field
                .bio(user.getBio())
                .avatarUrl(user.getAvatarUrl())
                .location(user.getLocation() != null ? GdprExportDto.LocationDto.builder()
                        .latitude(user.getLocation().getY())
                        .longitude(user.getLocation().getX())
                        .blurRadiusM(user.getBlurRadiusM())
                        .build() : null)
                .createdAt(user.getCreatedAt())
                .lastActiveAt(user.getLastActiveAt())
                .accountStatus(user.getIsActive() ? "ACTIVE" : "INACTIVE")
                .build();
    }

    private List<GdprExportDto.ActivityDataDto> buildActivitiesData(UUID userId) {
        return userActivityRepository.findByUserId(userId).stream()
                .map(ua -> GdprExportDto.ActivityDataDto.builder()
                        .id(ua.getId().toString())
                        .activityName(ua.getActivity() != null ? ua.getActivity().getName() : null)
                        .categoryName(ua.getActivity() != null && ua.getActivity().getCategory() != null
                                ? ua.getActivity().getCategory().getName() : null)
                        .level(ua.getLevel() != null ? ua.getLevel().name() : null)
                        .format(ua.getFormat() != null ? ua.getFormat().name() : null)
                        .visibleOnMap(ua.getVisibleOnMap())
                        .addedAt(ua.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.ProgramDataDto> buildProgramsData(UUID userId) {
        return programRepository.findByOrganisateurId(userId).stream()
                .map(p -> GdprExportDto.ProgramDataDto.builder()
                        .id(p.getId().toString())
                        .titre(p.getTitle())
                        .description(p.getDescription())
                        .activityName(p.getUserActivity() != null && p.getUserActivity().getActivity() != null
                                ? p.getUserActivity().getActivity().getName() : null)
                        .status(p.getStatus().name())
                        .schedules(p.getSchedules().stream()
                                .map(s -> GdprExportDto.ScheduleDto.builder()
                                        .id(s.getId().toString())
                                        .jourSemaine(s.getRecurrenceRule()) // Using recurrence rule instead of day of week
                                        .heureDebut(s.getStartsAt() != null ? s.getStartsAt().toString() : null)
                                        .heureFin(s.getEndsAt() != null ? s.getEndsAt().toString() : null)
                                        .location(s.getLocation() != null ? GdprExportDto.LocationDto.builder()
                                                .latitude(s.getLocation().getY())
                                                .longitude(s.getLocation().getX())
                                                .build() : null)
                                        .build())
                                .collect(Collectors.toList()))
                        .createdAt(p.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.MessageDataDto> buildMessagesData(UUID userId) {
        return messageRepository.findBySenderId(userId).stream()
                .map(m -> GdprExportDto.MessageDataDto.builder()
                        .id(m.getId().toString())
                        .conversationId(m.getConversation().getId().toString())
                        .content(m.getContent())
                        .sentByMe(true)
                        .sentAt(m.getSentAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.ReviewDataDto> buildReviewsData(UUID userId) {
        return reviewRepository.findByReviewerId(userId).stream()
                .map(r -> GdprExportDto.ReviewDataDto.builder()
                        .id(r.getId().toString())
                        .programTitle(r.getProgram() != null ? r.getProgram().getTitle() : null)
                        .score(r.getScore())
                        .comment(r.getComment())
                        .createdAt(r.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.RecommendationDataDto> buildRecommendationsData(UUID userId) {
        return recommendationRepository.findByRecommenderId(userId).stream()
                .map(r -> GdprExportDto.RecommendationDataDto.builder()
                        .id(r.getId().toString())
                        .recommendedUserName(r.getRecommended() != null
                                ? r.getRecommended().getDisplayName()
                                : "Unknown")
                        .comment(r.getComment())
                        .createdAt(r.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.ProgressionDataDto> buildProgressionsData(UUID userId) {
        return progressionRepository.findByProgramOrganisateurId(userId).stream()
                .map(p -> GdprExportDto.ProgressionDataDto.builder()
                        .id(p.getId().toString())
                        .programTitle(p.getProgram() != null ? p.getProgram().getTitle() : null)
                        .label(p.getTitle())
                        .value(p.getContent())
                        .recordedAt(p.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.NotificationDataDto> buildNotificationsData(UUID userId) {
        return notificationRepository.findByUserId(userId).stream()
                .map(n -> GdprExportDto.NotificationDataDto.builder()
                        .id(n.getId().toString())
                        .type(n.getType().name())
                        .title(n.getType().name()) // Notification doesn't have title field
                        .message(n.getPayload()) // Using payload as message
                        .read(n.getReadAt() != null)
                        .createdAt(n.getSentAt())
                        .build())
                .collect(Collectors.toList());
    }

    private List<GdprExportDto.AuditLogDataDto> buildAuditLogsData(UUID userId) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(
                userId, org.springframework.data.domain.PageRequest.of(0, 1000)
        ).stream()
                .map(a -> GdprExportDto.AuditLogDataDto.builder()
                        .id(a.getId().toString())
                        .actionType(a.getActionType().name())
                        .entityType(a.getEntityType())
                        .ipAddress(a.getIpAddress())
                        .timestamp(a.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private Map<String, Long> buildStatistics(UUID userId) {
        Map<String, Long> stats = new HashMap<>();
        stats.put("activities", (long) userActivityRepository.findByUserId(userId).size());
        stats.put("programs", (long) programRepository.findByOrganisateurId(userId).size());
        stats.put("messages", (long) messageRepository.findBySenderId(userId).size());
        stats.put("reviews", (long) reviewRepository.findByReviewerId(userId).size());
        stats.put("recommendations", (long) recommendationRepository.findByRecommenderId(userId).size());
        stats.put("progressions", (long) progressionRepository.findByProgramOrganisateurId(userId).size());
        stats.put("notifications", notificationRepository.countByUserId(userId));
        stats.put("conversations", (long) conversationMemberRepository.findConversationsByUserId(userId).size());
        return stats;
    }

    private int calculateDataPoints(GdprExportDto export) {
        return (export.getActivities() != null ? export.getActivities().size() : 0) +
               (export.getPrograms() != null ? export.getPrograms().size() : 0) +
               (export.getMessages() != null ? export.getMessages().size() : 0) +
               (export.getReviews() != null ? export.getReviews().size() : 0) +
               (export.getRecommendations() != null ? export.getRecommendations().size() : 0) +
               (export.getProgressions() != null ? export.getProgressions().size() : 0) +
               (export.getNotifications() != null ? export.getNotifications().size() : 0) +
               (export.getAuditLogs() != null ? export.getAuditLogs().size() : 0);
    }
}
