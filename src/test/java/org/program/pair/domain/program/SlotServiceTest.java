package org.program.pair.domain.program;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.chat.ChatService;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.UserService;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlotServiceTest {

    @Mock ScheduleRepository scheduleRepository;
    @Mock SlotParticipationRepository participationRepository;
    @Mock UserRepository userRepository;
    @Mock UserService userService;
    @Mock ChatService chatService;
    @Mock NotificationService notificationService;
    @Mock HtmlSanitizer sanitizer;

    @InjectMocks
    SlotService slotService;

    @Test
    void joinSlot_devraitRejeter_hoteRejoignantSonProprCreneau() {
        UUID hostId = UUID.randomUUID();
        Schedule slot = buildOpenSlot(hostId, Instant.now().plus(1, ChronoUnit.DAYS));
        when(scheduleRepository.lockById(slot.getId())).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> slotService.joinSlot(hostId, slot.getId(), new JoinSlotRequest(null)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("propre créneau");
    }

    @Test
    void joinSlot_devraitRejeter_creneauDejaPasse() {
        UUID hostId = UUID.randomUUID();
        UUID joinerId = UUID.randomUUID();
        Schedule slot = buildOpenSlot(hostId, Instant.now().minus(1, ChronoUnit.DAYS));
        when(scheduleRepository.lockById(slot.getId())).thenReturn(Optional.of(slot));

        assertThatThrownBy(() -> slotService.joinSlot(joinerId, slot.getId(), new JoinSlotRequest(null)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("passé");
    }

    @Test
    void joinSlot_devraitRejeter_doublon() {
        UUID hostId = UUID.randomUUID();
        UUID joinerId = UUID.randomUUID();
        Schedule slot = buildOpenSlot(hostId, Instant.now().plus(1, ChronoUnit.DAYS));
        when(scheduleRepository.lockById(slot.getId())).thenReturn(Optional.of(slot));
        when(participationRepository.existsByScheduleIdAndUserId(slot.getId(), joinerId)).thenReturn(true);

        assertThatThrownBy(() -> slotService.joinSlot(joinerId, slot.getId(), new JoinSlotRequest(null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("déjà rejoint");
    }

    @Test
    void joinSlot_devraitOuvrirUneConversationContextualisee() {
        UUID hostId = UUID.randomUUID();
        UUID joinerId = UUID.randomUUID();
        Schedule slot = buildOpenSlot(hostId, Instant.now().plus(1, ChronoUnit.DAYS));
        User host = slot.getProgram().getUserActivity().getUser();
        host.setReceiveMessages(true);

        stubHappyPathJoin(slot, joinerId);

        slotService.joinSlot(joinerId, slot.getId(), new JoinSlotRequest("Je débute, ça vous va ?"));

        verify(chatService).createConversation(eq(joinerId), any());
    }

    @Test
    void joinSlot_devraitRespecter_receiveMessagesFalseDeLHote() {
        UUID hostId = UUID.randomUUID();
        UUID joinerId = UUID.randomUUID();
        Schedule slot = buildOpenSlot(hostId, Instant.now().plus(1, ChronoUnit.DAYS));
        User host = slot.getProgram().getUserActivity().getUser();
        host.setReceiveMessages(false);

        stubHappyPathJoin(slot, joinerId);

        assertThatCode(() -> slotService.joinSlot(joinerId, slot.getId(), new JoinSlotRequest(null)))
            .doesNotThrowAnyException();

        verify(chatService, never()).createConversation(any(), any());
    }

    private void stubHappyPathJoin(Schedule slot, UUID joinerId) {
        when(scheduleRepository.lockById(slot.getId())).thenReturn(Optional.of(slot));
        when(participationRepository.existsByScheduleIdAndUserId(slot.getId(), joinerId)).thenReturn(false);
        when(scheduleRepository.countConfirmedParticipants(slot.getId())).thenReturn(1L);
        lenient().when(sanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));
        User joiner = new User();
        joiner.setId(joinerId);
        joiner.setDisplayName("Joiner");
        when(userRepository.getReferenceById(joinerId)).thenReturn(joiner);
        when(userRepository.findById(joinerId)).thenReturn(Optional.of(joiner));
        when(userService.getPublicProfile(any(), any())).thenReturn(new UserPublicDto(
            UUID.randomUUID(), "Host", null, null, "UNVERIFIED", List.of(), List.of(), false));
        when(participationRepository.findByScheduleIdAndUserId(any(), any())).thenReturn(Optional.empty());
    }

    private Schedule buildOpenSlot(UUID hostId, Instant startsAt) {
        Category category = Category.builder().id(UUID.randomUUID()).name("Sports").build();
        Activity activity = Activity.builder().id(UUID.randomUUID()).name("Yoga").category(category).build();

        User host = new User();
        host.setId(hostId);
        host.setDisplayName("Host");

        UserActivity ua = new UserActivity();
        ua.setId(UUID.randomUUID());
        ua.setUser(host);
        ua.setActivity(activity);

        Program program = new Program();
        program.setId(UUID.randomUUID());
        program.setTitle("Yoga du matin");
        program.setUserActivity(ua);

        Schedule schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setProgram(program);
        schedule.setPlaceName("Studio Test");
        schedule.setPlaceType(PlaceType.PUBLIC);
        schedule.setStartsAt(startsAt);
        schedule.setIsOpenToPartners(true);
        schedule.setStatus(SlotStatus.OPEN);
        schedule.setParticipantCount(0);
        return schedule;
    }
}
