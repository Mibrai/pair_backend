package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.guardian.ConsentState;
import org.program.pair.repository.GuardianRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le domaine des contacts d'urgence : désigner, inviter, consentir — et les
 * garde-fous qui font tenir la règle « rien de non sollicité, et un refus qui
 * protège vraiment ».
 *
 * <p>Le plus important de ces tests est {@link #unNumeroRefuse_nebPeutPlusEtreDesigne_parPersonne()}
 * — le refus global au numéro — parce que c'est celui dont l'absence
 * transformerait le module en canal de harcèlement avec une étape de
 * contournement triviale.
 */
class GuardianIntegrationTest extends AbstractIntegrationTest {

    @Autowired GuardianRepository guardianRepository;

    // ------------------------------------------------------------- désigner

    @Test
    void unContactExterneAvecEmail_estCreeEnPending() {
        Compte moi = compte();

        webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Camille", "email", "camille@example.org"))
            .exchange().expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.type").isEqualTo("EXTERNAL")
            .jsonPath("$.name").isEqualTo("Camille")
            .jsonPath("$.email").isEqualTo("camille@example.org")
            .jsonPath("$.consentState").isEqualTo("PENDING")
            // Le jeton de consentement ne sort jamais dans la réponse de l'owner :
            // c'est le secret du lien envoyé au contact.
            .jsonPath("$.consentToken").doesNotExist();
    }

    @Test
    void unMembreMeetdo_peutEtreDesigne() {
        Compte moi = compte();
        Compte proche = compte();

        webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("memberId", proche.id().toString()))
            .exchange().expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.type").isEqualTo("MEMBER")
            // Le nom du membre est résolu ; ni son e-mail ni son téléphone ne sont rendus.
            .jsonPath("$.email").isEmpty()
            .jsonPath("$.phone").isEmpty();
    }

    @Test
    void seDesignerSoiMeme_estRefuse() {
        Compte moi = compte();

        webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("memberId", moi.id().toString()))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("GUARDIAN_SELF");
    }

    @Test
    void unContactSansAucunCanal_estRefuse() {
        Compte moi = compte();

        webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Sans moyen de le joindre"))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("GUARDIAN_INVALID_CONTACT");
    }

    @Test
    void unNumeroInvalide_estRefuse() {
        Compte moi = compte();

        webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Fixe", "phone", "0123456789"))
            .exchange().expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void unMobileEstNormalise_aLaCreation() {
        Compte moi = compte();

        // Un numéro propre à ce test : la liste des refus est globale et
        // persistante, et un numéro refusé par une autre méthode ne se
        // redésignerait plus. Chaque test qui touche à un numéro en prend un à lui.
        webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Alex", "phone", "07 65 43 21 09"))
            .exchange().expectStatus().isCreated()
            .expectBody().jsonPath("$.phone").isEqualTo("+33765432109");
    }

    // ------------------------------------------------------- refus global

    @Test
    void unNumeroRefuse_nebPeutPlusEtreDesigne_parPersonne() {
        // Premier compte : désigne un contact par son numéro, et ce contact refuse.
        Compte premier = compte();
        String phone = "0612345678";

        webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(premier.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Dominique", "phone", phone))
            .exchange().expectStatus().isCreated();

        String token = guardianRepository.findByOwnerIdOrderByCreatedAtDesc(premier.id())
            .get(0).getConsentToken();

        // Le contact refuse, via la page publique (POST).
        webTestClient.post().uri("/public/guardian-consent/{t}/refuse", token)
            .exchange().expectStatus().isOk().expectBody().returnResult();

        // Un SECOND compte tente de désigner le même numéro, écrit autrement :
        // refusé, parce que le refus est global au numéro normalisé.
        Compte second = compte();
        webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(second.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", "Le même numéro autrement", "phone", "+33 6 12 34 56 78"))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("GUARDIAN_CONTACT_REFUSED");
    }

    // ------------------------------------------------------------- inviter

    @Test
    void inviterDeuxFois_estRefuse_pasDeRelance() {
        Compte moi = compte();
        String guardianId = creerContactEmail(moi, "Rene", "rene@example.org");

        webTestClient.post().uri("/api/guardians/{id}/invite", guardianId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.invitedAt").exists();

        webTestClient.post().uri("/api/guardians/{id}/invite", guardianId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("GUARDIAN_ALREADY_INVITED");
    }

    @Test
    void inviterUnContactSansEmail_diteQueLeSmsNestPasEncoreLa() {
        Compte moi = compte();
        String guardianId = creerContactTelephone(moi, "Sam", "0612349999");

        webTestClient.post().uri("/api/guardians/{id}/invite", guardianId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("GUARDIAN_SMS_NOT_AVAILABLE");
    }

    // ---------------------------------------------------- consentement public

    @Test
    void laPagePublique_montreLesDeuxBoutons_etAccepterBasculeEnAccepted() {
        Compte moi = compte();
        creerContactEmail(moi, "Claude", "claude@example.org");
        String token = guardianRepository.findByOwnerIdOrderByCreatedAtDesc(moi.id())
            .get(0).getConsentToken();

        // La page rend du HTML avec les deux formulaires POST.
        String html = new String(webTestClient.get().uri("/public/guardian-consent/{t}", token)
            .exchange().expectStatus().isOk()
            .expectBody().returnResult().getResponseBodyContent());
        assertThat(html)
            .contains("J'accepte")
            .contains("Je refuse")
            .contains("/accept")
            .contains("/refuse");

        webTestClient.post().uri("/public/guardian-consent/{t}/accept", token)
            .exchange().expectStatus().isOk().expectBody().returnResult();

        assertThat(guardianRepository.findByConsentToken(token))
            .get()
            .extracting(g -> g.getConsentState())
            .isEqualTo(ConsentState.ACCEPTED);
    }

    @Test
    void unJetonInconnu_rendUnePageIntrouvable_pas500() {
        webTestClient.get().uri("/public/guardian-consent/{t}", "jetonQuiNexistePas000")
            .exchange().expectStatus().isNotFound();
    }

    // ------------------------------------------------------------- supprimer

    @Test
    void retirerUnContact_leFaitDisparaitreDeLaListe() {
        Compte moi = compte();
        String guardianId = creerContactEmail(moi, "Ex", "ex@example.org");

        webTestClient.delete().uri("/api/guardians/{id}", guardianId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNoContent();

        webTestClient.get().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    // ------------------------------------------------------------------ outils

    private String creerContactEmail(Compte owner, String name, String email) {
        return String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", name, "email", email))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id"));
    }

    private String creerContactTelephone(Compte owner, String name, String phone) {
        return String.valueOf(webTestClient.post().uri("/api/guardians")
            .headers(h -> h.setBearerAuth(owner.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("name", name, "phone", phone))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id"));
    }

    private record Compte(UUID id, String token) {}

    private Compte compte() {
        String email = uniqueEmail("guardian");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Gard" + UUID.randomUUID().toString().substring(0, 8)))
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
