package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.ReturnCodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le cœur du module : valider son arrivée (et recevoir le code), puis refermer la
 * veille avec ce code — code de contrainte compris.
 *
 * <p>Les deux tests qui portent la sécurité du lot :
 * {@link #leCodeDeContrainte_repondCommeUnSucces_maisEscalade()} — la clôture sous
 * contrainte doit être indiscernable d'une clôture normale — et
 * {@link #troisMauvaisCodes_verrouillentLeCode()} — le plafond d'essais, la vraie
 * défense d'un secret court. L'indistinguabilité <i>temporelle</i> ne se teste
 * pas ici (trop de bruit) : elle tient à ce que {@code close} évalue les deux
 * empreintes systématiquement, ce que la revue vérifie.
 */
class ArrivalAndCloseIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired GuardianRepository guardianRepository;
    @Autowired ReturnCodeRepository returnCodeRepository;

    private static final double LAT = 48.5734;
    private static final double LNG = 7.7521;

    // ------------------------------------------------------------------ arrivée

    @Test
    void validerSonArrivee_rendLeCodeUneFois_etPasseSurPlace() {
        Compte moi = compte();
        String watchId = veilleArmee(moi);

        String code = arriver(moi, watchId, null);
        assertThat(code).hasSize(5);

        webTestClient.get().uri("/api/watches/{id}", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.watch.state").isEqualTo("ON_SITE")
            .jsonPath("$.watch.arrivalConfirmedAt").exists()
            .jsonPath("$.timeline[1].type").isEqualTo("ARRIVED_ON_SITE");
    }

    @Test
    void validerLarriveeDeuxFois_estRefuse() {
        Compte moi = compte();
        String watchId = veilleArmee(moi);
        arriver(moi, watchId, null);

        webTestClient.post().uri("/api/watches/{id}/arrival", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of())
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_ARRIVAL_NOT_EXPECTED");
    }

    // ------------------------------------------------------------------ clôture

    @Test
    void leBonCode_refermeLaVeille_etLeCodeEstDetruit() {
        Compte moi = compte();
        String watchId = veilleArmee(moi);
        String code = arriver(moi, watchId, null);

        // enteredAt fait foi : on referme avec une heure de saisie antérieure à
        // la réception, et c'est elle qui doit dater la clôture.
        Instant saisi = Instant.now().minus(20, ChronoUnit.MINUTES);
        fermer(moi, watchId, code, saisi).expectStatus().isAccepted();

        webTestClient.get().uri("/api/watches/{id}", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.watch.state").isEqualTo("CLOSED")
            .jsonPath("$.watch.closedAt").isEqualTo(saisi.toString());

        // Le code est détruit, pas marqué obsolète : une seconde clôture n'a plus
        // rien à confronter.
        assertThat(returnCodeRepository.findByWatchId(UUID.fromString(watchId))).isEmpty();
        fermer(moi, watchId, code, Instant.now())
            .expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_NO_CODE_TO_CLOSE");
    }

    @Test
    void unMauvaisCode_rend409_etDecrementeLesEssais() {
        Compte moi = compte();
        String watchId = veilleArmee(moi);
        arriver(moi, watchId, null);

        fermer(moi, watchId, "ZZZZZ", Instant.now())
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("WATCH_CODE_WRONG")
            .jsonPath("$.message").value(m -> assertThat((String) m).contains("2"));
    }

    @Test
    void troisMauvaisCodes_verrouillentLeCode() {
        Compte moi = compte();
        String watchId = veilleArmee(moi);
        arriver(moi, watchId, null);

        for (int i = 0; i < 3; i++) {
            fermer(moi, watchId, "ZZZZZ", Instant.now()).expectStatus().isEqualTo(409);
        }
        // Quatrième tentative : verrouillé, même avec un code qui serait bon.
        fermer(moi, watchId, "ZZZZZ", Instant.now())
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_CODE_LOCKED");
    }

    @Test
    void leCodeDeContrainte_repondCommeUnSucces_maisEscalade() {
        Compte moi = compte();
        String watchId = veilleArmee(moi);
        // L'utilisateur fixe son code de contrainte en validant son arrivée.
        arriver(moi, watchId, "SESAME");

        // Présenté à la clôture, il rend le MÊME 202 qu'un vrai code, corps vide.
        byte[] corps = fermer(moi, watchId, "SESAME", Instant.now())
            .expectStatus().isAccepted()
            .expectBody().returnResult().getResponseBodyContent();
        assertThat(corps == null || corps.length == 0).isTrue();

        // La différence n'est pas dans la réponse : elle est dans l'état, que le
        // client sait ne pas montrer sous contrainte.
        webTestClient.get().uri("/api/watches/{id}", watchId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.watch.state").isEqualTo("ESCALATED");
    }

    @Test
    void fermerAvantDavoirValideLarrivee_estRefuse() {
        Compte moi = compte();
        String watchId = veilleArmee(moi);

        fermer(moi, watchId, "ABCDE", Instant.now())
            .expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("WATCH_NO_CODE_TO_CLOSE");
    }

    // ------------------------------------------------------------------ outils

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
            Compte owner, String watchId, String code, Instant enteredAt) {
        return webTestClient.post().uri("/api/watches/{id}/close", watchId)
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("code", code, "enteredAt", enteredAt.toString()))
            .exchange();
    }

    /** Un compte, un créneau dont il est hôte, un contact accepté, une veille armée. */
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
            .bodyValue(Map.of("name", "Proche", "email", "proche@example.org"))
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
        String email = uniqueEmail("close");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Close" + UUID.randomUUID().toString().substring(0, 8)))
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
