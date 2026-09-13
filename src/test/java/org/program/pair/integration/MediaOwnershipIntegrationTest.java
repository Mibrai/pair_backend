package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.media.MediaFile;
import org.program.pair.domain.media.MediaFileService;
import org.program.pair.domain.media.MediaFileRepository;
import org.program.pair.domain.media.MediaPurpose;
import org.program.pair.domain.media.dto.MediaUploadResponse;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.CategoryRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.domain.user.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La propriété des fichiers déposés, de bout en bout (fiche P-BS-01, partie B).
 *
 * <p><b>Ce que ces tests tiennent, et que rien ne tenait avant V109.</b> Le
 * stockage ne gardait aucun déposant : {@code store()} recevait bien un
 * identifiant, mais il ne servait qu'au journal, et deux appelants sur quatre y
 * passaient un {@code programId} ou un {@code activityId}. Aucune suppression ne
 * pouvait donc être autorisée, faute de quelqu'un à comparer — c'est la raison
 * pour laquelle le lot 0 a <i>retiré</i> la route générique de suppression
 * plutôt que de la garder (voir {@code MediaFileDeletionIntegrationTest}).
 *
 * <p>Le test qui porte la décision de l'étape 9 est
 * {@link #remplacerLIconeDuneActivite_neDevraitPasSupprimerLeFichierDunAutre()} :
 * {@code activities} est un référentiel <b>partagé</b> sans colonne d'auteur
 * (V3, V22, V38), donc le remplacement reste permis à tout compte — mais il ne
 * doit plus détruire au passage le fichier de quelqu'un d'autre.
 */
class MediaOwnershipIntegrationTest extends AbstractIntegrationTest {

    @Autowired MediaFileRepository mediaFileRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired UserRepository userRepository;
    @Autowired org.program.pair.repository.ConversationRepository conversationRepository;
    @Autowired org.program.pair.repository.ConversationMemberRepository conversationMemberRepository;

    /**
     * Le dépôt écrit qui a déposé. C'est la brique dont tout le reste découle.
     */
    @Test
    void unUpload_devraitEnregistrerLAuteurDuFichier_quandUneImageEstDeposee() throws IOException {
        Compte moi = compte("media-auteur");
        String url = deposerImage(moi.token());

        MediaFile ligne = ligneDe(url);

        assertThat(ligne.getUploadedBy())
            .as("le déposant est l'appelant, jamais un identifiant de programme ou d'activité")
            .isEqualTo(moi.id());
        assertThat(ligne.getCreatedAt()).isNotNull();
    }

    /**
     * L'avatar est déposé par une autre route, et doit l'enregistrer aussi —
     * avec l'usage qui lui convient.
     */
    @Test
    void unAvatar_devraitEnregistrerSonAuteurEtSonUsage_quandIlEstDepose() throws IOException {
        Compte moi = compte("media-auteur-avatar");

        String avatarUrl = String.valueOf(webTestClient.post()
            .uri("/api/users/me/avatar")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + moi.token())
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(corpsPng().build()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("avatarUrl"));

        MediaFile ligne = ligneDe(avatarUrl);

        assertThat(ligne.getUploadedBy()).isEqualTo(moi.id());
        assertThat(ligne.getPurpose()).isEqualTo(MediaPurpose.AVATAR);
    }

    /**
     * Le scénario d'abus de l'étape 9, rejoué en entier.
     *
     * <p>A dépose l'icône d'une activité du référentiel partagé. B remplace
     * cette icône — ce qui reste <b>permis</b>, faute d'auteur sur
     * {@code activities} — et le fichier de A doit survivre. Avant, le
     * {@code DELETE} de la route et le remplacement effaçaient les octets sans
     * rien demander à personne : tout compte pouvait détruire le fichier d'un
     * autre en passant par une activité partagée.
     */
    @Test
    void remplacerLIconeDuneActivite_neDevraitPasSupprimerLeFichierDunAutre() throws IOException {
        Compte a = compte("icone-a");
        Compte b = compte("icone-b");
        UUID activiteId = activitePropre();

        String iconeDeA = deposerIcone(a.token(), activiteId);
        assertThat(ligneDe(iconeDeA).getUploadedBy()).isEqualTo(a.id());

        String iconeDeB = deposerIcone(b.token(), activiteId);
        assertThat(iconeDeB).isNotEqualTo(iconeDeA);

        // Le fichier de A est toujours servi : B a remplacé la référence, pas
        // les octets d'autrui.
        webTestClient.get().uri(iconeDeA)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + a.token())
            .exchange().expectStatus().isOk();
        assertThat(mediaFileRepository.findById(cheminDe(iconeDeA)))
            .as("et sa ligne de propriété non plus")
            .isPresent();

        // Et celui de B est bien devenu l'icône.
        webTestClient.get().uri(iconeDeB)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + b.token())
            .exchange().expectStatus().isOk();
    }

    /**
     * Le pendant positif : remplacer <b>sa propre</b> icône libère bien
     * l'ancien fichier. Sans ce test, « ne rien supprimer » passerait pour un
     * correctif alors que ce serait une fuite d'espace disque à chaque dépôt.
     */
    @Test
    void remplacerSaPropreIcone_devraitEffacerLAncienFichier() throws IOException {
        Compte moi = compte("icone-mienne");
        UUID activiteId = activitePropre();

        String premiere = deposerIcone(moi.token(), activiteId);
        String seconde = deposerIcone(moi.token(), activiteId);

        assertThat(seconde).isNotEqualTo(premiere);
        webTestClient.get().uri(premiere)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + moi.token())
            .exchange().expectStatus().isNotFound();
        assertThat(mediaFileRepository.findById(cheminDe(premiere))).isEmpty();
    }

    /**
     * La couverture d'un programme est déposée par l'hôte, pas par le programme.
     *
     * <p>{@code ProgramController} passait {@code programId} à {@code store()} :
     * la ligne de propriété désignait alors un objet, donc personne, et le
     * fichier devenait insupprimable par son propre auteur.
     */
    @Test
    void uneCouvertureDeProgramme_devraitAvoirPourDeposantLHote_etNonLeProgramme() throws IOException {
        Compte hote = compte("couverture-hote");
        UUID programmeId = programme(hote);

        String imageUrl = String.valueOf(webTestClient.post()
            .uri("/api/programs/{id}/image/upload", programmeId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + hote.token())
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(corpsPng().build()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody().get("imageUrl"));

        MediaFile ligne = ligneDe(imageUrl);
        assertThat(ligne.getUploadedBy()).isEqualTo(hote.id());
        assertThat(ligne.getUploadedBy()).isNotEqualTo(programmeId);
        assertThat(ligne.getPurpose()).isEqualTo(MediaPurpose.PROGRAM_IMAGE);

        // Et l'hôte peut donc retirer sa couverture, octets compris.
        webTestClient.delete().uri("/api/programs/{id}/image", programmeId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + hote.token())
            .exchange().expectStatus().isOk();

        webTestClient.get().uri(imageUrl)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + hote.token())
            .exchange().expectStatus().isNotFound();
    }

    /**
     * Le chemin non borné, vu depuis HTTP (fiche P-BS-11).
     *
     * <p>Ce test ne prouve pas à lui seul le bornage — le
     * {@code StrictHttpFirewall} de Spring Security refuse une partie de ces
     * formes avant d'atteindre le contrôleur, et c'est très bien. Ce qu'il
     * prouve, c'est le <b>résultat</b> : aucune de ces adresses ne rend jamais
     * un fichier. Le bornage lui-même, indépendant du cadre HTTP et des
     * appelants internes, est éprouvé dans
     * {@code domain/media/LocalStorageServiceTest}.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/api/media/files//etc/passwd",
        "/api/media/files/../../../etc/passwd",
        "/api/media/files/user_avatar/../../../etc/passwd",
        "/api/media/files/%2e%2e%2f%2e%2e%2fetc%2fpasswd"
    })
    void unCheminHorsDuStockage_neDevraitJamaisRendreDeFichier(String chemin) {
        Compte moi = compte("traversee");

        byte[] corps = webTestClient.get().uri(chemin)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + moi.token())
            .exchange()
            .expectStatus().value(statut -> assertThat(statut)
                .as("jamais 200 sur un chemin qui sort du stockage")
                .isNotEqualTo(200))
            .expectBody(byte[].class)
            .returnResult()
            .getResponseBody();

        if (corps != null) {
            assertThat(new String(corps, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("root:");
        }
    }

    // ------------------------------------------------------------------ outils

    private record Compte(UUID id, String token) {}

    /**
     * Une activité à soi, jamais une activité du catalogue.
     *
     * <p>La base est partagée par toute la suite : changer l'icône de « yoga »
     * ferait échouer, au hasard de l'ordre d'exécution, n'importe quel test qui
     * la lit. Chaque méthode fabrique donc la sienne, avec un slug unique.
     */
    private UUID activitePropre() {
        Category categorie = categoryRepository.findAll().stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Le catalogue de seed n'a aucune catégorie."));
        String suffixe = UUID.randomUUID().toString().substring(0, 8);
        return activityRepository.save(Activity.builder()
            .category(categorie)
            .name("Activité de test " + suffixe)
            .slug("activite-de-test-" + suffixe)
            .icon("sports")
            .build()).getId();
    }

    /**
     * Un programme dont l'appelant est l'hôte.
     *
     * <p>Le {@code UserActivity} est créé ici parce que c'est <b>par lui</b> que
     * le rattrapage V109 rejoint le déposant d'une couverture :
     * {@code programs.user_activity_id -> user_activities.user_id}. La fiche
     * proposait un {@code programs.organizer_id} qui n'existe pas.
     */
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
                "title", "Programme média " + UUID.randomUUID().toString().substring(0, 8),
                "isPublic", true))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(cree).isNotNull();
        return UUID.fromString(String.valueOf(cree.get("id")));
    }

    private String deposerImage(String token) throws IOException {
        MediaUploadResponse reponse = webTestClient.post()
            .uri("/api/media/upload/image")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(corpsPng().build()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(MediaUploadResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(reponse).isNotNull();
        assertThat(reponse.url()).startsWith(MediaFileService.URL_PREFIX);
        return reponse.url();
    }

    /**
     * P-MS-01, étape 4 — l'image d'une conversation doit être un fichier du
     * service, déposé par l'appelant. La route renvoyait telle quelle
     * n'importe quelle URL reçue.
     */
    @Test
    void uneImageDeConversation_nePeutPasPointerHorsDeNosMedias() throws IOException {
        Compte moi = compte("chat-image");
        UUID conversation = conversationDe(moi.id());

        webTestClient.post()
            .uri(b -> b.path("/api/conversations/{id}/images")
                .queryParam("image", "https://pistage.example/pixel.png").build(conversation))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + moi.token())
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("MEDIA_URL_INVALID");

        String url = deposerImage(moi.token());
        String rendue = webTestClient.post()
            .uri(b -> b.path("/api/conversations/{id}/images").queryParam("image", url).build(conversation))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + moi.token())
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        assertThat(rendue).isEqualTo(url);
    }

    @Test
    void uneImageDeConversation_deposeeParUnAutre_estRefusee() throws IOException {
        Compte auteur = compte("chat-image-auteur");
        Compte autre = compte("chat-image-autre");
        String url = deposerImage(auteur.token());

        webTestClient.post()
            .uri(b -> b.path("/api/conversations/{id}/images")
                .queryParam("image", url).build(conversationDe(autre.id())))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + autre.token())
            .exchange()
            .expectStatus().isBadRequest();
    }

    private UUID conversationDe(UUID membre) {
        org.program.pair.domain.chat.Conversation conversation = conversationRepository.save(
            org.program.pair.domain.chat.Conversation.builder()
                .type(org.program.pair.domain.chat.ConversationType.GROUP).build());
        org.program.pair.domain.chat.ConversationMember ligne = new org.program.pair.domain.chat.ConversationMember();
        ligne.getId().setConversationId(conversation.getId());
        ligne.getId().setUserId(membre);
        ligne.setConversation(conversation);
        ligne.setUser(userRepository.findById(membre).orElseThrow());
        conversationMemberRepository.save(ligne);
        return conversation.getId();
    }

    private String deposerIcone(String token, UUID activiteId) throws IOException {
        Map<?, ?> activite = webTestClient.post()
            .uri("/api/activities/{id}/icon/upload", activiteId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(corpsPng().build()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();

        assertThat(activite).isNotNull();
        String icone = String.valueOf(activite.get("icon"));
        assertThat(icone).startsWith(MediaFileService.URL_PREFIX + "activity_icon/");
        return icone;
    }

    private MediaFile ligneDe(String url) {
        return mediaFileRepository.findById(cheminDe(url))
            .orElseThrow(() -> new AssertionError("Aucune ligne media_files pour " + url));
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
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Compte compte(String prefixe) {
        String email = uniqueEmail(prefixe);
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Med" + UUID.randomUUID().toString().substring(0, 8)))
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
