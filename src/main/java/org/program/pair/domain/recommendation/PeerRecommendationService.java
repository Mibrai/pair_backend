package org.program.pair.domain.recommendation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.badge.BadgeService;
import org.program.pair.domain.recommendation.dto.CreateRecommendationRequest;
import org.program.pair.domain.recommendation.dto.RecommendationStatsDto;
import org.program.pair.domain.trust.InteractionProofType;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ConversationRepository;
import org.program.pair.repository.PeerRecommendationRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ConflictException;
import org.program.pair.shared.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final AttendanceRepository attendanceRepository;
    private final BadgeService badgeService;

    /**
     * Crée une recommandation entre pairs.
     * Vérifie qu'une preuve d'interaction réelle existe : soit une conversation
     * directe, soit une double confirmation de présence sur le même créneau
     * (SHARED_ATTENDANCE) — cette dernière est une preuve au moins aussi forte
     * qu'une simple conversation.
     */
    public PeerRecommendation createRecommendation(UUID recommenderId, CreateRecommendationRequest request) {
        UUID recommendedId = request.getRecommendedId();

        // Validation 1: No self-recommendation
        if (recommenderId.equals(recommendedId)) {
            throw new BusinessException("Vous ne pouvez pas vous recommander vous-même");
        }

        // Validation 2: Check if already recommended
        if (hasRecommended(recommenderId, recommendedId)) {
            throw dejaRecommande();
        }

        // Validation 3: Must have proof of interaction (conversation OR shared attendance)
        UUID conversationId = findConversationBetween(recommenderId, recommendedId);
        InteractionProofType proofType;
        if (conversationId != null) {
            proofType = InteractionProofType.CONVERSATION;
        } else if (attendanceRepository.existsSharedPresence(recommenderId, recommendedId)) {
            proofType = InteractionProofType.SHARED_ATTENDANCE;
        } else {
            throw new BusinessException("Vous devez avoir échangé des messages ou partagé une présence confirmée avec cet utilisateur avant de pouvoir le recommander");
        }

        // Create recommendation
        // Pas d'id assigné ici : @GeneratedValue le pose. Un id posé à la main rend
        // save() non-« new » pour Spring Data, qui appelle alors merge() au lieu de
        // persist() ; Hibernate 7 refuse de fusionner une instance détachée dont la
        // ligne n'existe pas et lève StaleObjectStateException — c'était le 500 du
        // chemin nominal de cette écriture.
        PeerRecommendation recommendation = PeerRecommendation.builder()
            .recommenderId(recommenderId)
            .recommendedId(recommendedId)
            .conversationId(conversationId)
            .interactionProofType(proofType)
            .rating(request.getRating())
            .comment(request.getComment())
            .activityContext(request.getActivityContext())
            .programContext(request.getProgramContext())
            .build();

        try {
            // saveAndFlush et non save : l'identifiant étant généré en mémoire,
            // l'INSERT ne partirait qu'au commit, donc hors de ce try — et la
            // violation de unique_recommendation ressortirait en 500.
            recommendation = recommendationRepository.saveAndFlush(recommendation);
        } catch (DataIntegrityViolationException e) {
            // Deux recommandations simultanées de la même personne : le contrôle
            // ci-dessus les laisse passer toutes les deux, seule la contrainte
            // unique_recommendation tranche. La seconde est un « déjà
            // recommandé » comme un autre, pas un 500.
            throw dejaRecommande();
        }
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
     * {@code 409} et non {@code 422} : « c'est déjà fait » est un état, pas un
     * refus de droit. L'app affiche alors « Recommandé », ce qui est la vérité —
     * le {@code 422} lui faisait annoncer un refus sur un geste déjà accompli.
     */
    private ConflictException dejaRecommande() {
        return new ConflictException(
            ErrorCode.RECOMMENDATION_ALREADY_GIVEN, "Vous avez déjà recommandé cet utilisateur");
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
     * Vérifie si un utilisateur peut recommander un autre (preuve d'interaction
     * requise : conversation OU présence partagée confirmée).
     */
    @Transactional(readOnly = true)
    public boolean canRecommend(UUID recommenderId, UUID recommendedId) {
        if (recommenderId.equals(recommendedId)) {
            return false;
        }
        if (hasRecommended(recommenderId, recommendedId)) {
            return false;
        }
        return findConversationBetween(recommenderId, recommendedId) != null
            || attendanceRepository.existsSharedPresence(recommenderId, recommendedId);
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
