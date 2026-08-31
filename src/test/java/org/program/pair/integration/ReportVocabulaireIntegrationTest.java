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
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le vocabulaire de la colonne {@code status}, et pourquoi une lecture tombait
 * là où l'écriture passait.
 *
 * <p>{@code ReportStatus} vaut {@code PENDING, REVIEWED, ACTIONED, DISMISSED}.
 * Le jeu de démonstration, lui, écrivait {@code 'OPEN'} et {@code 'RESOLVED'} —
 * le vocabulaire de la table d'origine (V9), que personne n'a repris quand
 * l'entité a été écrite. Le champ étant {@code @Enumerated(EnumType.STRING)},
 * Hibernate appelle {@code ReportStatus.valueOf("OPEN")} à chaque lecture et
 * lève ; le gestionnaire global rend un {@code 500 INTERNAL_ERROR}.
 *
 * <p>Rien de tout cela ne se voyait à l'écriture : {@code createReport} pose
 * {@code PENDING} en dur, il n'y a jamais de conversion entrante. D'où l'asymétrie
 * relevée par le chantier mobile — {@code POST /api/reports} en {@code 201},
 * {@code GET /api/reports/me} en {@code 500} — qui ressemblait à un défaut de
 * sérialisation et n'était qu'une donnée hors vocabulaire.
 *
 * <p>Conséquence moins visible et plus grave : les six signalements semés en
 * {@code 'OPEN'} n'étaient <b>pas</b> {@code PENDING}, donc
 * {@code GET /api/reports/pending} rendait une file de modération vide alors
 * qu'elle ne l'était pas.
 */
class ReportVocabulaireIntegrationTest extends AbstractIntegrationTest {

    @Autowired ReportRepository reportRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * Le défaut nu, sans passer par HTTP : avant la normalisation, ce
     * {@code findAll} lève sur la première ligne semée en {@code 'OPEN'}.
     */
    @Test
    void toutesLesLignesDeLaTable_doiventSeLireDansLenumJava() {
        assertThatCode(() -> reportRepository.findAll())
            .doesNotThrowAnyException();

        assertThat(reportRepository.findAll())
            .isNotEmpty()
            .allSatisfy(report -> assertThat(report.getStatus()).isNotNull());
    }

    /**
     * La file de modération : les signalements semés « ouverts » doivent s'y
     * trouver. Zéro ligne ici, c'est une file invisible, pas une file vide.
     */
    @Test
    void lesSignalementsSemesOuverts_doiventEtreEnAttente() {
        List<Report> enAttente = reportRepository
            .findByStatusOrderByCreatedAtDesc(ReportStatus.PENDING, org.springframework.data.domain.PageRequest.of(0, 50))
            .getContent();

        assertThat(enAttente).isNotEmpty();
    }

    /**
     * Le critère d'acceptation du lot : un compte qui possède au moins un
     * signalement obtient sa page, pas un {@code 500}.
     */
    @Test
    void mesSignalements_doitRendreLaPageDunCompteQuiASignale() {
        Compte auteur = compte();
        Compte cible = compte();

        webTestClient.post().uri("/api/reports")
            .headers(h -> h.setBearerAuth(auteur.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "reportedEntityType", "USER",
                "reportedEntityId", cible.id.toString(),
                "reason", "OTHER",
                "description", "Description assez longue pour passer la validation."))
            .exchange().expectStatus().isCreated();

        webTestClient.get().uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(auteur.token))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.content.length()").isEqualTo(1)
            // Le champ s'appelle « state » et vaut RECEIVED depuis que la route
            // rend ReportSummaryDto : PENDING est le mot de la modération, pas
            // celui du signalant. Voir MesSignalementsFormeIntegrationTest.
            .jsonPath("$.content[0].state").isEqualTo("RECEIVED");
    }

    /**
     * Ce qui empêche la situation de revenir : la base refuse désormais un mot
     * que l'enum ne connaît pas. Sans cette contrainte, la normalisation ne
     * vaudrait que pour les lignes d'aujourd'hui.
     */
    @Test
    void laBase_doitRefuserUnStatutHorsVocabulaire() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO reports (id, reporter_id, reported_entity_type, reported_entity_id,
                                 reason, status, created_at, updated_at)
            SELECT gen_random_uuid(), u.id, 'USER', u.id, 'OTHER', 'OPEN', NOW(), NOW()
            FROM users u LIMIT 1
            """))
            .hasMessageContaining("reports_status_vocabulaire");
    }

    /**
     * Se signaler soi-même occupait la file de modération avec un signalement
     * qui ne veut rien dire. {@code 422} et non {@code 409} : ce n'est pas
     * « c'est déjà fait », c'est « vous n'avez pas à faire ça ».
     */
    @Test
    void seSignalerSoiMeme_doitEtreRefuseEn422() {
        Compte moi = compte();

        webTestClient.post().uri("/api/reports")
            .headers(h -> h.setBearerAuth(moi.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "reportedEntityType", "USER",
                "reportedEntityId", moi.id.toString(),
                "reason", "OTHER",
                "description", "Description assez longue pour passer la validation."))
            .exchange()
            .expectStatus().isEqualTo(422)
            .expectBody()
            .jsonPath("$.code").isEqualTo("BUSINESS_RULE_VIOLATION");
    }

    /**
     * Le refus ne doit rien écrire : la file de modération est précisément ce
     * que cette règle protège, un 422 qui laisserait la ligne derrière lui ne
     * servirait à rien.
     */
    @Test
    void seSignalerSoiMeme_neDoitRienEcrire() {
        Compte moi = compte();

        webTestClient.post().uri("/api/reports")
            .headers(h -> h.setBearerAuth(moi.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "reportedEntityType", "USER",
                "reportedEntityId", moi.id.toString(),
                "reason", "OTHER",
                "description", "Description assez longue pour passer la validation."))
            .exchange().expectStatus().isEqualTo(422);

        webTestClient.get().uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(moi.token))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.content.length()").isEqualTo(0);
    }

    /**
     * La règle est restreinte à {@code USER}, le seul cas que le chantier mobile
     * a observé. Signaler autrui reste évidemment permis — ce test garde la
     * frontière, pour qu'un durcissement futur ne l'emporte pas par mégarde.
     */
    @Test
    void signalerQuelquunDautre_resteAutorise() {
        Compte moi = compte();
        Compte autre = compte();

        webTestClient.post().uri("/api/reports")
            .headers(h -> h.setBearerAuth(moi.token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "reportedEntityType", "USER",
                "reportedEntityId", autre.id.toString(),
                "reason", "OTHER",
                "description", "Description assez longue pour passer la validation."))
            .exchange().expectStatus().isCreated();
    }

    // — helpers —

    private record Compte(UUID id, String token) {}

    private Compte compte() {
        String email = uniqueEmail("vocab-report");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Voc" + UUID.randomUUID().toString().substring(0, 8)))
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
