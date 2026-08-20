package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.ActivityFormat;
import org.program.pair.domain.activity.ActivityLevel;
import org.program.pair.domain.activity.dto.UpsertUserActivityRequest;
import org.program.pair.domain.activity.dto.UserActivityDto;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.dto.CreateProgramRequest;
import org.program.pair.domain.program.dto.ProgramDto;
import org.program.pair.domain.program.dto.UpdateProgramRequest;
import org.program.pair.domain.publicslot.dto.PublicShareLinkDto;
import org.program.pair.repository.ActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partage public de programme — demande mobile du 2026-08-20.
 *
 * <p>Le défaut corrigé : un programme partagé arrivait chez son destinataire sous
 * la forme {@code meetdo://programs/42}, qu'aucune messagerie ne rend cliquable.
 * Le contrat est celui des créneaux, décliné — mêmes jetons, mêmes DTO, mêmes
 * refus.
 */
class PublicProgramPageIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // — le jeton —

    @Test
    void leJeton_doitEtreCreeALaPremiereDemande_puisRester() {
        String host = registerAndLogin("prog-a");
        UUID programId = activeProgram(host, "Yoga du samedi");

        assertThat(tokenInDb(programId)).isNull();

        PublicShareLinkDto first = shareLink(host, programId);
        assertThat(first.token()).hasSize(22);
        assertThat(first.shortUrl()).isEqualTo("https://lien.meetdo.fun/p/" + first.token());
        assertThat(first.shareable()).isTrue();

        // Redemander ne change pas l'adresse : un lien déjà partagé continuerait
        // de pointer vers l'ancienne.
        assertThat(shareLink(host, programId).token()).isEqualTo(first.token());
    }

    @Test
    void leJeton_neDoitJamaisEtreLidentifiantInterne() {
        // Une adresse bâtie sur la clé primaire se laisse énumérer.
        String host = registerAndLogin("prog-b");
        UUID programId = activeProgram(host, "Escalade du jeudi");

        assertThat(shareLink(host, programId).token()).isNotEqualTo(programId.toString());
    }

    @Test
    void leLien_doitEtreReserveALorganisateur() {
        String host = registerAndLogin("prog-c");
        UUID programId = activeProgram(host, "Course du matin");
        String intruder = registerAndLogin("prog-c2");

        webTestClient.get().uri("/api/programs/{id}/share-link", programId)
            .headers(h -> h.setBearerAuth(intruder))
            .exchange().expectStatus().isNotFound();
    }

    // — la page —

    @Test
    void laPage_doitEtreLisibleSansCompte_avecSesMetadonnees() {
        String host = registerAndLogin("prog-d");
        String token = shareLink(host, activeProgram(host, "Yoga du samedi")).token();

        String html = body("/p/" + token);

        assertThat(html).contains("Yoga du samedi");
        assertThat(html).contains("property=\"og:title\"");
        assertThat(html).contains("property=\"og:description\"");
        assertThat(html).contains("property=\"og:image\"");
        // L'adresse absolue, jamais relative : un robot d'aperçu ne suit pas de
        // chemin relatif.
        assertThat(html).contains("https://lien.meetdo.fun/p/" + token);
    }

    @Test
    void lApercu_doitToujoursPorterUneImage() {
        String host = registerAndLogin("prog-e");
        String token = shareLink(host, activeProgram(host, "Yoga du samedi")).token();

        assertThat(body("/p/" + token))
            .contains("/public/programs/" + token + "/cover.png");

        byte[] png = webTestClient.get().uri("/public/programs/{t}/cover.png", token)
            .exchange().expectStatus().isOk()
            .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
    }

    @Test
    void leBouton_doitViserLApplication_etNonLaPageElleMeme() {
        String host = registerAndLogin("prog-f");
        String token = shareLink(host, activeProgram(host, "Yoga du samedi")).token();

        assertThat(body("/p/" + token)).contains("meetdo://programs/" + token);
    }

    @Test
    void leJson_doitPorterLidentifiantDuProgramme_etRienDesTiers() {
        // L'identifiant du programme EST l'objet du partage : sans lui, le jeton
        // se résout en une description qu'aucun client ne peut afficher, faute
        // d'adresse où aller. Ceux des tiers restent exclus — ils donnent prise
        // sur des personnes que l'organisateur n'a pas partagées.
        String host = registerAndLogin("prog-g");
        UUID programId = activeProgram(host, "Yoga du samedi");
        String token = shareLink(host, programId).token();

        webTestClient.get().uri("/public/programs/{t}", token)
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.programId").isEqualTo(programId.toString())
            .jsonPath("$.title").isEqualTo("Yoga du samedi")
            .jsonPath("$.organizerGivenName").exists()
            .jsonPath("$.userActivityId").doesNotExist()
            .jsonPath("$.organizerId").doesNotExist();
    }

    @Test
    void lidentifiant_neDoitJamaisApparaitreDansUneAdresse() {
        String host = registerAndLogin("prog-g2");
        UUID programId = activeProgram(host, "Yoga du samedi");
        PublicShareLinkDto link = shareLink(host, programId);

        assertThat(link.shortUrl()).doesNotContain(programId.toString());
        assertThat(link.pageUrl()).doesNotContain(programId.toString());
        assertThat(status("/p/" + programId)).isEqualTo(404);
    }

    @Test
    void pageUrl_doitRendreDuHtml_jamaisDesDonnees() {
        // Elle valait /public/programs/{jeton}, la route JSON. Collée dans un
        // message, elle ouvrait un navigateur sur du texte brut — livré une heure
        // côté client avant qu'ils ne s'en aperçoivent.
        String host = registerAndLogin("prog-g3");
        PublicShareLinkDto link = shareLink(host, activeProgram(host, "Yoga du samedi"));

        assertThat(link.pageUrl()).endsWith("/page");

        webTestClient.get().uri(link.pageUrl().replaceFirst("^https://[^/]+", ""))
            .exchange().expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML);
    }

    @Test
    void unProgrammeSansSeance_doitResterVisible() {
        // Un programme n'est pas une occurrence : sans séance à venir il n'est pas
        // périmé, et son auteur peut en reprogrammer une.
        String host = registerAndLogin("prog-h");
        String token = shareLink(host, activeProgram(host, "Yoga du samedi")).token();

        assertThat(status("/p/" + token)).isEqualTo(200);
        webTestClient.get().uri("/public/programs/{t}", token)
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.sessionCount").isEqualTo(0);
    }

    // — les refus —

    @Test
    void unJetonInconnu_doitRendre404_jamais403() {
        assertThat(status("/p/jetonQuiNexistePasDuTout")).isEqualTo(404);
        assertThat(status("/public/programs/jetonQuiNexistePasDuTout")).isEqualTo(404);
    }

    @Test
    void refermerLePartage_doitEteindreLeLien_sansChangerLeJeton() {
        String host = registerAndLogin("prog-i");
        UUID programId = activeProgram(host, "Yoga du samedi");
        String token = shareLink(host, programId).token();

        assertThat(setShareable(host, programId, false).shareable()).isFalse();
        assertThat(status("/p/" + token)).isEqualTo(404);

        PublicShareLinkDto reopened = setShareable(host, programId, true);
        assertThat(reopened.token()).isEqualTo(token);
        assertThat(status("/p/" + token)).isEqualTo(200);
    }

    @Test
    void unProgrammeRedevenuPrive_neDoitPlusAvoirDePage() {
        String host = registerAndLogin("prog-j");
        UUID programId = activeProgram(host, "Yoga du samedi");
        String token = shareLink(host, programId).token();

        jdbcTemplate.update("UPDATE programs SET is_public = false WHERE id = ?", programId);

        assertThat(status("/p/" + token)).isEqualTo(404);
    }

    @Test
    void unProgrammeArchive_neDoitPlusAvoirDePage() {
        String host = registerAndLogin("prog-k");
        UUID programId = activeProgram(host, "Yoga du samedi");
        String token = shareLink(host, programId).token();

        jdbcTemplate.update("UPDATE programs SET archived_at = now() WHERE id = ?", programId);

        assertThat(status("/p/" + token)).isEqualTo(404);
    }

    // — l'association et le verbe HEAD —

    @Test
    void lassociationApple_doitDeclarerLesMotifsDeProgramme() {
        // iOS ignore en silence ce que ce fichier ne déclare pas : sans ces
        // motifs, la route répondrait et le lien n'ouvrirait jamais l'app.
        String aasa = body("/.well-known/apple-app-site-association");

        assertThat(aasa).contains("/p/*").contains("/public/programs/*");
        assertThat(aasa).contains("/s/*").contains("/public/slots/*");
    }

    @Test
    void head_doitRepondreCommeGet_surLesPagesPubliques() {
        // Relevé par l'équipe mobile : HEAD rendait 401 là où GET rend 200, les
        // règles de sécurité ne nommant que GET. Rien n'était cassé — Apple fait
        // des GET — mais tout diagnostic mené en « curl -I » concluait que la
        // page était protégée.
        String host = registerAndLogin("prog-l");
        String token = shareLink(host, activeProgram(host, "Yoga du samedi")).token();

        assertThat(headStatus("/.well-known/apple-app-site-association")).isEqualTo(200);
        assertThat(headStatus("/p/" + token)).isEqualTo(200);
        assertThat(headStatus("/public/programs/" + token)).isEqualTo(200);
    }

    // — helpers —

    private String body(String uri) {
        return new String(webTestClient.get().uri(uri)
            .exchange().expectBody().returnResult().getResponseBody());
    }

    private int status(String uri) {
        return webTestClient.get().uri(uri)
            .exchange().returnResult(byte[].class).getStatus().value();
    }

    private int headStatus(String uri) {
        return webTestClient.head().uri(uri)
            .exchange().returnResult(byte[].class).getStatus().value();
    }

    private String tokenInDb(UUID programId) {
        return jdbcTemplate.queryForObject(
            "SELECT public_share_token FROM programs WHERE id = ?", String.class, programId);
    }

    private PublicShareLinkDto shareLink(String token, UUID programId) {
        return webTestClient.get().uri("/api/programs/{id}/share-link", programId)
            .headers(h -> h.setBearerAuth(token))
            .exchange().expectStatus().isOk()
            .expectBody(PublicShareLinkDto.class).returnResult().getResponseBody();
    }

    private PublicShareLinkDto setShareable(String token, UUID programId, boolean shareable) {
        return webTestClient.patch().uri("/api/programs/{id}/shareable", programId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("isPubliclyShareable", shareable))
            .exchange().expectStatus().isOk()
            .expectBody(PublicShareLinkDto.class).returnResult().getResponseBody();
    }

    private UUID activeProgram(String token, String title) {
        UUID activityId = activityRepository.findAll().get(0).getId();
        UserActivityDto userActivity = webTestClient.post().uri("/api/users/me/activities")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new UpsertUserActivityRequest(
                activityId, true, null, ActivityLevel.INTERMEDIATE, ActivityFormat.GROUP))
            .exchange().expectStatus().isCreated()
            .expectBody(UserActivityDto.class).returnResult().getResponseBody();

        ProgramDto created = webTestClient.post().uri("/api/programs")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new CreateProgramRequest(
                userActivity.id(), title, "Programme ouvert à tous.", true, null,
                null, null, null, null, null, null, null, null, null, null))
            .exchange().expectStatus().isCreated()
            .expectBody(ProgramDto.class).returnResult().getResponseBody();

        webTestClient.put().uri("/api/programs/{id}", created.id())
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new UpdateProgramRequest(
                null, null, ProgramStatus.ACTIVE, true, null,
                null, null, null, null, null, null, null, null, null, null))
            .exchange().expectStatus().isOk();

        return created.id();
    }

    private String registerAndLogin(String prefix) {
        String email = uniqueEmail(prefix);
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", "Organisateur"))
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
