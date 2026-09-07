package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.AuthToken;
import org.program.pair.domain.auth.AuthTokenType;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.AuthTokenRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le livrable (h) : corriger l'adresse d'un compte.
 *
 * <p>Demandé par le chantier mobile le 07/09, et sans lui l'état {@code BOUNCED}
 * qu'apporte ce même lot serait un diagnostic sans remède : l'adresse n'était
 * modifiable nulle part — ni dans {@code UpdateProfileRequest}, ni par une route
 * dédiée — et une faute de frappe à l'inscription enfermait le compte dehors
 * pour de bon.
 *
 * <p>La propriété centrale est que <b>rien ne bascule à la demande</b> : c'est le
 * clic dans l'e-mail envoyé à la nouvelle adresse qui échange les deux. Une
 * seconde faute de frappe ne doit pas coûter le compte, l'adresse étant aussi
 * l'identifiant de connexion.
 */
class ChangementAdresseIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired AuthTokenRepository authTokenRepository;

    @Test
    void laDemande_neChangeRien_avantLeClic() {
        Compte compte = inscrire("avant-clic");
        String nouvelle = uniqueEmail("corrigee");

        demanderChangement(compte, nouvelle).expectStatus().isOk();

        User apres = recharge(compte);
        assertThat(apres.getEmail()).isEqualTo(compte.email());
        assertThat(apres.getPendingEmail()).isEqualTo(nouvelle);

        // Et le contrat le dit aussi : /users/me rend toujours l'ancienne, parce
        // qu'elle reste l'identifiant de connexion tant que l'autre n'a pas
        // prouvé qu'elle reçoit.
        webTestClient.get().uri("/api/users/me")
            .headers(h -> h.setBearerAuth(compte.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.email").isEqualTo(compte.email());
    }

    @Test
    void leClic_basculeLAdresse_etVerifieLeCompte() {
        Compte compte = inscrire("bascule");
        String nouvelle = uniqueEmail("bascule-ok");

        demanderChangement(compte, nouvelle).expectStatus().isOk();
        cliquer(jetonDeChangement(compte)).expectStatus().isOk();

        User apres = recharge(compte);
        assertThat(apres.getEmail()).isEqualTo(nouvelle);
        assertThat(apres.getPendingEmail()).isNull();
        // Le clic prouve que l'adresse existe et reçoit : c'est exactement ce que
        // la vérification demande, et la refaire redemanderait la même preuve.
        assertThat(apres.getVerificationStatus()).isEqualTo(VerificationStatus.EMAIL_VERIFIED);
    }

    @Test
    void uneAdresseDejaPrise_estRefuseeALaDemande() {
        Compte occupant = inscrire("occupant");
        Compte demandeur = inscrire("demandeur");

        demanderChangement(demandeur, occupant.email())
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("EMAIL_EXISTS");

        assertThat(recharge(demandeur).getPendingEmail()).isNull();
    }

    @Test
    void saPropreAdresse_estRefusee() {
        // Sans ce refus, un appui distrait enverrait un e-mail vers l'adresse
        // dont on cherche précisément à sortir — une panne de plus, à l'écran.
        Compte compte = inscrire("soi-meme");

        demanderChangement(compte, compte.email()).expectStatus().isBadRequest();
        assertThat(recharge(compte).getPendingEmail()).isNull();
    }

    @Test
    void uneAdressePrise_entreLaDemandeEtLeClic_abandonneSansCasserLeCompte() {
        Compte demandeur = inscrire("course");
        String convoitee = uniqueEmail("convoitee");

        demanderChangement(demandeur, convoitee).expectStatus().isOk();
        String jeton = jetonDeChangement(demandeur);

        // Quelqu'un d'autre inscrit cette adresse pendant les 24 heures du lien.
        inscrireAvec(convoitee);

        cliquer(jeton).expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("EMAIL_EXISTS");

        User apres = recharge(demandeur);
        assertThat(apres.getEmail()).isEqualTo(demandeur.email()); // le compte reste utilisable
        assertThat(apres.getPendingEmail()).isNull();              // la demande est abandonnée
    }

    @Test
    void redemanderLaMemeAdresse_estAccepte() {
        // Le geste de quelqu'un qui n'a pas reçu le premier lien. Le refuser au
        // motif que l'adresse est « déjà convoitée » — par lui-même — fermerait
        // la seule porte qui lui reste.
        Compte compte = inscrire("redemande");
        String nouvelle = uniqueEmail("redemande-ok");

        demanderChangement(compte, nouvelle).expectStatus().isOk();
        demanderChangement(compte, nouvelle).expectStatus().isOk();

        assertThat(recharge(compte).getPendingEmail()).isEqualTo(nouvelle);
    }

    @Test
    void uneAdresseConvoiteeParUnAutre_estRefusee() {
        Compte premier = inscrire("convoite-1");
        Compte second = inscrire("convoite-2");
        String convoitee = uniqueEmail("disputee");

        demanderChangement(premier, convoitee).expectStatus().isOk();
        demanderChangement(second, convoitee)
            .expectStatus().isEqualTo(409)
            .expectBody().jsonPath("$.code").isEqualTo("EMAIL_EXISTS");

        assertThat(recharge(second).getPendingEmail()).isNull();
    }

    @Test
    void laPageNavigateur_ditQueLAdresseAChange() {
        Compte compte = inscrire("page");

        demanderChangement(compte, uniqueEmail("page-ok")).expectStatus().isOk();

        webTestClient.get().uri("/v/" + jetonDeChangement(compte))
            .header("Accept", MediaType.TEXT_HTML_VALUE)
            .exchange().expectStatus().isOk()
            .expectBody(String.class)
            .value(html -> assertThat(html).contains("Votre nouvelle adresse est confirmée"));
    }

    @Test
    void unSecondClic_neCassePas() {
        Compte compte = inscrire("second-clic");
        String nouvelle = uniqueEmail("second-ok");

        demanderChangement(compte, nouvelle).expectStatus().isOk();
        String jeton = jetonDeChangement(compte);

        cliquer(jeton).expectStatus().isOk();
        cliquer(jeton).expectStatus().isOk(); // « déjà vérifié », pas une panne

        assertThat(recharge(compte).getEmail()).isEqualTo(nouvelle);
    }

    // ------------------------------------------------------------------ outils

    private WebTestClient.ResponseSpec demanderChangement(Compte compte, String email) {
        return webTestClient.post().uri("/api/users/me/change-email")
            .headers(h -> h.setBearerAuth(compte.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"email\":\"" + email + "\"}")
            .exchange();
    }

    private WebTestClient.ResponseSpec cliquer(String jeton) {
        return webTestClient.get().uri("/api/auth/verify-email?token=" + jeton)
            .header("Accept", MediaType.APPLICATION_JSON_VALUE)
            .exchange();
    }

    private String jetonDeChangement(Compte compte) {
        List<AuthToken> jetons = authTokenRepository.findAll().stream()
            .filter(t -> t.getType() == AuthTokenType.EMAIL_CHANGE)
            .filter(t -> t.getUser().getId().equals(compte.id()))
            .filter(t -> !t.estConsomme())
            .toList();
        assertThat(jetons).hasSize(1);
        return jetons.get(0).getToken();
    }

    private User recharge(Compte compte) {
        return userRepository.findById(compte.id()).orElseThrow();
    }

    private Compte inscrire(String prefixe) {
        return inscrireAvec(uniqueEmail(prefixe));
    }

    private Compte inscrireAvec(String email) {
        String corps = webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"email":"%s","password":"MotDePasse123!","displayName":"Test"}
                """.formatted(email))
            .exchange().expectStatus().isCreated()
            .expectBody(String.class).returnResult().getResponseBody();

        try {
            var noeud = objectMapper.readTree(corps);
            return new Compte(
                UUID.fromString(noeud.get("userId").asText()),
                email,
                noeud.get("accessToken").asText());
        } catch (Exception e) {
            throw new IllegalStateException("Réponse d'inscription illisible : " + corps, e);
        }
    }

    private record Compte(UUID id, String email, String token) {}
}
