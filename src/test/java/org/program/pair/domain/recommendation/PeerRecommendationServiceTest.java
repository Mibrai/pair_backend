package org.program.pair.domain.recommendation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.badge.BadgeService;
import org.program.pair.domain.chat.Conversation;
import org.program.pair.domain.recommendation.dto.CreateRecommendationRequest;
import org.program.pair.domain.trust.InteractionProofType;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ConversationRepository;
import org.program.pair.repository.PeerRecommendationRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ConflictException;
import org.program.pair.shared.exception.ErrorCode;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

        // 409 et non 422 : « c'est déjà fait » est un état, pas un refus de droit.
        assertThatThrownBy(() -> recommendationService.createRecommendation(fromId, request))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("déjà recommandé")
            .extracting(e -> ((ConflictException) e).getErrorCode())
            .isEqualTo(ErrorCode.RECOMMENDATION_ALREADY_GIVEN);
    }

    @Test
    void create_devraitAccepter_sansRatingNiComment_siConversationExiste() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());

        when(conversationRepository.findDirectBetween(fromId, toId))
            .thenReturn(Optional.of(conversation));
        when(recommendationRepository.findByRecommenderIdAndRecommendedId(fromId, toId))
            .thenReturn(Optional.empty());
        when(recommendationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateRecommendationRequest request = new CreateRecommendationRequest(toId, null, null, null, null);

        PeerRecommendation result = recommendationService.createRecommendation(fromId, request);

        assertThat(result.getRating()).isNull();
        assertThat(result.getComment()).isNull();
        assertThat(result.getInteractionProofType()).isEqualTo(InteractionProofType.CONVERSATION);
    }

    @Test
    void create_devraitAccepter_commentSeulSansRating_viaPresencePartagee() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        when(conversationRepository.findDirectBetween(fromId, toId))
            .thenReturn(Optional.empty());
        when(attendanceRepository.existsSharedPresence(fromId, toId)).thenReturn(true);
        when(recommendationRepository.findByRecommenderIdAndRecommendedId(fromId, toId))
            .thenReturn(Optional.empty());
        when(recommendationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateRecommendationRequest request = new CreateRecommendationRequest(
            toId, null, "On a couru ensemble, super rythme.", null, null);

        PeerRecommendation result = recommendationService.createRecommendation(fromId, request);

        assertThat(result.getComment()).isEqualTo("On a couru ensemble, super rythme.");
        assertThat(result.getInteractionProofType()).isEqualTo(InteractionProofType.SHARED_ATTENDANCE);
        assertThat(result.getConversationId()).isNull();
    }

    @Test
    void create_devraitRejeter_siNiConversationNiPresencePartagee() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        when(conversationRepository.findDirectBetween(fromId, toId)).thenReturn(Optional.empty());
        when(attendanceRepository.existsSharedPresence(fromId, toId)).thenReturn(false);
        when(recommendationRepository.findByRecommenderIdAndRecommendedId(fromId, toId))
            .thenReturn(Optional.empty());

        CreateRecommendationRequest request = new CreateRecommendationRequest(toId, null, null, null, null);

        assertThatThrownBy(() -> recommendationService.createRecommendation(fromId, request))
            .isInstanceOf(BusinessException.class);

        verify(recommendationRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void validation_devraitAccepter_ratingEtCommentAbsents() {
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        CreateRecommendationRequest request = new CreateRecommendationRequest(
            UUID.randomUUID(), null, null, null, null);

        var violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void validation_devraitAccepter_commentCourt() {
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        CreateRecommendationRequest request = new CreateRecommendationRequest(
            UUID.randomUUID(), null, "Super !", null, null);

        var violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void validation_devraitRejeter_ratingHorsBornes() {
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        CreateRecommendationRequest request = new CreateRecommendationRequest(
            UUID.randomUUID(), 6, null, null, null);

        var violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void validation_devraitRejeter_commentTropLong() {
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        CreateRecommendationRequest request = new CreateRecommendationRequest(
            UUID.randomUUID(), null, "x".repeat(501), null, null);

        var violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }
}
