package org.program.pair.domain.program;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.block.BlockFilterService;
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
import org.program.pair.shared.exception.ErrorCode;
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

/**
 * Ce qui appartient encore à {@code SlotService} seul.
 *
 * <p><b>Deux tests de refus ont quitté cette classe</b>, et il faut dire où ils
 * sont allés. P-BL-09 a sorti les refus d'entrée de {@code joinSlot} pour les
 * porter dans {@link SlotEntryGuard}, commune aux deux portes d'inscription
 * ({@code POST /slots/{id}/join} et {@code POST /programs/{id}/join}). Or une
 * règle qui vaut pour deux chemins ne se vérifie pas dans le test d'un seul :
 * ici, la garde est un bouchon, et n'importe quel refus qu'on lui ferait lever
 * ne prouverait plus que {@code joinSlot} propage ce qu'on vient d'y mettre.
 *
 * <ul>
 *   <li>« l'hôte ne rejoint pas son propre créneau » ({@code SLOT_OWN_SLOT}) est
 *       prouvé en HTTP contre une vraie base par
 *       {@code BusinessErrorCodeIntegrationTest.rejoindreSonPropreCreneau_doitRenvoyerUnCodeDistinctDuDoubleJoin} ;</li>
 *   <li>« une séance commencée ne se rejoint plus » ({@code SLOT_ALREADY_STARTED})
 *       l'est par {@code SlotRejoinIntegrationTest} sur la porte créneau, et par
 *       {@code ProgramJoinGuardsIntegrationTest.uneSeanceDejaCommencee_doitEtreRefusee_parLesDeuxPortes}
 *       sur <b>les deux</b> portes — ce que cette classe-ci ne pouvait pas faire.</li>
 * </ul>
 *
 * <p>Ce qui reste vrai à ce niveau, et qui est testé ici : que {@code joinSlot}
 * <b>consulte</b> la garde, avec les bons arguments et la bonne porte, et n'écrit
 * rien si elle refuse ({@link #joinSlot_doitConsulterLaGardeDEntree_etNeRienEcrireSiElleRefuse}) ;
 * et les règles qui n'ont jamais quitté ce service — l'état de la participation,
 * la réactivation d'une ligne désistée, l'ouverture de la conversation.
 */
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

    // Non stubbés, pour la même raison que conflictDetector : ces deux-là écrivent
    // sur le créneau et sur la file, et ce que cette classe vérifie — les refus
    // d'entrée, et l'ouverture de conversation — se joue avant ou après eux. Ils
    // doivent seulement exister : sans déclaration, @InjectMocks les laisse à null
    // et joinSlot tombe sur un NullPointerException qui ne dit rien du test.
    @Mock ParticipantCounter participantCounter;
    @Mock WaitlistPromoter waitlistPromoter;

    // Même raison, et le commentaire ci-dessus avait décrit le piège d'avance :
    // P-BL-09 a sorti les refus d'entrée de joinSlot pour les porter dans une garde
    // commune aux deux portes d'inscription, et sans cette déclaration les six tests
    // de cette classe tombaient sur « this.entryGuard is null ».
    //
    // Bouchon muet par défaut, comme conflictDetector : « la garde ne refuse rien »
    // est le bon décor pour les chemins heureux de cette classe. Un seul test la
    // fait lever, et c'est celui de la délégation — les règles qu'elle porte se
    // prouvent ailleurs (voir la javadoc de la classe).
    @Mock SlotEntryGuard entryGuard;

    // BlockFilterService avait disparu des collaborateurs de SlotService avec
    // P-BL-09 : le blocage à l'ENTRÉE est en tête de SlotEntryGuard, qui le porte
    // pour les deux portes. Il est revenu avec P-BL-05, pour les deux LECTURES que
    // la garde d'entrée ne couvre pas — la fiche d'un créneau (404 quand un
    // blocage sépare l'appelant de l'organisateur) et « mes créneaux ».
    //
    // Déclaré bien qu'aucun test de cette classe ne le sollicite, pour la raison
    // que les commentaires ci-dessus décrivent deux fois : sans déclaration,
    // @InjectMocks laisse le champ à null, et le premier test de lecture écrit ici
    // tomberait sur un NullPointerException qui ne dit rien de ce qu'il vérifie.
    @Mock BlockFilterService blockFilterService;

    @InjectMocks
    SlotService slotService;

    /**
     * La délégation, et elle seule : le créneau verrouillé est soumis à la garde,
     * sous la porte {@code SLOT}, et un refus arrête tout avant la première
     * écriture.
     *
     * <p><b>Ce que ce test ne prouve pas, et pourquoi c'est assumé.</b> Il ne dit
     * rien de ce que la garde refuse — le refus est celui qu'on vient de lui faire
     * lever. Les règles elles-mêmes se prouvent contre une vraie base, sur les
     * deux portes, et la javadoc de cette classe dit où. Ce qui reste ici est le
     * seul fait qui appartienne encore à {@code joinSlot} : qu'il demande, à cet
     * endroit-là de la méthode, et qu'il n'écrive rien si la réponse est non.
     *
     * <p>{@code verifyNoInteractions} sur les trois collaborateurs d'écriture est
     * la vraie assertion : un jour où quelqu'un déplacerait la garde après la
     * création de la participation, l'exception continuerait de remonter — et
     * l'appelant aurait, lui, une ligne de trop.
     */
    @Test
    void joinSlot_doitConsulterLaGardeDEntree_etNeRienEcrireSiElleRefuse() {
        UUID hostId = UUID.randomUUID();
        UUID joinerId = UUID.randomUUID();
        Schedule slot = buildOpenSlot(hostId, Instant.now().plus(1, ChronoUnit.DAYS));
        when(scheduleRepository.lockById(slot.getId())).thenReturn(Optional.of(slot));

        doThrow(new ValidationException(ErrorCode.SLOT_NOT_ACCEPTING_PARTICIPANTS,
                "refus posé par le test, pas par la règle"))
            .when(entryGuard).assertMayEnter(eq(joinerId), eq(slot), any(Instant.class),
                eq(SlotEntryGuard.Door.SLOT));

        assertThatThrownBy(() -> slotService.joinSlot(joinerId, slot.getId(), new JoinSlotRequest(null)))
            .isInstanceOf(ValidationException.class);

        verify(entryGuard).assertMayEnter(eq(joinerId), eq(slot), any(Instant.class),
            eq(SlotEntryGuard.Door.SLOT));
        verifyNoInteractions(participationRepository, chatService, notificationService);
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

        // La surcharge à quatre arguments, et pas celle à deux : c'est celle que
        // joinSlot appelle. Le never() portait sur l'autre — que ce service
        // n'appelle nulle part — si bien que l'assertion aurait tenu même si une
        // conversation s'était ouverte. Un test vert qui ne prouve pas son titre
        // est pire qu'un test absent : il fait croire la règle gardée.
        verify(chatService, never()).createConversation(any(), any(), any(), any());
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
