package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.chat.ChatService;
import org.program.pair.domain.chat.dto.CreateConversationRequest;
import org.program.pair.domain.chat.dto.SendMessageRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramService;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotService;
import org.program.pair.domain.program.dto.CreateProgramRequest;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.program.dto.ProgramDto;
import org.program.pair.domain.program.dto.UpdateProgramRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L'auteur d'un programme choisit s'il accepte les messages de ses participants.
 *
 * <p>Le réglage n'est pas un drapeau d'affichage : le refus s'applique côté
 * serveur, à l'ouverture d'une conversation <b>et</b> à l'envoi dans un fil déjà
 * ouvert. Ne garder que le premier laisserait passer tout participant ayant déjà
 * écrit une fois — et comme rejoindre un créneau ouvre une conversation, c'est le
 * cas de presque tous.
 *
 * <p>La lecture n'est jamais touchée : « lecture seule » veut dire lecture.
 */
class ProgramMessagePermissionIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ProgramService programService;
    @Autowired SlotService slotService;
    @Autowired ChatService chatService;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void leDefaut_doitEtreDAccepterLesMessages() {
        // Le produit met des gens en relation : un programme muet par défaut
        // prendrait tout le monde à contre-pied. L'auteur restreint, il n'ouvre pas.
        User author = register("perm-default-author@pair.app");
        UserActivity yoga = yogaOf(author);

        ProgramDto created = programService.createProgram(author.getId(),
            new CreateProgramRequest(yoga.getId(), "Yoga ouvert", "Description", true, null,
                null, null, null, null, null, null, null, null, null, null));

        assertThat(created.allowParticipantMessages()).isTrue();
    }

    @Test
    void lAuteurQuiRefuse_doitBloquerLOuvertureParUnParticipant() {
        User author = register("perm-refuse-author@pair.app");
        User participant = register("perm-refuse-participant@pair.app");
        Program program = programWithMessages(author, "Yoga fermé", false);

        assertThatThrownBy(() -> chatService.createConversation(
            participant.getId(),
            new CreateConversationRequest(author.getId(), null, program.getId())))
            .isInstanceOf(ForbiddenException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROGRAM_MESSAGES_DISABLED);

        // Un refus ne doit rien laisser derrière lui.
        assertThat(chatService.getMyConversations(participant.getId())).isEmpty();
    }

    @Test
    void lAuteur_doitGarderLeDroitDEcrireDansLesDeuxSens() {
        User author = register("perm-author-writes@pair.app");
        User participant = register("perm-author-writes-p@pair.app");
        Program program = programWithMessages(author, "Yoga fermé", false);

        assertThatCode(() -> chatService.createConversation(
            author.getId(),
            new CreateConversationRequest(participant.getId(), null, program.getId())))
            .doesNotThrowAnyException();

        UUID conversationId = chatService.getMyConversations(author.getId()).get(0).id();
        assertThatCode(() -> chatService.sendMessage(author.getId(),
            new SendMessageRequest(conversationId, "Bonjour à tous")))
            .doesNotThrowAnyException();
    }

    @Test
    void leRefusPosteApres_doitFigerUnFilDejaOuvert() {
        // Le cas qui compte : la conversation existe déjà — souvent parce que le
        // participant a rejoint un créneau — et l'auteur ferme ensuite. Ne
        // vérifier qu'à l'ouverture rendrait le réglage sans effet sur elle.
        User author = register("perm-later-author@pair.app");
        User participant = register("perm-later-participant@pair.app");
        Program program = programWithMessages(author, "Yoga d'abord ouvert", true);

        chatService.createConversation(participant.getId(),
            new CreateConversationRequest(author.getId(), null, program.getId()));
        UUID conversationId = chatService.getMyConversations(participant.getId()).get(0).id();

        assertThatCode(() -> chatService.sendMessage(participant.getId(),
            new SendMessageRequest(conversationId, "Avant fermeture")))
            .doesNotThrowAnyException();

        programService.updateProgram(author.getId(), program.getId(),
            new UpdateProgramRequest(null, null, null, null, false,
                null, null, null, null, null, null, null, null, null, null));

        assertThatThrownBy(() -> chatService.sendMessage(participant.getId(),
            new SendMessageRequest(conversationId, "Après fermeture")))
            .isInstanceOf(ForbiddenException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROGRAM_MESSAGES_DISABLED);

        // Lecture seule veut dire lecture : le fil et son historique restent
        // accessibles au participant.
        assertThat(chatService.getMessages(participant.getId(), conversationId, 50))
            .singleElement()
            .satisfies(message -> assertThat(message.content()).isEqualTo("Avant fermeture"));
    }

    @Test
    void rejoindreUnCreneau_neDoitPasEchouer_quandLAuteurRefuseLesMessages() {
        // Rejoindre et écrire sont deux choses. Fermer sa messagerie ne ferme pas
        // ses créneaux : l'inscription aboutit, seule l'ouverture du fil saute.
        User author = register("perm-slot-author@pair.app");
        User joiner = register("perm-slot-joiner@pair.app");
        Program program = programWithMessages(author, "Yoga fermé", false);
        Schedule slot = slotOf(program);

        assertThatCode(() -> slotService.joinSlot(joiner.getId(), slot.getId(), new JoinSlotRequest(null)))
            .doesNotThrowAnyException();

        assertThat(chatService.getMyConversations(joiner.getId())).isEmpty();
    }

    @Test
    void sansProgrammeDansLeCorps_rienNEstRefuse() {
        // Une conversation ouverte depuis un profil ne met aucun programme en jeu.
        // C'est la limite assumée du réglage : sans programId, le serveur ne sait
        // pas quel réglage consulter — une activité porte N programmes.
        User author = register("perm-noprog-author@pair.app");
        User participant = register("perm-noprog-participant@pair.app");
        programWithMessages(author, "Yoga fermé", false);

        assertThatCode(() -> chatService.createConversation(
            participant.getId(),
            new CreateConversationRequest(author.getId(), null, null)))
            .doesNotThrowAnyException();
    }

    private Program programWithMessages(User author, String title, boolean allow) {
        return programRepository.save(Program.builder()
            .userActivity(yogaOf(author))
            .title(title)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .allowParticipantMessages(allow)
            .build());
    }

    private Schedule slotOf(Program program) {
        return scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Studio test")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Test")
            .location(geometryFactory.createPoint(new Coordinate(2.35, 48.85)))
            .startsAt(Instant.now().plus(1, ChronoUnit.DAYS))
            .isOpenToPartners(true)
            .build());
    }

    private UserActivity yogaOf(User user) {
        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        return userActivityRepository.save(
            UserActivity.builder().user(user).activity(yoga).build());
    }

    private User register(String email) {
        RegisterRequest registerReq = new RegisterRequest(email, "Password123!", email.split("@")[0]);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();
        return userRepository.findByEmail(email).orElseThrow();
    }
}
