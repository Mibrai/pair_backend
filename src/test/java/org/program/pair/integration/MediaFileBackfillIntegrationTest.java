package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.media.MediaFileService;
import org.program.pair.domain.media.dto.MediaUploadResponse;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.CategoryRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.BodyInserters;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le rattrapage de V109, éprouvé sur les colonnes réelles.
 *
 * <p><b>Pourquoi rejouer les {@code INSERT} plutôt qu'observer leur résultat.</b>
 * La migration s'exécute une seule fois, au démarrage du conteneur, avant que
 * le moindre test n'ait écrit une ligne : il n'y a donc, à ce moment-là, aucun
 * avatar local à rattacher. Observer la table après coup ne prouverait rien. Ces
 * tests lisent donc le <b>texte même</b> de {@code V109__media_files.sql}, en
 * extraient les quatre {@code INSERT}, et les rejouent sur des données créées
 * juste avant — ce que leur {@code ON CONFLICT DO NOTHING} rend légitime. Ce qui
 * est vérifié est bien le SQL livré, pas une reformulation de celui-ci dans le
 * test.
 *
 * <p><b>Ce que ces tests ont attrapé.</b> La fiche P-BS-01 proposait de
 * rattacher {@code programs.image_url} à un {@code programs.organizer_id} qui
 * <b>n'existe pas</b> : l'hôte se rejoint par
 * {@code programs.user_activity_id -> user_activities.user_id}. Et elle
 * cherchait la photo de souvenir ailleurs qu'à sa place —
 * {@code attendances.memory_photo_url}, posée par V54.
 */
class MediaFileBackfillIntegrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired UserActivityRepository userActivityRepository;

    /** Le test que la fiche demande nommément : les avatars déjà en place. */
    @Test
    void leRattrapage_devraitRattacherUnAvatarExistant_aSonProprietaire() throws IOException {
        Compte moi = compte("rattrapage-avatar");
        String avatarUrl = deposerAvatar(moi.token());
        String chemin = cheminDe(avatarUrl);

        // On ramène la base à l'état d'avant V109 pour CE fichier : l'URL est
        // en base, mais aucune ligne ne dit qui l'a déposé.
        oublierLaLigne(chemin);
        assertThat(deposantDe(chemin)).isNull();

        rejouerLeRattrapage();

        assertThat(deposantDe(chemin)).isEqualTo(moi.id());
        assertThat(usageDe(chemin)).isEqualTo("AVATAR");
    }

    /**
     * L'écart n° 1 avec la fiche, éprouvé : l'hôte d'un programme se rejoint par
     * {@code user_activities}, et le rattrapage le retrouve.
     */
    @Test
    void leRattrapage_devraitRattacherUneCouverture_aLHoteJointParUserActivities() throws IOException {
        Compte hote = compte("rattrapage-couverture");
        UUID programmeId = programme(hote);
        String imageUrl = deposerCouverture(hote.token(), programmeId);
        String chemin = cheminDe(imageUrl);

        oublierLaLigne(chemin);
        assertThat(deposantDe(chemin)).isNull();

        rejouerLeRattrapage();

        assertThat(deposantDe(chemin))
            .as("programs n'a pas d'organizer_id : l'hôte vient de user_activities.user_id")
            .isEqualTo(hote.id());
        assertThat(usageDe(chemin)).isEqualTo("PROGRAM_IMAGE");
    }

    /** Les lignes les plus sensibles de la table : les pièces jointes d'incident. */
    @Test
    void leRattrapage_devraitRattacherUnePieceJointeDIncident_aSonAuteur() throws IOException {
        Compte moi = compte("rattrapage-piece");
        String url = deposerImage(moi.token());
        String chemin = cheminDe(url);

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "PLACE", "note", "Lieu peu rassurant.", "attachmentUrl", url))
            .exchange().expectStatus().isCreated();

        oublierLaLigne(chemin);
        assertThat(deposantDe(chemin)).isNull();

        rejouerLeRattrapage();

        assertThat(deposantDe(chemin)).isEqualTo(moi.id());
        assertThat(usageDe(chemin)).isEqualTo("INCIDENT_ATTACHMENT");
    }

    /**
     * Ce que le rattrapage <b>ne peut pas</b> rattacher, et qui doit rester
     * inatteignable : une icône d'activité posée avant V109.
     *
     * <p>{@code activities} est un référentiel partagé sans colonne d'auteur ;
     * l'ancien code enregistrait un {@code activityId} en guise de déposant,
     * c'est-à-dire personne. Aucun {@code INSERT} du rattrapage ne lit cette
     * colonne, et c'est délibéré : inventer un déposant rendrait ces fichiers
     * supprimables par quelqu'un qui ne les a pas déposés.
     */
    @Test
    void leRattrapage_neDevraitRienInventer_pourUneIconeDActiviteSansAuteur() {
        String chemin = "activity_icon/" + UUID.randomUUID() + ".png";
        UUID activiteId = activitePropre();
        jdbc.update("UPDATE activities SET icon = ? WHERE id = ?",
            MediaFileService.URL_PREFIX + chemin, activiteId);

        rejouerLeRattrapage();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE path = ?", Integer.class, chemin))
            .as("un fichier sans déposant connaissable reste sans ligne, donc non supprimable")
            .isZero();
    }

    /**
     * Les URL externes ne désignent aucun fichier de notre volume : le
     * rattrapage doit les ignorer, sinon la table se remplit de chemins qui
     * n'existent pas.
     */
    @Test
    void leRattrapage_devraitIgnorerUneUrlExterne_quandElleNestPasUnFichierLocal() {
        Compte moi = compte("rattrapage-externe");
        String externe = "https://loremflickr.com/640/400/yoga?lock=1";
        jdbc.update("UPDATE users SET avatar_url = ? WHERE id = ?", externe, moi.id());

        rejouerLeRattrapage();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE uploaded_by = ?", Integer.class, moi.id()))
            .isZero();
    }

    // ------------------------------------------------------------------ outils

    /**
     * Les {@code INSERT} du rattrapage, lus dans le fichier de migration livré.
     *
     * <p>Les lignes de commentaire sont retirées avant le découpage : elles
     * contiennent des points-virgules et des mots-clés, et c'est le seul piège
     * de cette lecture.
     */
    private void rejouerLeRattrapage() {
        for (String instruction : instructionsDuRattrapage()) {
            jdbc.execute(instruction);
        }
    }

    private List<String> instructionsDuRattrapage() {
        String sql;
        try (var flux = new ClassPathResource("db/migration/V109__media_files.sql").getInputStream()) {
            sql = new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("V109__media_files.sql est introuvable sur le classpath", e);
        }

        String sansCommentaires = sql.lines()
            .filter(ligne -> !ligne.stripLeading().startsWith("--"))
            .reduce("", (a, b) -> a + "\n" + b);

        List<String> instructions = Arrays.stream(sansCommentaires.split(";"))
            .map(String::strip)
            .filter(i -> i.toUpperCase(Locale.ROOT).startsWith("INSERT INTO MEDIA_FILES"))
            .toList();

        assertThat(instructions)
            .as("les quatre rattrapages de V109 : avatars, couvertures, pièces jointes, souvenirs")
            .hasSize(4);
        return instructions;
    }

    private void oublierLaLigne(String chemin) {
        jdbc.update("DELETE FROM media_files WHERE path = ?", chemin);
    }

    private UUID deposantDe(String chemin) {
        List<UUID> trouves = jdbc.query(
            "SELECT uploaded_by FROM media_files WHERE path = ?",
            (rs, i) -> rs.getObject("uploaded_by", UUID.class), chemin);
        return trouves.isEmpty() ? null : trouves.get(0);
    }

    private String usageDe(String chemin) {
        return jdbc.queryForObject(
            "SELECT purpose FROM media_files WHERE path = ?", String.class, chemin);
    }

    private record Compte(UUID id, String token) {}

    private UUID activitePropre() {
        Category categorie = categoryRepository.findAll().stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Le catalogue de seed n'a aucune catégorie."));
        String suffixe = UUID.randomUUID().toString().substring(0, 8);
        return activityRepository.save(Activity.builder()
            .category(categorie)
            .name("Activité rattrapage " + suffixe)
            .slug("activite-rattrapage-" + suffixe)
            .icon("sports")
            .build()).getId();
    }

    private UUID programme(Compte hote) {
        User utilisateur = userRepository.findById(hote.id()).orElseThrow();
        UserActivity userActivity = userActivityRepository.save(UserActivity.builder()
            .user(utilisateur)
            .activity(activityRepository.findById(activitePropre()).orElseThrow())
            .visibleOnMap(true)
            .build());

        Map<?, ?> cree = webTestClient.post().uri("/api/programs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + hote.token())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "userActivityId", userActivity.getId().toString(),
                "title", "Programme rattrapage " + UUID.randomUUID().toString().substring(0, 8),
                "isPublic", true))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(cree).isNotNull();
        return UUID.fromString(String.valueOf(cree.get("id")));
    }

    private String deposerAvatar(String token) throws IOException {
        return String.valueOf(webTestClient.post().uri("/api/users/me/avatar")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(corpsPng().build()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("avatarUrl"));
    }

    private String deposerCouverture(String token, UUID programmeId) throws IOException {
        return String.valueOf(webTestClient.post().uri("/api/programs/{id}/image/upload", programmeId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(corpsPng().build()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("imageUrl"));
    }

    private String deposerImage(String token) throws IOException {
        MediaUploadResponse reponse = webTestClient.post().uri("/api/media/upload/image")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(corpsPng().build()))
            .exchange().expectStatus().isOk()
            .expectBody(MediaUploadResponse.class).returnResult().getResponseBody();
        assertThat(reponse).isNotNull();
        return reponse.url();
    }

    private String cheminDe(String url) {
        assertThat(url).startsWith(MediaFileService.URL_PREFIX);
        return url.substring(MediaFileService.URL_PREFIX.length());
    }

    private MultipartBodyBuilder corpsPng() throws IOException {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(pngValide()) {
            @Override
            public String getFilename() {
                return "image.png";
            }
        }).contentType(MediaType.IMAGE_PNG);
        return builder;
    }

    private byte[] pngValide() throws IOException {
        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Compte compte(String prefixe) {
        String email = uniqueEmail(prefixe);
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Rat" + UUID.randomUUID().toString().substring(0, 8)))
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
