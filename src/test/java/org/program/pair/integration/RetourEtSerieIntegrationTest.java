package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
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
 * Ce qui récompense le geste, et ce qui l'annonce : la série de retours confirmés
 * (§5 du retour du 02/09) et l'annonce de retour au contact (§6).
 *
 * <p>Deux tests portent la sécurité de ce lot :
 * {@link #uneClotureSousContrainte_compteCommeUnRetourConfirme()} — la série ne
 * doit pas trahir sur l'écran que quelqu'un regarde par-dessus l'épaule — et
 * {@link #aucunTypeDeNotification_nAEteCreePourLAnnonceDeRetour()} — l'exception
 * accordée au §6 ne doit pas devenir une brèche dans la règle qu'elle contourne.
 */
class RetourEtSerieIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------- §5 : la série

    @Test
    void troisRetoursConfirmes_fontUneSerieDeTrois() {
        Compte moi = compte();

        assertThat(serie(moi, veilleRefermee(moi))).isEqualTo(1);
        assertThat(serie(moi, veilleRefermee(moi))).isEqualTo(2);
        assertThat(serie(moi, veilleRefermee(moi))).isEqualTo(3);
    }

    @Test
    void uneVeilleAbandonnee_rompLaSerie() {
        Compte moi = compte();
        veilleRefermee(moi);
        veilleRefermee(moi);

        String abandonnee = veilleArmee(moi);
        webTestClient.post().uri("/api/watches/{id}/abandon", abandonnee)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk();

        // La suivante repart de un : la série compte d'affilée, pas en tout.
        assertThat(serie(moi, veilleRefermee(moi))).isEqualTo(1);
    }

    @Test
    void uneVeilleDesarmeeAvantLeDepart_neCompteNiNeRompt() {
        // Il n'y avait pas de retour à confirmer : la compter contre la personne
        // serait faux, et la compter pour elle serait un cadeau.
        Compte moi = compte();
        String premiere = veilleRefermee(moi);
        assertThat(serie(moi, premiere)).isEqualTo(1);

        String desarmee = veilleArmee(moi);
        webTestClient.delete().uri("/api/watches/{id}", desarmee)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNoContent();

        assertThat(serie(moi, veilleRefermee(moi))).isEqualTo(2);
    }

    @Test
    void uneClotureSousContrainte_compteCommeUnRetourConfirme() {
        // La clause d'indistinguabilité, appliquée à un compteur. Sous contrainte,
        // la veille reste ESCALATED : une série calculée sur l'état afficherait un
        // nombre différent au moment précis où l'écran est regardé par quelqu'un
        // d'autre. Elle se calcule donc sur l'événement CLOSED_BY_CODE, que les
        // deux clôtures écrivent.
        Compte moi = compte();
        veilleRefermee(moi);
        veilleRefermee(moi);

        String sousContrainte = veilleArmee(moi);
        String code = arriver(moi, sousContrainte, "MAMAN");
        assertThat(code).isNotBlank();
        fermer(moi, sousContrainte, "MAMAN").expectStatus().isAccepted();

        assertThat(serie(moi, sousContrainte)).isEqualTo(3);
    }

    @Test
    void laSerieDunAutre_neDeborderPasSurLaMienne() {
        Compte moi = compte();
        veilleRefermee(moi);
        veilleRefermee(moi);

        Compte quelquUnDautre = compte();
        assertThat(serie(quelquUnDautre, veilleRefermee(quelquUnDautre))).isEqualTo(1);
    }

    // ------------------------------------------- §6 : l'annonce de retour

    @Test
    void sansLeDrapeau_personneNEstPrevenu() {
        // Le comportement d'aujourd'hui, qui reste le défaut.
        Compte moi = compte();
        String veille = veilleArmee(moi);
        String code = arriver(moi, veille, null);
        fermer(moi, veille, code).expectStatus().isAccepted();

        assertThat(messagesAuProche(moi)).isEmpty();
        assertThat(evenements(veille)).doesNotContain("RETURN_ANNOUNCED");
    }

    @Test
    void avecLeDrapeau_leContactRecoitUnMessageQuiNeParlePasDeVeille() {
        Compte moi = compte();
        String veille = veilleArmee(moi);
        String code = arriver(moi, veille, null);

        webTestClient.post().uri("/api/watches/{id}/close", veille)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", code, "enteredAt", Instant.now().toString(),
                "notifyGuardian", true))
            .exchange().expectStatus().isAccepted();

        List<Map<String, Object>> sortants = messagesAuProche(moi);
        assertThat(sortants).hasSize(1);

        // Le message dit « je suis rentrée », et rien du dispositif : ni veille, ni
        // alerte, ni heure limite, ni lieu. C'est ce qui le distingue d'une
        // notification de fin de veille — le contact n'apprend pas qu'une veille
        // avait été armée.
        String corps = String.valueOf(sortants.get(0).get("body")).toLowerCase();
        assertThat(corps).doesNotContain("veille").doesNotContain("alerte")
            .doesNotContain("orangerie").doesNotContain("strasbourg");

        // Un envoi vers un tiers laisse une trace dans le journal de la personne.
        assertThat(evenements(veille)).contains("RETURN_ANNOUNCED");
    }

    @Test
    void sousContrainte_leDrapeauNAnnonceRien() {
        // Rassurer le contact pendant qu'une escalade silencieuse part serait
        // l'exact contraire de ce que la personne vient de demander.
        Compte moi = compte();
        String veille = veilleArmee(moi);
        arriver(moi, veille, "MAMAN");

        webTestClient.post().uri("/api/watches/{id}/close", veille)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", "MAMAN", "enteredAt", Instant.now().toString(),
                "notifyGuardian", true))
            .exchange().expectStatus().isAccepted();

        assertThat(evenements(veille)).doesNotContain("RETURN_ANNOUNCED");
    }

    @Test
    void lAnnonce_neDoitPasPolluerLEtatDeRemiseDesAlertes() {
        // alertDelivery agrège tout ce que l'outbox porte pour une veille, et le
        // client en a fait un bandeau global où BOUNCED veut dire « le proche n'a
        // pas été joint par l'alerte ». Rattacher l'annonce à la veille ferait
        // passer à SENT une veille où aucune alerte n'est partie — et sonnerait
        // l'alarme parce qu'un « tout va bien » a rebondi.
        Compte moi = compte();
        String veille = veilleArmee(moi);
        String code = arriver(moi, veille, null);

        webTestClient.post().uri("/api/watches/{id}/close", veille)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", code, "enteredAt", Instant.now().toString(),
                "notifyGuardian", true))
            .exchange().expectStatus().isAccepted();

        assertThat(messagesAuProche(moi)).hasSize(1);
        webTestClient.get().uri("/api/watches/{id}", veille)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.alertDelivery").isEqualTo("NONE");
    }

    @Test
    void aucunTypeDeNotification_nAEteCreePourLAnnonceDeRetour() {
        // L'exception du §6 est accordée par un message sortant demandé au coup par
        // coup, jamais par un type de notification. La règle du QUATER §3 tient
        // entière, et ce test la garde du côté de l'exception.
        for (NotificationType t : NotificationType.values()) {
            assertThat(t.name())
                .as("type %s", t.name())
                .doesNotContain("RETURN_ANNOUNCED")
                .doesNotContain("SAFE")
                .doesNotContain("HOME");
        }
    }

    // ------------------------------------------------------------ helpers

    private int serie(Compte owner, String watchId) {
        return webTestClient.get().uri("/api/watches/{id}", watchId)
            .headers(h -> h.setBearerAuth(owner.token()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody()
            .get("consecutiveConfirmedReturns") instanceof Number n ? n.intValue() : -1;
    }

    private List<String> evenements(String watchId) {
        return jdbcTemplate.queryForList(
            "SELECT type FROM watch_events WHERE watch_id = ?::uuid", String.class, watchId);
    }

    /**
     * Les messages déposés à l'attention du proche de ce compte-là.
     *
     * <p>Cherchés par destinataire et non par veille : l'annonce de retour n'est
     * volontairement pas rattachée à la veille dans l'outbox, sinon elle
     * polluerait alertDelivery — voir
     * {@link #lAnnonce_neDoitPasPolluerLEtatDeRemiseDesAlertes()}.
     *
     * <p><b>L'adresse est propre au compte</b>, et il le faut : l'outbox est une
     * table partagée par toute la classe. Une adresse commune faisait compter, à
     * un test, les messages qu'un autre venait d'envoyer — et le test ne passait
     * que tant qu'il était seul à en produire.
     */
    private List<Map<String, Object>> messagesAuProche(Compte owner) {
        return jdbcTemplate.queryForList(
            "SELECT recipient, body FROM outbox_messages WHERE recipient = ?",
            emailDuProche(owner));
    }

    /** L'adresse du contact de ce compte : unique, pour que les tests ne se mélangent pas. */
    private static String emailDuProche(Compte owner) {
        return "proche-" + owner.id() + "@example.org";
    }

    /** Une veille armée, arrivée sur place, refermée par son code. */
    private String veilleRefermee(Compte owner) {
        String watchId = veilleArmee(owner);
        String code = arriver(owner, watchId, null);
        fermer(owner, watchId, code).expectStatus().isAccepted();
        return watchId;
    }

    private String arriver(Compte owner, String watchId, String duressCode) {
        Object body = duressCode == null ? Map.of() : Map.of("duressCode", duressCode);
        return String.valueOf(webTestClient.post().uri("/api/watches/{id}/arrival", watchId)
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("returnCode"));
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec fermer(
            Compte owner, String watchId, String code) {
        return webTestClient.post().uri("/api/watches/{id}/close", watchId)
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", code, "enteredAt", Instant.now().toString()))
            .exchange();
    }

    private String veilleArmee(Compte owner) {
        UUID scheduleId = creerCreneau(owner);
        UUID guardianId = contactAccepte(owner);
        return String.valueOf(webTestClient.post().uri("/api/watches")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("scheduleId", scheduleId.toString(), "guardianId", guardianId.toString()))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id"));
    }

    private UUID creerCreneau(Compte owner) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        Map<?, ?> body = webTestClient.post().uri("/api/quick-slots")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new QuickSlotRequest(
                activityId, Instant.now().plus(2, ChronoUnit.HOURS), null,
                "Parc de l'Orangerie", PlaceType.PUBLIC, LAT, LNG,
                "1 avenue de l'Europe", null, "Strasbourg", 5, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString(String.valueOf(body.get("scheduleId")));
    }

    private UUID contactAccepte(Compte owner) {
        UUID guardianId = UUID.fromString(String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Proche", "email", emailDuProche(owner)))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
        String token = guardianRepository.findByIdAndOwnerId(guardianId, owner.id())
            .orElseThrow().getConsentToken();
        webTestClient.post().uri("/public/guardian-consent/{t}/accept", token)
            .exchange().expectStatus().isOk();
        return guardianId;
    }

    private record Compte(UUID id, String token) {}

    private Compte compte() {
        String email = uniqueEmail("serie");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Serie" + UUID.randomUUID().toString().substring(0, 8)))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();

        UUID id = UUID.fromString(String.valueOf(webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(auth.accessToken()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));

        return new Compte(id, auth.accessToken());
    }
}
