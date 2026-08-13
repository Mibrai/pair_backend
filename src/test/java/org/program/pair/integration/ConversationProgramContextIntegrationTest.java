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
import org.program.pair.domain.chat.dto.ConversationDetailDto;
import org.program.pair.domain.chat.dto.ConversationSummaryDto;
import org.program.pair.domain.chat.dto.CreateConversationRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotService;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le contexte d'une conversation : le programme, l'activité et la séance qui
 * lient les deux personnes.
 *
 * <p>Ce que ces tests verrouillent, et qui était faux : {@code activityContextName}
 * existait dans le contrat depuis l'origine et valait {@code null} en toutes
 * circonstances — un {@code TODO Phase 2} codé en dur. La colonne
 * {@code activity_context_id}, créée par V6, n'était écrite nulle part :
 * {@code ChatService} recevait l'identifiant et le jetait. Rejoindre un créneau
 * ouvrait donc une conversation dite « contextualisée » qui ne portait aucun
 * contexte, et l'en-tête du client restait vide.
 *
 * <p>La date du créneau est celle que le client compare à maintenant pour griser
 * un fil dont la séance est passée. Sans elle la règle n'a aucun déclencheur.
 */
class ConversationProgramContextIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotService slotService;
    @Autowired ChatService chatService;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void rejoindreUnCreneau_doitPorterProgrammeActiviteEtDatesJusquAuxDeuxDto() {
        User host = register("ctx-host@pair.app");
        User joiner = register("ctx-joiner@pair.app");

        Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant endsAt = startsAt.plus(90, ChronoUnit.MINUTES);
        Schedule slot = createSlot(yogaOf(host), "Yoga du matin", startsAt, endsAt);

        slotService.joinSlot(joiner.getId(), slot.getId(), new JoinSlotRequest(null));

        List<ConversationSummaryDto> conversations = chatService.getMyConversations(joiner.getId());
        assertThat(conversations).hasSize(1);
        ConversationSummaryDto summary = conversations.get(0);

        assertThat(summary.programId()).isEqualTo(slot.getProgram().getId());
        assertThat(summary.programTitle()).isEqualTo("Yoga du matin");
        assertThat(summary.activityName()).isEqualTo("Yoga");
        // Doublé, et c'est voulu : le client lit activityName aux côtés de
        // programTitle, l'ancien nom reste servi pour ne rien casser.
        assertThat(summary.activityContextName()).isEqualTo(summary.activityName());
        assertThat(summary.scheduleId()).isEqualTo(slot.getId());
        assertThat(summary.scheduleStartsAt()).isEqualTo(startsAt);
        assertThat(summary.scheduleEndsAt()).isEqualTo(endsAt);

        // L'écran de conversation affiche le même en-tête sans repasser par la
        // liste : le détail doit porter le contexte, pas seulement le résumé.
        ConversationDetailDto detail =
            chatService.getConversationDetail(joiner.getId(), summary.id());

        assertThat(detail.programId()).isEqualTo(slot.getProgram().getId());
        assertThat(detail.programTitle()).isEqualTo("Yoga du matin");
        assertThat(detail.activityName()).isEqualTo("Yoga");
        assertThat(detail.scheduleId()).isEqualTo(slot.getId());
        assertThat(detail.scheduleStartsAt()).isEqualTo(startsAt);
        assertThat(detail.scheduleEndsAt()).isEqualTo(endsAt);
    }

    @Test
    void uneConversationNeeHorsProgramme_doitResterSansContexte() {
        // Ouverte depuis un profil : le client affiche alors l'en-tête sans
        // contexte et ne grise rien. Ces champs doivent être nullables et le
        // rester — les remplir au jugé désignerait la mauvaise séance.
        User a = register("ctx-plain-a@pair.app");
        User b = register("ctx-plain-b@pair.app");

        chatService.createConversation(a.getId(), new CreateConversationRequest(b.getId(), null, null));

        ConversationSummaryDto summary = chatService.getMyConversations(a.getId()).get(0);

        assertThat(summary.programId()).isNull();
        assertThat(summary.programTitle()).isNull();
        assertThat(summary.activityName()).isNull();
        assertThat(summary.activityContextName()).isNull();
        assertThat(summary.scheduleId()).isNull();
        assertThat(summary.scheduleStartsAt()).isNull();
        assertThat(summary.scheduleEndsAt()).isNull();
    }

    @Test
    void rejoindreUnSecondCreneau_doitRafraichirLeContexteDuFilExistant() {
        // Deux personnes qui se retrouvent sur une nouvelle séance gardent leur
        // fil. Y laisser la première séance figerait l'en-tête sur une date
        // passée, et le client grise sur cette date : le fil se grise alors
        // qu'un créneau est à venir.
        User host = register("ctx-refresh-host@pair.app");
        User joiner = register("ctx-refresh-joiner@pair.app");

        Instant firstStart = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        UserActivity hostYoga = yogaOf(host);
        Schedule first = createSlot(hostYoga, "Yoga du matin", firstStart, firstStart.plus(1, ChronoUnit.HOURS));
        slotService.joinSlot(joiner.getId(), first.getId(), new JoinSlotRequest(null));

        Instant secondStart = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Schedule second = createSlot(hostYoga, "Yoga du soir", secondStart, secondStart.plus(1, ChronoUnit.HOURS));
        slotService.joinSlot(joiner.getId(), second.getId(), new JoinSlotRequest(null));

        List<ConversationSummaryDto> conversations = chatService.getMyConversations(joiner.getId());
        assertThat(conversations).hasSize(1);

        ConversationSummaryDto summary = conversations.get(0);
        assertThat(summary.scheduleId()).isEqualTo(second.getId());
        assertThat(summary.scheduleStartsAt()).isEqualTo(secondStart);
        assertThat(summary.programTitle()).isEqualTo("Yoga du soir");
    }

    /**
     * Une seule activité-utilisateur par hôte : {@code uq_user_activity} interdit
     * le doublon, et deux programmes de la même activité sont précisément le cas
     * qui rend le contexte nécessaire.
     */
    private UserActivity yogaOf(User host) {
        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();
        return userActivityRepository.save(
            UserActivity.builder().user(host).activity(yoga).build());
    }

    private Schedule createSlot(UserActivity userActivity, String programTitle,
                                Instant startsAt, Instant endsAt) {
        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title(programTitle)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        return scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Studio test")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("1 rue du Test")
            .location(geometryFactory.createPoint(new Coordinate(2.35, 48.85)))
            .startsAt(startsAt)
            .endsAt(endsAt)
            .isOpenToPartners(true)
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
