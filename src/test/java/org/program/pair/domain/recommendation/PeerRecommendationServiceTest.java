package org.program.pair.domain.recommendation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.badge.BadgeService;
import org.program.pair.domain.chat.Conversation;
import org.program.pair.domain.recommendation.dto.CreateRecommendationRequest;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ConversationRepository;
import org.program.pair.repository.PeerRecommendationRepository;
import org.program.pair.shared.exception.BusinessException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PeerRecommendationServiceTest {

    @Mock
    PeerRecommendationRepository recommendationRepository;
    @Mock
    ConversationRepository conversationRepository;
    @Mock
    AttendanceRepository attendanceRepository;
    @Mock
    BadgeService badgeService;
    @InjectMocks
    PeerRecommendationService recommendationService;

    @Test
    void create_devraitRejeter_autoRecommandation() {
        UUID userId = UUID.randomUUID();
        CreateRecommendationRequest request = new CreateRecommendationRequest(
            userId, 5, "Commentaire sur moi-même, c'est génial", null, null
        );

        assertThatThrownBy(() -> recommendationService.createRecommendation(userId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("vous-même");
    }

    @Test
    void create_devraitRejeter_sansConversationEntreLesDeux() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        when(conversationRepository.findDirectBetween(fromId, toId))
            .thenReturn(Optional.empty());
        when(recommendationRepository.findByRecommenderIdAndRecommendedId(fromId, toId))
            .thenReturn(Optional.empty());

        CreateRecommendationRequest request = new CreateRecommendationRequest(
            toId, 5, "Excellente personne, très fiable et professionnelle", null, null
        );

        assertThatThrownBy(() -> recommendationService.createRecommendation(fromId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("échangé des messages");
    }

    @Test
    void create_devraitRejeter_doublonDeRecommandation() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        when(recommendationRepository.findByRecommenderIdAndRecommendedId(fromId, toId))
            .thenReturn(Optional.of(new PeerRecommendation()));

        CreateRecommendationRequest request = new CreateRecommendationRequest(
            toId, 5, "Excellente personne, très fiable et professionnelle", null, null
        );

        assertThatThrownBy(() -> recommendationService.createRecommendation(fromId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("déjà recommandé");
    }
}
