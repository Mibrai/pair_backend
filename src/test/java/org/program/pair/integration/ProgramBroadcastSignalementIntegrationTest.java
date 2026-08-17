package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.chat.ChatService;
import org.program.pair.domain.chat.dto.ConversationSummaryDto;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.notification.PushNotificationServiceInterface;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Ce qui avertit un participant qu'une diffusion est arrivée.
 *
 * <p>Le fil, lui, fonctionnait : il naît, il apparaît des deux côtés, ses
 * messages se lisent. Ce que ces tests verrouillent est l'étage au-dessus — le
 * <b>signalement</b> —, dont l'absence rendait la fonctionnalité inutilisable en
 * pratique : un auteur qui diffuse croit avoir prévenu son groupe, et n'a
 * prévenu personne.
 *
 * <p><b>Toujours la première diffusion, jamais précédée d'une lecture.</b> C'est
 * la condition qui distingue ces tests de {@link ProgramBroadcastIntegrationTest} :
 * là-bas, le compte de non-lus n'est mesuré qu'après un {@code markAsRead}, qui
 * crée la ligne de {@code conversation_members} au passage. Le défaut se logeait
 * exactement dans l'intervalle que cet appel refermait.
 */
class ProgramBroadcastSignalementIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired UserProgramRepository userProgramRepository;
    @Autowired ChatService chatService;

    @MockitoBean PushNotificationServiceInterface pushService;

    @Test
    void unePremiereDiffusion_doitCompterAuBadgeDuParticipant() {
        User author = register("sig-badge-author@pair.app");
        User participant = register("sig-badge-participant@pair.app");
        Program program = program(author, "Yoga signalé");
        enroll(participant, program, UserProgramStatus.ACTIVE);

        chatService.broadcastToProgram(author.getId(), program.getId(), "Séance déplacée à 19h");

        // Aucune lecture préalable : le participant n'a pas de ligne de membre, et
        // c'est justement le cas normal — il n'a encore jamais ouvert ce fil, qui
        // n'existait pas avant cette diffusion.
        assertThat(chatService.getUnreadCount(participant.getId())).isEqualTo(1);
    }

    @Test
    void unePremiereDiffusion_doitCompterDansLeFil() {
        User author = register("sig-fil-author@pair.app");
        User participant = register("sig-fil-participant@pair.app");
        Program program = program(author, "Yoga compté");
        enroll(participant, program, UserProgramStatus.ACTIVE);

        chatService.broadcastToProgram(author.getId(), program.getId(), "Première annonce");

        ConversationSummaryDto thread = chatService.getMyConversations(participant.getId()).get(0);
        // Le client somme ce champ pour son badge : un total qui ne retombe pas sur
        // getUnreadCount ferait diverger le badge selon la façon dont il est calculé.
        assertThat(thread.unreadCount()).isEqualTo(1);
    }

    @Test
    void uneDiffusionLue_neDoitPlusCompter() {
        User author = register("sig-lu-author@pair.app");
        User participant = register("sig-lu-participant@pair.app");
        Program program = program(author, "Yoga lu");
        enroll(participant, program, UserProgramStatus.ACTIVE);

        chatService.broadcastToProgram(author.getId(), program.getId(), "À lire");
        UUID threadId = chatService.getMyConversations(participant.getId()).get(0).id();
        chatService.markAsRead(participant.getId(), threadId);

        assertThat(chatService.getUnreadCount(participant.getId())).isZero();

        chatService.broadcastToProgram(author.getId(), program.getId(), "À lire aussi");
        assertThat(chatService.getUnreadCount(participant.getId())).isEqualTo(1);
    }

    @Test
    void lAuteur_neDoitPasCompterSesPropresDiffusions() {
        User author = register("sig-auteur@pair.app");
        Program program = program(author, "Yoga solitaire");

        chatService.broadcastToProgram(author.getId(), program.getId(), "Ma propre annonce");

        // Envoyer n'est pas recevoir.
        assertThat(chatService.getUnreadCount(author.getId())).isZero();
    }

    @Test
    void unNouvelInscrit_doitTrouverLHistoriqueNonLu() {
        User author = register("sig-inscrit-author@pair.app");
        User newcomer = register("sig-inscrit@pair.app");
        Program program = program(author, "Yoga rejoint");

        chatService.broadcastToProgram(author.getId(), program.getId(), "Écrit avant son arrivée");
        assertThat(chatService.getUnreadCount(newcomer.getId())).isZero();

        enroll(newcomer, program, UserProgramStatus.ACTIVE);

        // Il gagne le fil et son historique : cet historique lui est donc non lu,
        // au même titre que le fil lui est visible — sans qu'aucune ligne n'ait eu
        // à être écrite derrière lui.
        assertThat(chatService.getUnreadCount(newcomer.getId())).isEqualTo(1);
    }

    @Test
    void unPartant_neDoitPasGarderLeCompteDUnFilQuIlNeVoitPlus() {
        User author = register("sig-partant-author@pair.app");
        User leaver = register("sig-partant@pair.app");
        Program program = program(author, "Yoga quitté");
        UserProgram enrollment = enroll(leaver, program, UserProgramStatus.ACTIVE);

        chatService.broadcastToProgram(author.getId(), program.getId(), "Non lu");
        assertThat(chatService.getUnreadCount(leaver.getId())).isEqualTo(1);

        enrollment.setStatus(UserProgramStatus.LEFT);
        userProgramRepository.save(enrollment);

        // Sans cette exclusion il garderait au badge un nombre qu'il lui serait
        // impossible de faire retomber, le fil lui étant devenu inaccessible.
        assertThat(chatService.getUnreadCount(leaver.getId())).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unePremiereDiffusion_doitPartirEnPushAvecSonBadge() {
        User author = register("sig-push-author@pair.app");
        User participant = register("sig-push-participant@pair.app");
        Program program = program(author, "Yoga poussé");
        enroll(participant, program, UserProgramStatus.ACTIVE);

        chatService.broadcastToProgram(author.getId(), program.getId(), "Séance annulée");

        ArgumentCaptor<Long> badge = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);

        // La push part après le commit, et son envoi est @Async : d'où le délai.
        verify(pushService, timeout(10_000)).sendPush(
            eq(participant.getId()),
            eq(NotificationType.PROGRAM_BROADCAST),
            payload.capture(),
            badge.capture());

        // Le payload est sérialisé pour FCM : ses valeurs sont des chaînes.
        assertThat(payload.getValue()).containsEntry("programId", program.getId().toString());
        assertThat(payload.getValue()).containsEntry("programTitle", "Yoga poussé");
        // Un badge à zéro efface l'icône au lieu de l'incrémenter : la bannière
        // s'afficherait, et le nombre disparaîtrait dans le même mouvement.
        assertThat(badge.getValue()).isEqualTo(1L);
    }

    @Test
    void lExpediteur_neDoitPasRecevoirSaPropreDiffusion() {
        User author = register("sig-nopush-author@pair.app");
        User participant = register("sig-nopush-participant@pair.app");
        Program program = program(author, "Yoga sans écho");
        enroll(participant, program, UserProgramStatus.ACTIVE);

        chatService.broadcastToProgram(author.getId(), program.getId(), "Bonjour");

        verify(pushService, timeout(10_000)).sendPush(
            eq(participant.getId()), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(pushService, org.mockito.Mockito.never()).sendPush(
            eq(author.getId()), any(), any(), org.mockito.ArgumentMatchers.anyLong());
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
