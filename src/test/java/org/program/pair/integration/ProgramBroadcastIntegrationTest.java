package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.chat.ChatService;
import org.program.pair.domain.chat.dto.ConversationDetailDto;
import org.program.pair.domain.chat.dto.ConversationSummaryDto;
import org.program.pair.domain.chat.dto.MessageDto;
import org.program.pair.domain.chat.dto.SendMessageRequest;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.UserProgram;
import org.program.pair.domain.program.UserProgramStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserProgramRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le fil de diffusion d'un programme : l'auteur écrit, les participants lisent.
 *
 * <p>Ce que ces tests verrouillent avant tout, c'est que l'appartenance est
 * <b>dérivée</b> des inscriptions actives et jamais recopiée. Une liste figée au
 * moment de l'envoi divergerait dès la première inscription : le nouvel inscrit
 * ne verrait rien, le partant continuerait de tout voir. Ici, le premier gagne le
 * fil et son historique sans qu'on ait rien propagé, et le second le perd le jour
 * même — y compris ce qui a été écrit avant son départ.
 */
class ProgramBroadcastIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired UserProgramRepository userProgramRepository;
    @Autowired ChatService chatService;

    @Test
    void uneDiffusion_doitAtteindreLesParticipantsActifs() {
        User author = register("bc-author@pair.app");
        User participant = register("bc-participant@pair.app");
        Program program = program(author, "Yoga du matin");
        enroll(participant, program, UserProgramStatus.ACTIVE);

        MessageDto sent = chatService.broadcastToProgram(
            author.getId(), program.getId(), "Séance déplacée à 19h");

        List<ConversationSummaryDto> threads = chatService.getMyConversations(participant.getId());
        assertThat(threads).hasSize(1);

        ConversationSummaryDto thread = threads.get(0);
        assertThat(thread.type()).isEqualTo("PROGRAM_BROADCAST");
        assertThat(thread.programId()).isEqualTo(program.getId());
        // otherUser n'a pas de sens à trente personnes : c'est le titre qui nomme
        // le fil, pas un interlocuteur.
        assertThat(thread.otherUser()).isNull();
        assertThat(thread.title()).isEqualTo("Yoga du matin");
        assertThat(thread.memberCount()).isEqualTo(2);
        assertThat(thread.lastMessageContent()).isEqualTo("Séance déplacée à 19h");

        assertThat(chatService.getMessages(participant.getId(), thread.id(), 50))
            .singleElement()
            .satisfies(m -> assertThat(m.id()).isEqualTo(sent.id()));
    }

    @Test
    void leFil_neDoitNaitreQuALaPremiereDiffusion_etResterUnique() {
        User author = register("bc-unique-author@pair.app");
        Program program = program(author, "Yoga unique");

        // Avant toute diffusion : aucun fil. Créer le fil à la création du
        // programme peuplerait la base de conversations vides.
        assertThat(chatService.getMyConversations(author.getId())).isEmpty();

        chatService.broadcastToProgram(author.getId(), program.getId(), "Premier");
        chatService.broadcastToProgram(author.getId(), program.getId(), "Deuxième");

        List<ConversationSummaryDto> threads = chatService.getMyConversations(author.getId());
        assertThat(threads).hasSize(1);
        assertThat(chatService.getMessages(author.getId(), threads.get(0).id(), 50)).hasSize(2);
    }

    @Test
    void unNouvelInscrit_doitGagnerLeFilEtSonHistorique() {
        // Le cas qu'une liste recopiée manquerait : la diffusion est partie avant
        // l'inscription, et pourtant elle doit être lisible.
        User author = register("bc-newcomer-author@pair.app");
        User newcomer = register("bc-newcomer@pair.app");
        Program program = program(author, "Yoga du soir");

        chatService.broadcastToProgram(author.getId(), program.getId(), "Écrit avant son arrivée");

        assertThat(chatService.getMyConversations(newcomer.getId())).isEmpty();

        enroll(newcomer, program, UserProgramStatus.ACTIVE);

        List<ConversationSummaryDto> threads = chatService.getMyConversations(newcomer.getId());
        assertThat(threads).hasSize(1);
        assertThat(chatService.getMessages(newcomer.getId(), threads.get(0).id(), 50))
            .singleElement()
            .satisfies(m -> assertThat(m.content()).isEqualTo("Écrit avant son arrivée"));
    }

    @Test
    void unParticipantParti_doitPerdreLeFilEtSonHistorique() {
        User author = register("bc-leaver-author@pair.app");
        User leaver = register("bc-leaver@pair.app");
        Program program = program(author, "Yoga quitté");
        UserProgram enrollment = enroll(leaver, program, UserProgramStatus.ACTIVE);

        chatService.broadcastToProgram(author.getId(), program.getId(), "Pendant qu'il était là");
        UUID threadId = chatService.getMyConversations(leaver.getId()).get(0).id();

        // Il a lu : une ligne de membre existe désormais, avec son lastReadAt.
        chatService.markAsRead(leaver.getId(), threadId);

        enrollment.setStatus(UserProgramStatus.LEFT);
        userProgramRepository.save(enrollment);

        // La ligne de membre subsiste et ne rouvre rien : l'accès se dérive du
        // programme, pas d'elle.
        assertThat(chatService.getMyConversations(leaver.getId())).isEmpty();
        assertThatThrownBy(() -> chatService.getMessages(leaver.getId(), threadId, 50))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void leBadgeDUnPartant_neDoitPasResterBloque() {
        // Sans exclusion des fils de diffusion, le partant garderait au badge des
        // messages d'un fil qu'il ne peut plus ouvrir — donc un nombre qu'il lui
        // serait impossible de faire retomber.
        User author = register("bc-badge-author@pair.app");
        User leaver = register("bc-badge-leaver@pair.app");
        Program program = program(author, "Yoga badge");
        UserProgram enrollment = enroll(leaver, program, UserProgramStatus.ACTIVE);

        chatService.broadcastToProgram(author.getId(), program.getId(), "Non lu");
        UUID threadId = chatService.getMyConversations(leaver.getId()).get(0).id();
        chatService.markAsRead(leaver.getId(), threadId);
        chatService.broadcastToProgram(author.getId(), program.getId(), "Non lu aussi");

        assertThat(chatService.getUnreadCount(leaver.getId())).isEqualTo(1);

        enrollment.setStatus(UserProgramStatus.LEFT);
        userProgramRepository.save(enrollment);

        assertThat(chatService.getUnreadCount(leaver.getId())).isZero();
    }

    @Test
    void unParticipant_neDoitPasPouvoirEcrireDansLeFil() {
        User author = register("bc-readonly-author@pair.app");
        User participant = register("bc-readonly-participant@pair.app");
        Program program = program(author, "Yoga lecture seule");
        enroll(participant, program, UserProgramStatus.ACTIVE);

        chatService.broadcastToProgram(author.getId(), program.getId(), "Bonjour à tous");
        UUID threadId = chatService.getMyConversations(participant.getId()).get(0).id();

        assertThatThrownBy(() -> chatService.sendMessage(participant.getId(),
            new SendMessageRequest(threadId, "Moi aussi je veux parler")))
            .isInstanceOf(ForbiddenException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROGRAM_BROADCAST_READ_ONLY);

        // L'auteur, lui, peut continuer par la route ordinaire des conversations :
        // le client n'a pas à connaître deux chemins d'écriture.
        assertThatCode(() -> chatService.sendMessage(author.getId(),
            new SendMessageRequest(threadId, "Suite")))
            .doesNotThrowAnyException();
    }

    @Test
    void unNonParticipant_neDoitRienVoirNiPouvoirDiffuser() {
        User author = register("bc-outsider-author@pair.app");
        User outsider = register("bc-outsider@pair.app");
        Program program = program(author, "Yoga privé");

        chatService.broadcastToProgram(author.getId(), program.getId(), "Réservé aux inscrits");
        UUID threadId = chatService.getMyConversations(author.getId()).get(0).id();

        assertThatThrownBy(() -> chatService.getMessages(outsider.getId(), threadId, 50))
            .isInstanceOf(ForbiddenException.class);

        assertThatThrownBy(() -> chatService.broadcastToProgram(
            outsider.getId(), program.getId(), "Je diffuse chez les autres"))
            .isInstanceOf(ForbiddenException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROGRAM_BROADCAST_READ_ONLY);
    }

    @Test
    void leDetailDuFil_doitEnumererLesMembresDerives() {
        User author = register("bc-detail-author@pair.app");
        User first = register("bc-detail-first@pair.app");
        User second = register("bc-detail-second@pair.app");
        Program program = program(author, "Yoga détaillé");
        enroll(first, program, UserProgramStatus.ACTIVE);
        enroll(second, program, UserProgramStatus.ACTIVE);

        chatService.broadcastToProgram(author.getId(), program.getId(), "Bonjour");
        UUID threadId = chatService.getMyConversations(first.getId()).get(0).id();

        ConversationDetailDto detail = chatService.getConversationDetail(first.getId(), threadId);

        assertThat(detail.title()).isEqualTo("Yoga détaillé");
        assertThat(detail.memberCount()).isEqualTo(3);
        // Dérivés, pas lus dans conversation_members : seuls l'auteur et le
        // premier ont une ligne de lecture, le fil en compte pourtant trois.
        assertThat(detail.members()).hasSize(3)
            .extracting(m -> m.id())
            .containsExactlyInAnyOrder(author.getId(), first.getId(), second.getId());
    }

    private Program program(User author, String title) {
        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(author).activity(yoga).build());
        return programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title(title)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());
    }

    private UserProgram enroll(User user, Program program, UserProgramStatus status) {
        return userProgramRepository.save(UserProgram.builder()
            .user(user)
            .program(program)
            .status(status)
            .build());
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
