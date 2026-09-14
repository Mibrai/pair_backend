package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.program.dto.SlotParticipantDto;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un compte fermé ne fait tomber la liste de personne — incident du 14/09/2026.
 *
 * <p>{@code V116} a désactivé les vingt comptes démo en production. Les comptes
 * de test étaient inscrits à des créneaux dont l'hôte était l'un d'eux, et
 * {@code GET /api/slots/mine} rendait {@code 404 « Utilisateur introuvable »} :
 * le profil de l'hôte, composé pour chaque créneau, lève pour un compte inactif,
 * et un seul hôte dans ce cas faisait échouer toute la liste.
 *
 * <p>La désactivation n'est pas propre aux comptes démo : c'est aussi ce que
 * pose la fermeture de compte d'un utilisateur. Le compte est donc désactivé ici
 * directement en base, comme V116 l'a fait.
 *
 * <p>Décision : le créneau d'un hôte au compte fermé <b>sort</b> de « mes
 * créneaux », comme il est déjà absent du fil et de la carte, et sa fiche rend
 * {@code 404 « Créneau introuvable »}.
 */
class HoteDesactiveIntegrationTest extends AbstractIntegrationTest {

    // Décor à soi : Poitiers, que personne d'autre n'emploie.
    private static final double LAT = 46.5802;
    private static final double LNG = 0.3404;

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void mesCreneaux_doiventRester200_etOmettreLeCreneauDeLHoteFerme() {
        Compte hote = compte("hote-ferme");
        Compte autreHote = compte("hote-actif");
        Compte inscrit = compte("inscrit");
        UUID creneauFerme = publier(hote, 2);
        // Un autre jour : l'inscrit ne peut pas être à deux créneaux à la même heure.
        UUID creneauActif = publier(autreHote, 3);
        rejoindre(inscrit, creneauFerme);
        rejoindre(inscrit, creneauActif);

        desactiver(hote);

        assertThat(mesCreneaux(inscrit, false)).contains(creneauActif).doesNotContain(creneauFerme);
        assertThat(mesCreneaux(inscrit, true)).contains(creneauActif).doesNotContain(creneauFerme);
    }

    @Test
    void laFiche_doitRendreCreneauIntrouvable_etNonUtilisateurIntrouvable() {
        Compte hote = compte("fiche-hote");
        Compte inscrit = compte("fiche-inscrit");
        UUID creneau = publier(hote);
        rejoindre(inscrit, creneau);

        desactiver(hote);

        webTestClient.get().uri("/api/slots/{id}", creneau)
            .headers(h -> h.setBearerAuth(inscrit.jeton()))
            .exchange().expectStatus().isNotFound()
            .expectBody().jsonPath("$.message").isEqualTo("Créneau introuvable.");
    }

    @Test
    void leFil_doitRester200_sansLeCreneauDeLHoteFerme() {
        Compte hote = compte("fil-hote");
        Compte lecteur = compte("fil-lecteur");
        UUID creneau = publier(hote);
        assertThat(fil(lecteur)).contains(creneau);

        desactiver(hote);

        assertThat(fil(lecteur)).doesNotContain(creneau);
    }

    @Test
    void rejoindre_doitRendreCreneauIntrouvable_sansCreerDInscription() {
        Compte hote = compte("join-hote");
        Compte candidat = compte("join-candidat");
        UUID creneau = publier(hote);

        desactiver(hote);

        webTestClient.post().uri("/api/slots/{id}/join", creneau)
            .headers(h -> h.setBearerAuth(candidat.jeton()))
            .exchange().expectStatus().isNotFound()
            .expectBody().jsonPath("$.message").isEqualTo("Créneau introuvable.");
        Integer lignes = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM slot_participations WHERE schedule_id = ?", Integer.class, creneau);
        assertThat(lignes).isZero();
    }

    @Test
    void lesInscritsVusParLHote_doiventRester200_sansLInscritFerme() {
        Compte hote = compte("liste-hote");
        Compte ferme = compte("liste-ferme");
        Compte actif = compte("liste-actif");
        UUID creneau = publier(hote);
        rejoindre(ferme, creneau);
        rejoindre(actif, creneau);

        desactiver(ferme);

        List<SlotParticipantDto> inscrits = webTestClient.get()
            .uri("/api/slots/{id}/participants", creneau)
            .headers(h -> h.setBearerAuth(hote.jeton()))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotParticipantDto.class).returnResult().getResponseBody();
        assertThat(inscrits).hasSize(1);
        assertThat(inscrits.get(0).user().id()).isEqualTo(actif.id());
    }

    // — outils —

    private record Compte(String jeton, UUID id) {}

    private Compte compte(String prefixe) {
        String email = uniqueEmail("hote-desactive-" + prefixe);
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Testeur"))
            .exchange().expectStatus().isCreated();
        adresseVerifiee(email);
        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        UUID id = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE LOWER(email) = LOWER(?)", UUID.class, email);
        return new Compte(auth.accessToken(), id);
    }

    /** Comme V116 : le compte seul, sans rien retirer de ses créneaux ni de ses inscriptions. */
    private void desactiver(Compte compte) {
        jdbcTemplate.update("UPDATE users SET is_active = FALSE WHERE id = ?", compte.id());
    }

    private UUID publier(Compte hote) {
        return publier(hote, 2);
    }

    private UUID publier(Compte hote, int dansNJours) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        Instant debut = Instant.now().plus(dansNJours, ChronoUnit.DAYS);
        SlotFeedItemDto slot = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(hote.jeton()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, debut, debut.plus(Duration.ofHours(2)),
                "Parc de Blossac", PlaceType.PUBLIC, LAT, LNG,
                "Rue de la Tranchée", null, "Poitiers", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(SlotFeedItemDto.class).returnResult().getResponseBody();
        assertThat(slot).isNotNull();
        return slot.scheduleId();
    }

    private void rejoindre(Compte compte, UUID creneau) {
        webTestClient.post().uri("/api/slots/{id}/join", creneau)
            .headers(h -> h.setBearerAuth(compte.jeton()))
            .exchange().expectStatus().isCreated();
    }

    private List<UUID> mesCreneaux(Compte compte, boolean aVenir) {
        return webTestClient.get()
            .uri(b -> b.path("/api/slots/mine").queryParam("upcoming", aVenir).build())
            .headers(h -> h.setBearerAuth(compte.jeton()))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class).returnResult().getResponseBody()
            .stream().map(SlotFeedItemDto::scheduleId).toList();
    }

    private List<UUID> fil(Compte compte) {
        return webTestClient.get()
            .uri(b -> b.path("/api/slots/feed")
                .queryParam("lat", LAT).queryParam("lng", LNG)
                .queryParam("radiusMeters", 20000).build())
            .headers(h -> h.setBearerAuth(compte.jeton()))
            .exchange().expectStatus().isOk()
            .expectBodyList(SlotFeedItemDto.class).returnResult().getResponseBody()
            .stream().map(SlotFeedItemDto::scheduleId).toList();
    }
}
