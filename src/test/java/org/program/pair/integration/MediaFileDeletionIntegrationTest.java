package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.media.dto.MediaUploadResponse;
import org.program.pair.domain.user.dto.UserPrivateDto;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La suppression générique de fichiers, et ce qui la remplace.
 *
 * <p><b>Le défaut fermé ici.</b> {@code DELETE /api/media/files/**} prenait le
 * chemin dans l'URL et le passait au stockage sans vérifier autre chose qu'un
 * jeton valide : l'appelant ne servait qu'à écrire la ligne de journal. Comme
 * les chemins de fichiers voyagent en clair dans les DTO — l'avatar d'un
 * profil, l'image d'un programme, la pièce jointe d'un signalement — il
 * suffisait de lire une réponse d'API pour effacer le fichier d'un autre
 * compte. Un signalement pouvait donc perdre sa preuve par la main de la
 * personne signalée.
 *
 * <p><b>Pourquoi 405 et pas 403.</b> Rien, sur le disque, ne dit qui a déposé
 * quoi : le stockage ne garde pas d'auteur. Une garde d'autorisation n'aurait
 * eu personne à comparer ; la route est donc retirée, et c'est le refus de
 * méthode qui le prouve. Le jour où la table de propriété des médias arrive
 * (fiche P-BS-01, partie B), un nouveau {@code DELETE} devra vérifier l'auteur,
 * et ce test-ci deviendra alors celui qu'il faudra réécrire sciemment — plutôt
 * que de laisser la route revenir sans garde par simple revert.
 *
 * <p>Le troisième test est la non-régression qui compte : retirer la route ne
 * doit pas empêcher quelqu'un d'effacer son propre avatar, seule suppression
 * que l'application appelle réellement.
 */
class MediaFileDeletionIntegrationTest extends AbstractIntegrationTest {

    /**
     * Le refus lui-même : un DELETE sur le motif générique n'est plus une
     * méthode connue, et le fichier visé est toujours servi après coup.
     */
    @Test
    void routeGeneriqueDeSuppression_devraitRendre405EtLaisserLeFichierEnPlace() throws IOException {
        String token = registerAndLogin(uniqueEmail("media-delete-405"));
        String url = uploadImage(token);

        webTestClient.delete()
            .uri(url)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
            .expectBody()
            .jsonPath("$.code").isEqualTo("METHOD_NOT_ALLOWED");

        // La preuve que le refus n'a rien détruit au passage.
        webTestClient.get()
            .uri(url)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isOk();
    }

    /**
     * Le scénario d'abus, rejoué en entier : le compte B connaît le chemin du
     * fichier de A — il lui suffit d'avoir lu une réponse d'API — et tente de
     * l'effacer. Le refus doit être 405, donc indépendant de toute
     * autorisation, et le fichier de A doit rester lisible par A.
     */
    @Test
    void unAutreCompte_neDevraitPlusPouvoirSupprimerLeFichierDeQuelquun() throws IOException {
        String tokenAuteur = registerAndLogin(uniqueEmail("media-delete-auteur"));
        String url = uploadImage(tokenAuteur);

        String tokenTiers = registerAndLogin(uniqueEmail("media-delete-tiers"));

        webTestClient.delete()
            .uri(url)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenTiers)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        webTestClient.get()
            .uri(url)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAuteur)
            .exchange()
            .expectStatus().isOk();
    }

    /**
     * Non-régression : la seule suppression que l'application appelle
     * réellement — son propre avatar — efface toujours le fichier. Elle connaît
     * le propriétaire de la ressource, donc elle n'a pas besoin de la route
     * générique pour être sûre.
     */
    @Test
    void supprimerSonPropreAvatar_devraitToujoursEffacerLeFichier() throws IOException {
        String token = registerAndLogin(uniqueEmail("media-delete-avatar"));

        UserPrivateDto avecAvatar = webTestClient.post()
            .uri("/api/users/me/avatar")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(pngUploadBody().build()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(UserPrivateDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(avecAvatar).isNotNull();
        assertThat(avecAvatar.avatarUrl()).startsWith("/api/media/files/user_avatar/");
        String avatarUrl = avecAvatar.avatarUrl();

        webTestClient.get()
            .uri(avatarUrl)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isOk();

        UserPrivateDto sansAvatar = webTestClient.delete()
            .uri("/api/users/me/avatar")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isOk()
            .expectBody(UserPrivateDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(sansAvatar).isNotNull();
        assertThat(sansAvatar.avatarUrl()).isNull();

        // Le profil oublie l'URL ET le fichier quitte le disque : sans la
        // seconde moitié, l'avatar resterait servi à qui a noté son chemin.
        webTestClient.get()
            .uri(avatarUrl)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .jsonPath("$.code").isEqualTo("MEDIA_FILE_NOT_FOUND");
    }

    /** Dépose une image et rend son URL {@code /api/media/files/...}. */
    private String uploadImage(String token) throws IOException {
        MediaUploadResponse reponse = webTestClient.post()
            .uri("/api/media/upload/image")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(pngUploadBody().build()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(MediaUploadResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(reponse).isNotNull();
        assertThat(reponse.url()).startsWith("/api/media/files/");
        return reponse.url();
    }

    private MultipartBodyBuilder pngUploadBody() throws IOException {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(validPngBytes()) {
            @Override
            public String getFilename() {
                return "image.png";
            }
        }).contentType(MediaType.IMAGE_PNG);
        return builder;
    }

    private byte[] validPngBytes() throws IOException {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private String registerAndLogin(String email) {
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!", email.split("@")[0]))
            .exchange()
            .expectStatus().isCreated();

        AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        assertThat(authResponse.accessToken()).isNotBlank();
        return authResponse.accessToken();
    }
}
