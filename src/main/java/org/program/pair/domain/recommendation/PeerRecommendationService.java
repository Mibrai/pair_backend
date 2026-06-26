package org.program.pair.domain.recommendation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.badge.BadgeService;
import org.program.pair.domain.recommendation.dto.CreateRecommendationRequest;
import org.program.pair.domain.recommendation.dto.RecommendationStatsDto;
import org.program.pair.repository.ConversationRepository;
import org.program.pair.repository.PeerRecommendationRepository;
import org.program.pair.shared.exception.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class PeerRecommendationService {

    private final PeerRecommendationRepository recommendationRepository;
    private final ConversationRepository conversationRepository;
    private final BadgeService badgeService;

    /**
     * Crée une recommandation entre pairs
     * Vérifie qu'une conversation existe (preuve d'interaction)
     */
    public PeerRecommendation createRecommendation(UUID recommenderId, CreateRecommendationRequest request) {
        UUID recommendedId = request.getRecommendedId();

        // Validation 1: No self-recommendation
        if (recommenderId.equals(recommendedId)) {
            throw new BusinessException("Vous ne pouvez pas vous recommander vous-même");
        }

        // Validation 2: Check if already recommended
        if (hasRecommended(recommenderId, recommendedId)) {
            throw new BusinessException("Vous avez déjà recommandé cet utilisateur");
        }

        // Validation 3: Must have conversation (proof of interaction)
        UUID conversationId = findConversationBetween(recommenderId, recommendedId);
        if (conversationId == null) {
            throw new BusinessException("Vous devez avoir échangé des messages avec cet utilisateur avant de pouvoir le recommander");
        }

        // Create recommendation
        PeerRecommendation recommendation = PeerRecommendation.builder()
            .id(UUID.randomUUID())
            .recommenderId(recommenderId)
            .recommendedId(recommendedId)
            .conversationId(conversationId)
            .rating(request.getRating())
            .comment(request.getComment())
            .activityContext(request.getActivityContext())
            .programContext(request.getProgramContext())
            .build();

        recommendation = recommendationRepository.save(recommendation);
        log.info("User {} recommended user {} with rating {}", recommenderId, recommendedId, request.getRating());

        // Trigger badge evaluation for recommended user
        try {
            badgeService.evaluateBadges(recommendedId);
        } catch (Exception e) {
            log.warn("Failed to evaluate badges after recommendation: {}", e.getMessage());
        }

        return recommendation;
    }

    /**
     * Récupère les recommandations reçues par un utilisateur
     */
    @Transactional(readOnly = true)
    public Page<PeerRecommendation> getRecommendationsReceived(UUID userId, Pageable pageable) {
        return recommendationRepository.findByRecommendedIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * Récupère les recommandations données par un utilisateur
     */
    @Transactional(readOnly = true)
    public Page<PeerRecommendation> getRecommendationsGiven(UUID userId, Pageable pageable) {
        return recommendationRepository.findByRecommenderIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * Récupère les statistiques de recommandations d'un utilisateur
     */
    @Transactional(readOnly = true)
    public RecommendationStatsDto getUserStats(UUID userId) {
        long receivedCount = recommendationRepository.countByRecommendedId(userId);
        long givenCount = recommendationRepository.countByRecommenderId(userId);
        Double averageRating = recommendationRepository.findAverageRatingByUserId(userId);

        // Count unique recommenders
        Page<PeerRecommendation> received = recommendationRepository.findByRecommendedIdOrderByCreatedAtDesc(
            userId, Pageable.unpaged()
        );
        long uniqueRecommenders = received.stream()
            .map(PeerRecommendation::getRecommenderId)
            .distinct()
            .count();

        return RecommendationStatsDto.builder()
            .recommendationsReceivedCount(receivedCount)
            .recommendationsGivenCount(givenCount)
            .averageRating(averageRating != null ? averageRating : 0.0)
            .uniqueRecommenders(uniqueRecommenders)
            .build();
    }

    /**
     * Vérifie si un utilisateur peut recommander un autre
     * (doit avoir une conversation)
     */
    @Transactional(readOnly = true)
    public boolean canRecommend(UUID recommenderId, UUID recommendedId) {
        if (recommenderId.equals(recommendedId)) {
            return false;
        }
        if (hasRecommended(recommenderId, recommendedId)) {
            return false;
        }
        return findConversationBetween(recommenderId, recommendedId) != null;
    }

    /**
     * Vérifie si un utilisateur a déjà recommandé un autre
     */
    @Transactional(readOnly = true)
    public boolean hasRecommended(UUID recommenderId, UUID recommendedId) {
        return recommendationRepository.findByRecommenderIdAndRecommendedId(recommenderId, recommendedId).isPresent();
    }

    /**
     * Trouve l'ID de la conversation entre deux utilisateurs
     */
    private UUID findConversationBetween(UUID userId1, UUID userId2) {
        return conversationRepository.findDirectBetween(userId1, userId2)
            .map(conv -> conv.getId())
            .orElse(null);
    }
}
