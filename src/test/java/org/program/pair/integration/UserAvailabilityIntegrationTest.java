package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot D3 — disponibilités habituelles.
 *
 * <p>La règle qui gouverne le lot : <b>pondérer, jamais exclure</b>. Une
 * disponibilité déclarée est une habitude, pas un engagement — qui a coché
 * « mardi soir » peut très bien vouloir un samedi matin, et le lui cacher
 * reviendrait à lui retirer ce qu'il cherchait ce jour-là.
 */
class UserAvailabilityIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // — déclarer —

    @Test
    void laGrille_doitEtreRemplacee_enUnSeulAppel() {
        String token = registerAndLogin();

        assertThat(replace(token, List.of(
            Map.of("dayOfWeek", 2, "timeSlot", "EVENING"),
            Map.of("dayOfWeek", 6, "timeSlot", "MORNING")))).hasSize(2);

        assertThat(replace(token, List.of(
            Map.of("dayOfWeek", 3, "timeSlot", "AFTERNOON")))).hasSize(1);
    }

    @Test
    void cocherDeuxFoisLaMemeCase_neDoitPasEchouer() {
        // Ce n'est pas une erreur de l'utilisateur ; la clé composite la
        // refuserait par une violation d'intégrité plutôt qu'un message lisible.
        String token = registerAndLogin();

        assertThat(replace(token, List.of(
            Map.of("dayOfWeek", 2, "timeSlot", "EVENING"),
            Map.of("dayOfWeek", 2, "timeSlot", "EVENING")))).hasSize(1);
    }

    @Test
    void unJourHorsBornes_doitEtreRefuse() {
        String token = registerAndLogin();

        webTestClient.put().uri("/api/users/me/availability")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(List.of(Map.of("dayOfWeek", 8, "timeSlot", "EVENING")))
            .exchange().expectStatus().isBadRequest();
    }

    // — pondérer —

    @Test
    void unCreneauHorsDisponibilite_doitRester_visible() {
        // Le cœur du lot. Une case non cochée ne masque rien.
        String host = registerAndLogin();
        UUID morning = publishSlotAt(host, nextOccurrence(2, 9));  // mardi 9 h

        String viewer = registerAndLogin();
        replace(viewer, List.of(Map.of("dayOfWeek", 2, "timeSlot", "EVENING")));

        assertThat(feedIds(viewer)).contains(morning);
    }

    @Test
    void auSeinDunMemeJour_ceQuiTombeBien_doitPasserDevant() {
        String host = registerAndLogin();
        Instant tuesday = nextOccurrence(2, 9);

        // Deux séances le même jour : 9 h et 20 h.
        UUID morning = publishSlotAt(host, tuesday);
        UUID evening = publishSlotAt(registerAndLogin(), tuesday.plus(11, ChronoUnit.HOURS));

        String viewer = registerAndLogin();
        replace(viewer, List.of(Map.of("dayOfWeek", 2, "timeSlot", "EVENING")));

        List<UUID> feed = feedIds(viewer);
        assertThat(feed).contains(morning, evening);
        // Celle du soir passe devant, alors qu'elle commence onze heures plus tard.
        assertThat(feed.indexOf(evening)).isLessThan(feed.indexOf(morning));
    }

    @Test
    void laChronologie_neDoitJamaisEtreBousculee() {
        // La pondération ne joue qu'entre créneaux du même jour : un créneau de
        // la semaine prochaine ne passe pas devant un de demain.
        String host = registerAndLogin();
        Instant tuesdayMorning = nextOccurrence(2, 9);
        Instant nextTuesdayEvening = tuesdayMorning.plus(7, ChronoUnit.DAYS).plus(11, ChronoUnit.HOURS);

        UUID soon = publishSlotAt(host, tuesdayMorning);
        UUID later = publishSlotAt(registerAndLogin(), nextTuesdayEvening);

        String viewer = registerAndLogin();
        replace(viewer, List.of(Map.of("dayOfWeek", 2, "timeSlot", "EVENING")));

        List<UUID> feed = feedIds(viewer, 14);
        assertThat(feed.indexOf(soon)).isLessThan(feed.indexOf(later));
    }

    @Test
    void sansAucuneCase_lOrdreDoitResterCeluiDavant() {
        // Tous les rangs sont alors égaux, et le classement dégénère en
        // « par jour, puis par heure » — exactement l'ordre historique.
        String host = registerAndLogin();
        Instant tuesday = nextOccurrence(2, 9);
        UUID morning = publishSlotAt(host, tuesday);
        UUID evening = publishSlotAt(registerAndLogin(), tuesday.plus(11, ChronoUnit.HOURS));

        List<UUID> feed = feedIds(registerAndLogin());

        assertThat(feed.indexOf(morning)).isLessThan(feed.indexOf(evening));
    }

    // — helpers —

    /** Le prochain jour ISO donné, à l'heure donnée, dans le fuseau applicatif. */
    private Instant nextOccurrence(int isoDayOfWeek, int hour) {
        LocalDate day = LocalDate.now(ZONE).plusDays(1);
        while (day.getDayOfWeek().getValue() != isoDayOfWeek) {
            day = day.plusDays(1);
        }
        return LocalDateTime.of(day, java.time.LocalTime.of(hour, 0)).atZone(ZONE).toInstant();
    }

    @SuppressWarnings("unchecked")
    private List<Map> replace(String token, List<Map<String, Object>> slots) {
        return webTestClient.put().uri("/api/users/me/availability")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(slots)
            .exchange().expectStatus().isOk()
            .expectBodyList(Map.class).returnResult().getResponseBody();
    }

    private List<UUID> feedIds(String token) {
        return feedIds(token, 7);
    }

    private List<UUID> feedIds(String token, int days) {
        List<SlotFeedItemDto> feed = webTestClient.get()
            .uri(b -> b.path("/api/slots/feed")
                .queryParam("lat", LAT).queryParam("lng", LNG)
                .queryParam("radiusMeters", 20000)
                .queryParam("to", Instant.now().plus(days, ChronoUnit.DAYS).toString())
                .build())
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class).returnResult().getResponseBody();
        return feed.stream().map(SlotFeedItemDto::scheduleId).toList();
    }

    private UUID publishSlotAt(String token, Instant startsAt) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, startsAt, null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("dispo");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Disponible"))
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
