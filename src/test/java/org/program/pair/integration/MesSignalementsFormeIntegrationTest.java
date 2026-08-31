package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.report.Report;
import org.program.pair.domain.report.ReportStatus;
import org.program.pair.repository.ReportRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La forme de {@code GET /api/reports/me}, et la fuite qu'elle referme.
 *
 * <p>La route rendait {@code Page<Report>}. L'entité est annotée {@code @Data} :
 * tous ses champs partaient au client, dont {@code resolutionNotes} — les notes
 * internes rédigées par le modérateur — et {@code reviewedBy}, son identifiant.
 * Un signalant lisait donc les notes de modération le concernant et savait qui
 * l'avait traité.
 *
 * <p>Rien ne le signalait : la route rendait {@code 200}, le client lisait les
 * deux ou trois champs qu'il affichait, et les autres voyageaient sans que
 * personne les regarde. C'est le mode d'échec propre aux entités servies telles
 * quelles — il ne se manifeste jamais, jusqu'au jour où quelqu'un ouvre la
 * réponse.
 *
 * <p>Le test le plus important de cette classe est donc
 * {@link #laReponse_neDoitPorterNiNotesDeModerationNiModerateur()}, et il est
 * écrit en interrogeant le <b>corps brut</b> plutôt que des chemins JSON : un
 * champ qu'on aurait oublié d'exclure n'apparaîtrait dans aucune assertion
 * nommée, et c'est précisément celui qu'on cherche.
 */
class MesSignalementsFormeIntegrationTest extends AbstractIntegrationTest {

    @Autowired ReportRepository reportRepository;

    /**
     * Le contrat convenu avec le chantier mobile le 31/08 : cinq champs, pas un
     * de plus, et {@code state} plutôt que {@code status}.
     */
    @Test
    void mesSignalements_doitRendreLesCinqChampsDuContrat() {
        Compte auteur = compte();
        signaler(auteur, compte().id());

        webTestClient.get().uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(auteur.token()))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.content[0].id").exists()
            .jsonPath("$.content[0].targetType").isEqualTo("USER")
            .jsonPath("$.content[0].state").isEqualTo("RECEIVED")
            .jsonPath("$.content[0].createdAt").exists()
            .jsonPath("$.content[0].updatedAt").exists();
    }

    /**
     * La fuite elle-même.
     *
     * <p>Le signalement est traité avec une note qui décrit une décision, comme
     * une vraie note de modération le ferait. Ni elle, ni l'identifiant du
     * modérateur ne doivent ressortir — pas plus que le nom des champs qui les
     * portaient, qu'un client pourrait se remettre à lire.
     */
    @Test
    void laReponse_neDoitPorterNiNotesDeModerationNiModerateur() {
        Compte auteur = compte();
        Compte moderateur = compte();
        UUID signalementId = signaler(auteur, compte().id());

        Report signalement = reportRepository.findById(signalementId).orElseThrow();
        signalement.setStatus(ReportStatus.ACTIONED);
        signalement.setReviewedBy(moderateur.id());
        signalement.setReviewedAt(Instant.now());
        signalement.setResolutionNotes(
            "Avertissement adressé au compte visé, deuxième manquement en un mois.");
        reportRepository.saveAndFlush(signalement);

        String corps = new String(webTestClient.get().uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(auteur.token()))
            .exchange()
            .expectStatus().isOk()
            .expectBody().returnResult().getResponseBodyContent());

        assertThat(corps)
            .doesNotContain("Avertissement adressé")
            .doesNotContain("resolutionNotes")
            .doesNotContain("reviewedBy")
            .doesNotContain("reviewedAt")
            .doesNotContain(moderateur.id().toString());
    }

    /**
     * {@code REVIEWED} et {@code ACTIONED} se projettent tous deux sur
     * {@code RESOLVED}.
     *
     * <p>La distinction dit si une sanction a suivi. Elle appartient à l'équipe
     * qui traite : la rendre au signalant reviendrait à lui rendre compte de ce
     * qui est arrivé à quelqu'un d'autre.
     *
     * <p>Les deux statuts sont posés sur le <b>même</b> signalement, l'un après
     * l'autre. Ce n'est pas un raccourci : le limiteur d'inscriptions n'accorde
     * que cinq comptes par méthode de test, et une méthode qui en crée un par
     * assertion finit par recevoir des {@code 429} sur ses derniers cas — donc
     * par échouer pour une raison qui n'a rien à voir avec ce qu'elle vérifie.
     */
    @Test
    void traiteEtSanctionne_doiventSeLireIdentiquement() {
        Compte auteur = compte();
        UUID signalementId = signaler(auteur, compte().id());

        assertThat(etatServiApres(auteur, signalementId, ReportStatus.REVIEWED))
            .isEqualTo("RESOLVED");
        assertThat(etatServiApres(auteur, signalementId, ReportStatus.ACTIONED))
            .isEqualTo("RESOLVED");
    }

    /**
     * {@code DISMISSED} s'affiche tel quel.
     *
     * <p>Un signalement classé sans suite maintenu en « en cours » est pire
     * qu'un refus assumé : il apprend à l'utilisateur que le suivi ment, et un
     * suivi auquel on ne croit plus est un signalement qu'on ne refait pas.
     */
    @Test
    void classeSansSuite_doitEtreAffichable() {
        Compte auteur = compte();
        UUID signalementId = signaler(auteur, compte().id());

        assertThat(etatServiApres(auteur, signalementId, ReportStatus.DISMISSED))
            .isEqualTo("DISMISSED");
    }

    /** L'état que la route sert après avoir placé ce signalement dans ce statut. */
    private String etatServiApres(Compte auteur, UUID signalementId, ReportStatus status) {
        Report signalement = reportRepository.findById(signalementId).orElseThrow();
        signalement.setStatus(status);
        reportRepository.saveAndFlush(signalement);

        return String.valueOf(((Map<?, ?>) ((java.util.List<?>) webTestClient.get()
            .uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(auteur.token()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody()
            .get("content")).get(0)).get("state"));
    }

    private UUID signaler(Compte auteur, UUID cible) {
        return UUID.fromString(String.valueOf(webTestClient.post().uri("/api/reports")
            .headers(h -> h.setBearerAuth(auteur.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "reportedEntityType", "USER",
                "reportedEntityId", cible.toString(),
                "reason", "OTHER",
                "description", "Description assez longue pour passer la validation."))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id")));
    }

    private record Compte(UUID id, String token) {}

    private Compte compte() {
        String email = uniqueEmail("forme-report");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Fmr" + UUID.randomUUID().toString().substring(0, 8)))
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
