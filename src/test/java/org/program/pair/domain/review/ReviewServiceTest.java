package org.program.pair.domain.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.chat.Conversation;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.review.dto.CreateReviewRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.ConversationRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ReviewRepository;
import org.program.pair.shared.exception.BusinessException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    ReviewRepository reviewRepository;
    @Mock
    ProgramRepository programRepository;
    @Mock
    ConversationRepository conversationRepository;
    @Mock
    AttendanceRepository attendanceRepository;
    @InjectMocks
    ReviewService reviewService;

    @Test
    void createReview_devraitRejeter_auteurNoteSonPropreProgramme() {
        UUID ownerId = UUID.randomUUID();
        Program program = buildProgramOwnedBy(ownerId);
        when(programRepository.findById(program.getId())).thenReturn(Optional.of(program));

        CreateReviewRequest request = new CreateReviewRequest(
            program.getId(), 5.0f, "Super programme, très bien organisé");

        // L'auteur EST le propriétaire
        assertThatThrownBy(() -> reviewService.createReview(ownerId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("propre programme");
    }

    @Test
    void createReview_devraitRejeter_sansInteractionProuvee() {
        UUID ownerId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        Program program = buildProgramOwnedBy(ownerId);
        when(programRepository.findById(program.getId())).thenReturn(Optional.of(program));
        when(conversationRepository.findDirectBetween(reviewerId, ownerId))
            .thenReturn(Optional.empty()); // AUCUNE conversation

        CreateReviewRequest request = new CreateReviewRequest(
            program.getId(), 5.0f, "Super programme, très bien organisé");

        assertThatThrownBy(() -> reviewService.createReview(reviewerId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("échangé des messages");

        verify(reviewRepository, never()).save(any());
    }

    @Test
    void createReview_devraitRejeter_siDejaNoteUneFois() {
        UUID ownerId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        Program program = buildProgramOwnedBy(ownerId);
        when(programRepository.findById(program.getId())).thenReturn(Optional.of(program));
        when(reviewRepository.findByReviewerIdAndProgramId(reviewerId, program.getId()))
            .thenReturn(Optional.of(new Review()));

        CreateReviewRequest request = new CreateReviewRequest(
            program.getId(), 5.0f, "Super programme, très bien organisé");

        assertThatThrownBy(() -> reviewService.createReview(reviewerId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("déjà évalué");
    }

    @Test
    void createReview_accepteAvis_quandToutEstValide() {
        UUID ownerId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        Program program = buildProgramOwnedBy(ownerId);

        Conversation conversation = new Conversation();
        conversation.setId(UUID.randomUUID());

        when(programRepository.findById(program.getId())).thenReturn(Optional.of(program));
        when(conversationRepository.findDirectBetween(reviewerId, ownerId))
            .thenReturn(Optional.of(conversation));
        when(reviewRepository.findByReviewerIdAndProgramId(reviewerId, program.getId()))
            .thenReturn(Optional.empty());
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateReviewRequest request = new CreateReviewRequest(
            program.getId(), 5.0f, "Super programme, très bien organisé");

        assertThatCode(() -> reviewService.createReview(reviewerId, request))
            .doesNotThrowAnyException();

        verify(reviewRepository).save(any(Review.class));
    }

    private Program buildProgramOwnedBy(UUID ownerId) {
        User owner = new User();
        owner.setId(ownerId);
        UserActivity ua = new UserActivity();
        ua.setUser(owner);
        Program p = new Program();
        p.setId(UUID.randomUUID());
        p.setUserActivity(ua);
        return p;
    }


}
