package org.program.pair.domain.program;

import org.program.pair.domain.block.BlockFilterService;
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

import static org.assertj.core.api.Assertions.assertThat;
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
    // Non stubbé : un mock Mockito rend une liste vide par défaut, soit « aucun
    // conflit » — le comportement d'avant l'introduction de la règle B1.
    @Mock ScheduleConflictDetector conflictDetector;
    @Mock HtmlSanitizer sanitizer;

    @Mock BlockFilterService blockFilterService;

    // Non stubbés, pour la même raison que conflictDetector : ces deux-là écrivent
    // sur le créneau et sur la file, et ce que cette classe vérifie — les refus
    // d'entrée, et l'ouverture de conversation — se joue avant ou après eux. Ils
    // doivent seulement exister : sans déclaration, @InjectMocks les laisse à null
    // et joinSlot tombe sur un NullPointerException qui ne dit rien du test.
    @Mock ParticipantCounter participantCounter;
    @Mock WaitlistPromoter waitlistPromoter;

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

        // Une participation CONFIRMED, et non la seule existence d'une ligne :
        // c'est tout le lot du 04/09. Le contrôle portait sur exists(), donc une
        // ligne WITHDRAWN — celle que leaveSlot laisse derrière lui — valait
        // encore refus, et se désinscrire était irréversible.
        SlotParticipation confirmee = new SlotParticipation();
        confirmee.setStatus(ParticipationStatus.CONFIRMED);
        when(participationRepository.findByScheduleIdAndUserId(slot.getId(), joinerId))
            .thenReturn(Optional.of(confirmee));

        assertThatThrownBy(() -> slotService.joinSlot(joinerId, slot.getId(), new JoinSlotRequest(null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("déjà rejoint");
    }

    @Test
    void joinSlot_devraitAccepter_apresUnDesistement() {
        // Le pendant du test ci-dessus, et le défaut lui-même : la même ligne,
        // dans l'état que laisse leaveSlot, ne doit plus valoir refus.
        UUID hostId = UUID.randomUUID();
        UUID joinerId = UUID.randomUUID();
        Schedule slot = buildOpenSlot(hostId, Instant.now().plus(1, ChronoUnit.DAYS));
        slot.getProgram().getUserActivity().getUser().setReceiveMessages(false);

        SlotParticipation partie = new SlotParticipation();
        partie.setStatus(ParticipationStatus.WITHDRAWN);
        partie.setWithdrawnAt(Instant.now());
        stubHappyPathJoin(slot, joinerId, Optional.of(partie));

        assertThatCode(() -> slotService.joinSlot(joinerId, slot.getId(), new JoinSlotRequest(null)))
            .doesNotThrowAnyException();

        assertThat(partie.getStatus()).isEqualTo(ParticipationStatus.CONFIRMED);
        assertThat(partie.getWithdrawnAt()).isNull();
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

        // Le programme et le créneau vont jusqu'à la conversation, pas seulement
        // l'activité. Ce test passait déjà quand le contexte n'allait nulle part :
        // ChatService recevait l'activité et la jetait, et l'en-tête du client
        // restait vide. C'est la date du créneau qui lui permet de griser le fil
        // une fois la séance passée — l'activité seule ne la désigne pas dès que
        // quelqu'un suit deux programmes de la même activité.
        verify(chatService).createConversation(
            eq(joinerId),
            argThat(request -> request.targetUserId().equals(hostId)
                && slot.getProgram().getUserActivity().getActivity().getId()
                    .equals(request.activityContextId())),
            eq(slot.getProgram().getId()),
            eq(slot.getId()));
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
        stubHappyPathJoin(slot, joinerId, Optional.empty());
    }

    /**
     * @param participationExistante l'état dans lequel joinSlot trouve la ligne :
     *        vide pour une première inscription, présente pour une réinscription
     *        après désistement — les deux chemins que le lot du 04/09 sépare.
     */
    private void stubHappyPathJoin(Schedule slot, UUID joinerId,
                                   Optional<SlotParticipation> participationExistante) {
        when(scheduleRepository.lockById(slot.getId())).thenReturn(Optional.of(slot));
        when(participationRepository.findByScheduleIdAndUserId(slot.getId(), joinerId))
            .thenReturn(participationExistante);
        // Plus de stub du décompte de places : le recomptage qui suivait l'écriture
        // a quitté SlotService pour ParticipantCounter, qui est mocké. Le seul
        // décompte que joinSlot fait encore lui-même est le contrôle de capacité,
        // et ce créneau de test n'a pas de plafond — il ne l'atteint donc jamais.
        lenient().when(sanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));
        User joiner = new User();
        joiner.setId(joinerId);
        joiner.setDisplayName("Joiner");
        // lenient : la référence n'est demandée que pour poser une ligne neuve.
        // Une réinscription réactive la ligne existante, dont le porteur est déjà
        // renseigné — c'est précisément ce que ce chemin ne refait pas.
        lenient().when(userRepository.getReferenceById(joinerId)).thenReturn(joiner);
        when(userRepository.findById(joinerId)).thenReturn(Optional.of(joiner));
        when(userService.getPublicProfile(any(), any())).thenReturn(UserPublicDto.identity(
            UUID.randomUUID(), "Host", null, null, "UNVERIFIED"));
        // Le rendu d'un créneau lit désormais les participations par lot, même
        // quand le lot n'a qu'un élément : une seule écriture de la règle.
        when(participationRepository.findByUserIdAndScheduleIdIn(any(), any())).thenReturn(List.of());
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
