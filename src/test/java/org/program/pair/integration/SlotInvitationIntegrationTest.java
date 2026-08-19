package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.invitation.dto.InvitationDto;
import org.program.pair.domain.invitation.dto.InvitationLinkDto;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot B2 — invitation nominative.
 *
 * <p>La récompense est le sujet du lot autant que le mécanisme : un badge, et
 * rien qui se compte. Plusieurs de ces tests vérifient donc des absences.
 */
class SlotInvitationIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void chaqueAppel_doitRendreUnLienDistinct() {
        // Un code par invitation : c'est ce qui permet de savoir laquelle a abouti.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);

        InvitationLinkDto first = invite(host, slotId);
        InvitationLinkDto second = invite(host, slotId);

        assertThat(first.code()).hasSize(22);
        assertThat(first.url()).isEqualTo("https://lien.meetdo.fun/i/" + first.code());
        assertThat(second.code()).isNotEqualTo(first.code());
    }

    @Test
    void unTiers_neDoitPasPouvoirInviter() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        String stranger = registerAndLogin();

        webTestClient.post().uri("/api/slots/{id}/invite", slotId)
            .headers(h -> h.setBearerAuth(stranger))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void accepter_doitRejoindreLeCreneau_etMarquerLInvitation() {
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        InvitationLinkDto link = invite(host, slotId);

        String guest = registerAndLogin();
        SlotFeedItemDto joined = accept(guest, link.code());

        assertThat(joined.scheduleId()).isEqualTo(slotId);

        List<InvitationDto> mine = mine(host);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).accepted()).isTrue();
    }

    @Test
    void uneInvitationDejaUtilisee_doitEtreRefusee() {
        String host = registerAndLogin();
        InvitationLinkDto link = invite(host, publishSlot(host));

        accept(registerAndLogin(), link.code());

        webTestClient.post().uri("/api/invitations/{code}/accept", link.code())
            .headers(h -> h.setBearerAuth(registerAndLogin()))
            .exchange().expectStatus().isBadRequest();
    }

    @Test
    void sInviterSoiMeme_doitEtreRefuse() {
        String host = registerAndLogin();
        InvitationLinkDto link = invite(host, publishSlot(host));

        webTestClient.post().uri("/api/invitations/{code}/accept", link.code())
            .headers(h -> h.setBearerAuth(host))
            .exchange().expectStatus().isBadRequest();
    }

    @Test
    void unRefusDeRejoindre_neDoitRienEnregistrer() {
        // Une invitation ne donne aucun droit de plus : si le créneau refuse, le
        // refus remonte tel quel et l'invitation reste non convertie.
        String host = registerAndLogin();
        UUID slotId = publishSlot(host);
        InvitationLinkDto link = invite(host, slotId);

        // Le créneau se ferme aux partenaires entre-temps.
        jdbcTemplate.update("UPDATE schedules SET is_open_to_partners = false WHERE id = ?", slotId);

        webTestClient.post().uri("/api/invitations/{code}/accept", link.code())
            .headers(h -> h.setBearerAuth(registerAndLogin()))
            .exchange().expectStatus().isBadRequest();

        assertThat(mine(host).get(0).accepted()).isFalse();
    }

    @Test
    void uneInvitationConvertie_doitDonnerUnBadgeDeRoleEtRienDautre() {
        String host = registerAndLogin();
        InvitationLinkDto link = invite(host, publishSlot(host));
        UUID hostId = userId(host);

        accept(registerAndLogin(), link.code());

        List<Map<String, Object>> badges = jdbcTemplate.queryForList("""
            SELECT b.code, b.category FROM badge_awards ba
            JOIN badges b ON ba.badge_id = b.id
            WHERE ba.user_id = ?
            """, hostId);

        assertThat(badges).anySatisfy(badge -> {
            assertThat(badge.get("code")).isEqualTo("HOST_INVITER");
            assertThat(badge.get("category")).isEqualTo("ROLE");
        });
    }

    @Test
    void aucunEndpoint_neDoitExposerUnTotalDInvitations() {
        // Garde-fou n°1 : un compteur rendu au client deviendrait un classement
        // de parrains. La liste dit ce que chaque invitation est devenue, et
        // s'arrête là.
        String host = registerAndLogin();
        InvitationLinkDto link = invite(host, publishSlot(host));
        accept(registerAndLogin(), link.code());

        String body = new String(webTestClient.get().uri("/api/invitations/me")
            .headers(h -> h.setBearerAuth(host))
            .exchange().expectStatus().isOk()
            .expectBody().returnResult().getResponseBody());

        assertThat(body).doesNotContain("count").doesNotContain("total").doesNotContain("rank");
    }

    @Test
    void unMembreDejaInscrit_neDoitPasCompterCommeRecrutement() {
        // Une invitation acceptée par quelqu'un qui était déjà là a marché sans
        // faire venir personne. Confondre les deux fausserait toute mesure.
        String guest = registerAndLogin();          // existe avant l'invitation
        String host = registerAndLogin();
        InvitationLinkDto link = invite(host, publishSlot(host));

        accept(guest, link.code());

        InvitationDto invitation = mine(host).get(0);
        assertThat(invitation.accepted()).isTrue();
        assertThat(invitation.broughtNewMember()).isFalse();
    }

    // — helpers —

    private InvitationLinkDto invite(String token, UUID slotId) {
        return webTestClient.post().uri("/api/slots/{id}/invite", slotId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isCreated()
            .expectBody(InvitationLinkDto.class).returnResult().getResponseBody();
    }

    private SlotFeedItemDto accept(String token, String code) {
        return webTestClient.post().uri("/api/invitations/{code}/accept", code)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
    }

    private List<InvitationDto> mine(String token) {
        return webTestClient.get().uri("/api/invitations/me")
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBodyList(InvitationDto.class).returnResult().getResponseBody();
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
                activityId, Instant.now().plus(3, ChronoUnit.DAYS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", null, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private String registerAndLogin() {
        String email = uniqueEmail("invit");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Invitant"))
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
