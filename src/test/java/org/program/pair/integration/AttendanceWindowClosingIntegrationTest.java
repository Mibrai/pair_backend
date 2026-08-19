package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.attendance.jobs.AttendancePromptJob;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot C4 — no-show et désistement tardif.
 *
 * <p>Tout le lot tient à une distinction : ce qu'un silence dit, et ce qu'il ne
 * dit pas. Ces tests vérifient donc surtout qu'aucune conséquence n'est tirée
 * d'une non-réponse.
 */
class AttendanceWindowClosingIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AttendancePromptJob attendancePromptJob;

    @Test
    void uneFenetreSansReponse_doitSeRefermer_apresSeptJours() {
        // La fenêtre de sept jours n'existait nulle part : la relance travaille
        // sur une à trois heures après la fin, et rien ne repassait ensuite.
        Ctx ctx = pastSlot(10);

        attendancePromptJob.closeUnansweredAttendanceWindows();

        assertThat(closedAt(ctx.slotId, ctx.participantId)).isNotNull();
    }

    @Test
    void uneFenetreRecente_doitResterOuverte() {
        // Trois jours : la personne a encore le temps de répondre.
        Ctx ctx = pastSlot(3);

        attendancePromptJob.closeUnansweredAttendanceWindows();

        assertThat(closedAt(ctx.slotId, ctx.participantId)).isNull();
    }

    @Test
    void uneReponseDonnee_neDoitPasEtreEcraseeParLaFermeture() {
        Ctx ctx = pastSlot(10);
        answer(ctx, false);   // « je n'y suis finalement pas allé »

        attendancePromptJob.closeUnansweredAttendanceWindows();

        // La fenêtre n'est pas « fermée sans réponse » : elle a une réponse.
        assertThat(closedAt(ctx.slotId, ctx.participantId)).isNull();
    }

    @Test
    void laFermeture_neDoitProduireAucuneConsequenceVisible() {
        // Ni notification, ni marque de présence : fermer la fenêtre dit que le
        // moment de répondre est passé, pas que la personne était absente.
        Ctx ctx = pastSlot(10);

        attendancePromptJob.closeUnansweredAttendanceWindows();

        Long notifications = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type <> 'ATTENDANCE_PROMPT'",
            Long.class, ctx.participantId);
        assertThat(notifications).isZero();

        Long attendances = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM attendances WHERE user_id = ? AND schedule_id = ?",
            Long.class, ctx.participantId, ctx.slotId);
        assertThat(attendances).isZero();
    }

    @Test
    void unSilence_neDoitPasPeserSurLeSignalDeFiabilite() {
        // Le point le plus important du lot. Un silence retire la séance de la
        // mesure au lieu de peser contre : le compter au dénominateur
        // reviendrait à trancher pour « je n'y étais pas », alors qu'il peut
        // vouloir dire « j'ai oublié de répondre ».
        Ctx ctx = pastSlot(10);

        int denominator = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM slot_participations sp
            JOIN schedules s ON sp.schedule_id = s.id
            WHERE sp.user_id = ? AND sp.status = 'CONFIRMED' AND s.starts_at < NOW()
              AND EXISTS (SELECT 1 FROM attendances a
                          WHERE a.user_id = sp.user_id AND a.schedule_id = sp.schedule_id)
            """, Integer.class, ctx.participantId);

        // La séance restée sans réponse n'est pas comptée.
        assertThat(denominator).isZero();
    }

    @Test
    void unDesistement_doitEtreDate() {
        // Sans withdrawn_at, un désistement à trois jours et un désistement à
        // une heure étaient le même événement. La colonne a été ajoutée au lot
        // C1, qui écrivait déjà des désistements.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String participant = registerAndLogin();
        join(participant, slotId);

        webTestClient.delete().uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(participant))
            .exchange().expectStatus().isNoContent();

        Timestamp withdrawnAt = jdbcTemplate.queryForObject("""
            SELECT withdrawn_at FROM slot_participations
            WHERE schedule_id = ? AND user_id = ?
            """, Timestamp.class, slotId, userId(participant));

        assertThat(withdrawnAt).isNotNull();
    }

    @Test
    void unDesistement_neDoitPasPeserSurLeSignal() {
        // Se décommander à l'avance n'est pas manquer à sa parole. Le
        // dénominateur ne retient que les participations confirmées.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String participant = registerAndLogin();
        join(participant, slotId);
        webTestClient.delete().uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(participant))
            .exchange().expectStatus().isNoContent();

        String status = jdbcTemplate.queryForObject("""
            SELECT status FROM slot_participations WHERE schedule_id = ? AND user_id = ?
            """, String.class, slotId, userId(participant));

        assertThat(status).isEqualTo("WITHDRAWN");
    }

    // — helpers —

    private record Ctx(UUID slotId, UUID participantId, String participantToken) {}

    /** Un créneau terminé il y a N jours, avec un inscrit qui n'a rien dit. */
    private Ctx pastSlot(int daysAgo) {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String participant = registerAndLogin();
        join(participant, slotId);

        // On recule la séance en base : la créer dans le passé serait refusé par
        // la validation, à juste titre.
        jdbcTemplate.update("UPDATE schedules SET starts_at = ?, ends_at = ? WHERE id = ?",
            Timestamp.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS)),
            Timestamp.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS)),
            slotId);

        return new Ctx(slotId, userId(participant), participant);
    }

    private void answer(Ctx ctx, boolean wasPresent) {
        jdbcTemplate.update("""
            INSERT INTO attendances (id, schedule_id, user_id, was_present, attended_at, confirmed_at)
            VALUES (gen_random_uuid(), ?, ?, ?, NOW(), NOW())
            """, ctx.slotId, ctx.participantId, wasPresent);
    }

    private Timestamp closedAt(UUID slotId, UUID userId) {
        return jdbcTemplate.queryForObject("""
            SELECT attendance_closed_at FROM slot_participations
            WHERE schedule_id = ? AND user_id = ?
            """, Timestamp.class, slotId, userId);
    }

    private void join(String token, UUID slotId) {
        webTestClient.post().uri("/api/slots/{id}/join", slotId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
            .exchange().expectStatus().isCreated();
    }

    private UUID userId(String token) {
        return UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private UUID publishSlot(String token) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(2, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("noshow");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Participant"))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return auth.accessToken();
    }
}
