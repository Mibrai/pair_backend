package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.block.BlockService;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramEnrollmentService;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotService;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserProgramRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.HasErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * P-BL-09 — {@code POST /programs/{id}/join} contournait tout.
 *
 * <p>Deux portes ouvrent la même séance : {@code POST /slots/{id}/join} et
 * {@code POST /programs/{id}/join} avec un {@code scheduleId}. La première
 * vérifiait le blocage, le statut, la séance commencée et l'ouverture aux
 * partenaires ; la seconde n'en vérifiait aucun — le mot « blocage »
 * n'apparaissait pas une fois dans son fichier. Une personne bloquée par
 * l'organisateur entrait donc par l'autre chemin sur le créneau même dont le
 * blocage l'avait écartée.
 *
 * <p>Les refus vivent désormais dans {@code SlotEntryGuard}, écrits une fois.
 * Ces tests-ci existent surtout pour interdire qu'ils redeviennent deux.
 */
class ProgramJoinGuardsIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired UserProgramRepository userProgramRepository;
    @Autowired ProgramEnrollmentService enrollmentService;
    @Autowired SlotService slotService;
    @Autowired BlockService blockService;

    /** Par quelle porte on entre. Les deux doivent refuser la même chose. */
    enum Porte { CRENEAU, PROGRAMME }

    @Test
    void unePersonneBloqueeParLOrganisateur_neDoitPasEntrerParLaPorteProgramme() {
        Fixture f = creneau(SlotStatus.OPEN, Instant.now().plus(Duration.ofDays(2)), true);
        blockService.block(f.hostId, f.candidateId, null);

        assertThatThrownBy(() -> entrer(Porte.PROGRAMME, f))
            .as("404 et non un refus nommé : le créneau a déjà disparu de son fil, "
                + "il ne doit pas réapparaître par son identifiant")
            .isInstanceOf(ResourceNotFoundException.class);

        assertThat(userProgramRepository.existsByUserIdAndProgramIdAndStatusActive(
                f.candidateId, f.programId))
            .as("aucune ligne user_programs ne doit rester derrière le refus")
            .isFalse();
    }

    @Test
    void unePersonneQuiABloqueLOrganisateur_doitSavoirPourquoi() {
        // L'autre forme du blocage, et la réponse opposée : c'est sa propre
        // décision, elle a droit à un refus nommé.
        Fixture f = creneau(SlotStatus.OPEN, Instant.now().plus(Duration.ofDays(2)), true);
        blockService.block(f.candidateId, f.hostId, null);

        assertThat(codeDuRefus(Porte.PROGRAMME, f)).isEqualTo(ErrorCode.USER_BLOCKED);
    }

    @Test
    void rejoindreLeProgrammeEntier_enEtantBloque_doitAussiEtreRefuse() {
        // Sans scheduleId, l'inscription porte sur tout le programme : il n'y a
        // pas de créneau à confronter, mais il y a un organisateur. Ce chemin
        // ne vérifiait rien du tout.
        Fixture f = creneau(SlotStatus.OPEN, Instant.now().plus(Duration.ofDays(2)), true);
        blockService.block(f.hostId, f.candidateId, null);

        assertThatThrownBy(() -> enrollmentService.joinProgram(f.candidateId, f.programId, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejoindreLeProgrammeEntier_sansBlocage_doitToujoursMarcher() {
        // Non-régression : l'inscription au programme entier porte sur des
        // créneaux dont certains sont passés, et cela ne doit pas la refuser.
        Fixture f = creneau(SlotStatus.OPEN, Instant.now().minus(Duration.ofDays(3)), true);

        assertThat(enrollmentService.joinProgram(f.candidateId, f.programId, null)).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(Porte.class)
    void uneSeanceDejaCommencee_doitEtreRefusee_parLesDeuxPortes(Porte porte) {
        Fixture f = creneau(SlotStatus.OPEN, Instant.now().minus(Duration.ofMinutes(30)), true);

        assertThat(codeDuRefus(porte, f)).isEqualTo(ErrorCode.SLOT_ALREADY_STARTED);
    }

    @ParameterizedTest
    @EnumSource(Porte.class)
    void uneSeanceAnnulee_doitEtreRefusee_parLesDeuxPortes(Porte porte) {
        Fixture f = creneau(SlotStatus.CANCELLED, Instant.now().plus(Duration.ofDays(2)), true);

        assertThat(codeDuRefus(porte, f))
            .isEqualTo(ErrorCode.SLOT_NOT_ACCEPTING_PARTICIPANTS);
    }

    @Test
    void uneSeanceFermeeAuxPartenaires_doitEtreRefusee_parLaPorteCreneau() {
        // La seule différence assumée entre les deux portes, et elle attend une
        // décision produit : isOpenToPartners=false sert peut-être à des
        // inscriptions « programme » légitimes — un créneau réservé aux inscrits
        // du programme. Tant que la question n'est pas tranchée, la porte
        // programme reste comme elle était. Voir le TODO de SlotEntryGuard.
        Fixture f = creneau(SlotStatus.OPEN, Instant.now().plus(Duration.ofDays(2)), false);

        assertThat(codeDuRefus(Porte.CRENEAU, f))
            .isEqualTo(ErrorCode.SLOT_NOT_OPEN_TO_PARTNERS);

        assertThat(enrollmentService.joinProgram(f.candidateId, f.programId, f.slotId))
            .as("l'état actuel, documenté plutôt que figé : ce test change le jour "
                + "où le produit répond")
            .isNotNull();
    }

    // — fixtures —

    private record Fixture(UUID slotId, UUID programId, UUID hostId, UUID candidateId) {}

    private Object entrer(Porte porte, Fixture f) {
        return porte == Porte.CRENEAU
            ? slotService.joinSlot(f.candidateId, f.slotId, new JoinSlotRequest(null))
            : enrollmentService.joinProgram(f.candidateId, f.programId, f.slotId);
    }

    /** Le code du refus rendu par cette porte, quel que soit le type d'exception. */
    private ErrorCode codeDuRefus(Porte porte, Fixture f) {
        Throwable refus = catchThrowable(() -> entrer(porte, f));
        assertThat(refus).as("cette porte doit refuser").isNotNull();
        assertThat(refus).isInstanceOf(HasErrorCode.class);
        return ((HasErrorCode) refus).getErrorCode();
    }

    private Fixture creneau(SlotStatus status, Instant startsAt, boolean openToPartners) {
        User host = user("porte-hote");
        User candidate = user("porte-candidat");

        Activity activity = activityRepository.findBySlug("yoga").orElseThrow();
        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(host).activity(activity).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Deux portes " + UUID.randomUUID())
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Schedule slot = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Séance en ligne")
            .placeType(PlaceType.ONLINE)
            .startsAt(startsAt)
            .endsAt(startsAt.plus(Duration.ofHours(1)))
            .isOpenToPartners(openToPartners)
            .status(status)
            .cancelledAt(status == SlotStatus.CANCELLED ? Instant.now() : null)
            .build());

        return new Fixture(slot.getId(), program.getId(), host.getId(), candidate.getId());
    }

    private User user(String prefix) {
        return userRepository.save(User.builder()
            .email(uniqueEmail(prefix))
            .passwordHash("x")
            .displayName("Compte de test")
            .isActive(true)
            .build());
    }
}
