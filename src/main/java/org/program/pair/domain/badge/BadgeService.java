package org.program.pair.domain.badge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.trust.Badge;
import org.program.pair.domain.trust.BadgeAward;
import org.program.pair.domain.user.User;
import org.program.pair.repository.BadgeAwardRepository;
import org.program.pair.repository.BadgeRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ProgressionRepository;
import org.program.pair.repository.UserActivityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class BadgeService {

    private final BadgeRepository badgeRepository;
    private final BadgeAwardRepository badgeAwardRepository;
    private final ProgramRepository programRepository;
    private final ProgressionRepository progressionRepository;
    private final UserActivityRepository userActivityRepository;
    private final org.program.pair.repository.PeerRecommendationRepository peerRecommendationRepository;

    /**
     * Évalue tous les badges pour un utilisateur et attribue ceux qu'il mérite
     */
    public List<BadgeAward> evaluateBadges(UUID userId) {
        log.info("Evaluating badges for user {}", userId);

        List<Badge> allBadges = badgeRepository.findAll();
        List<BadgeAward> awarded = new java.util.ArrayList<>();

        for (Badge badge : allBadges) {
            // Skip if already awarded
            if (badgeAwardRepository.findByUserIdAndBadgeId(userId, badge.getId()).isPresent()) {
                continue;
            }

            // Check eligibility
            if (isEligible(userId, badge)) {
                BadgeAward award = awardBadge(userId, badge);
                awarded.add(award);
                log.info("Badge {} awarded to user {}", badge.getCode(), userId);
            }
        }

        return awarded;
    }

    /**
     * Vérifie si un utilisateur est éligible pour un badge
     */
    public boolean isEligible(UUID userId, Badge badge) {
        return switch (badge.getConditionType()) {
            case VERIFICATION -> checkVerification(userId);
            case PROGRAM_COUNT -> checkProgramCount(userId, badge.getConditionThreshold());
            case PROGRESSION_STREAK -> checkProgressionStreak(userId, badge.getConditionThreshold());
            case ACTIVITY_DIVERSITY -> checkActivityDiversity(userId, badge.getConditionThreshold());
            case RECOMMENDATION_COUNT -> checkRecommendationCount(userId, badge.getConditionThreshold());
            case MANUAL -> false; // Manual badges cannot be auto-awarded
        };
    }

    /**
     * Attribue un badge à un utilisateur
     */
    public BadgeAward awardBadge(UUID userId, Badge badge) {
        BadgeAward.BadgeAwardId id = new BadgeAward.BadgeAwardId();
        id.setBadgeId(badge.getId());
        id.setUserId(userId);

        BadgeAward award = BadgeAward.builder()
            .id(id)
            .badge(badge)
            .awardedAt(Instant.now())
            .build();

        return badgeAwardRepository.save(award);
    }

    /**
     * Récupère tous les badges d'un utilisateur
     */
    @Transactional(readOnly = true)
    public List<BadgeAward> getUserBadges(UUID userId) {
        return badgeAwardRepository.findByUserId(userId);
    }

    /**
     * Récupère tous les badges disponibles
     */
    @Transactional(readOnly = true)
    public List<Badge> getAllBadges() {
        return badgeRepository.findAll();
    }

    /**
     * Compte les badges d'un utilisateur
     */
    @Transactional(readOnly = true)
    public long countUserBadges(UUID userId) {
        return badgeAwardRepository.countByUserId(userId);
    }

    // ===== Condition Checks =====

    private boolean checkVerification(UUID userId) {
        // TODO: Check if user has verified email or phone
        // For MVP, always return false (verification not implemented yet)
        return false;
    }

    private boolean checkProgramCount(UUID userId, Integer threshold) {
        long count = programRepository.countProgramsByUser(userId);
        return count >= threshold;
    }

    private boolean checkProgressionStreak(UUID userId, Integer threshold) {
        // Count total progressions as streak proxy (simplified)
        // TODO: Implement proper streak calculation based on consecutive days
        int count = progressionRepository.countByUserId(userId);
        return count >= threshold;
    }

    private boolean checkActivityDiversity(UUID userId, Integer threshold) {
        long count = userActivityRepository.countByUserId(userId);
        return count >= threshold;
    }

    private boolean checkRecommendationCount(UUID userId, Integer threshold) {
        long count = peerRecommendationRepository.countByRecommendedId(userId);
        return count >= threshold;
    }
}
