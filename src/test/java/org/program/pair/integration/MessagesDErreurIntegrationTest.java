package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.dto.UpsertUserActivityRequest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.shared.dto.ErrorResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce qu'un corps d'erreur dit, et surtout ce qu'il ne dit plus (P-BA-10).
 *
 * <p>Trois choses partaient au client et n'avaient rien à y faire : le message
 * d'une {@code IllegalStateException} imprévue, le type MIME que Tika avait
 * reniflé dans un fichier refusé, et la valeur d'un paramètre mal typé. La
 * première est éprouvée sans base par
 * {@code GlobalExceptionHandlerTest} — c'est une question de résolution de
 * gestionnaire, pas de parcours. Les deux autres se voient d'ici, sur la vraie
 * chaîne de filtres et avec les vrais bundles.
 *
 * <p>Et son revers, qui compte autant : les refus qui <b>doivent</b> rester des
 * {@code 409} le restent, avec un code nommé et un message dans la langue
 * demandée. Le retrait du gestionnaire {@code IllegalStateException} est la
 * seule opération de la fiche qui pouvait les emporter sans bruit.
 */
class MessagesDErreurIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;

    /**
     * Le doublon d'activité au profil : {@code 409}, un code nommé, et de
     * l'allemand.
     *
     * <p>Ce refus était une {@code IllegalStateException}. Il rendait déjà
     * {@code 409}, mais avec le code générique {@code CONFLICT} — que l'app ne
     * traduit pas — et le message français du service, quelle que soit la langue
     * demandée. Le code est ce sur quoi le client branche sa logique : celui-ci
     * lui dit de stabiliser l'affichage sur « ajoutée » plutôt que d'ouvrir un
     * bandeau d'erreur, puisque l'état voulu est atteint.
     */
    @Test
    void ajouterDeuxFoisLaMemeActivite_doitRendre409NommeEtTraduit() {
        String token = inscrire("err-activite");
        UUID activiteId = uneActiviteDuReferentiel();
        UpsertUserActivityRequest corps =
            new UpsertUserActivityRequest(activiteId, null, null, null, null);

        webTestClient.post().uri("/api/users/me/activities")
            .headers(h -> {
                h.setBearerAuth(token);
                h.set(HttpHeaders.ACCEPT_LANGUAGE, "de");
            })
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(corps)
            .exchange()
            .expectStatus().isCreated();

        ErrorResponse erreur = webTestClient.post().uri("/api/users/me/activities")
            .headers(h -> {
                h.setBearerAuth(token);
                h.set(HttpHeaders.ACCEPT_LANGUAGE, "de");
            })
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(corps)
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(erreur).isNotNull();
        assertThat(erreur.code()).isEqualTo("USER_ACTIVITY_ALREADY_ADDED");
        // Le texte exact appartient au bundle et peut être reformulé ; ce qui est
        // tenu ici, c'est qu'il est bien allemand et non le littéral du service.
        assertThat(erreur.message())
            .as("le client demande de l'allemand : le message du service ne doit plus passer")
            .contains("Aktivität")
            .doesNotContain("activité");
    }

    /**
     * Un PDF déposé comme avatar : {@code 400}, et rien de ce que le serveur a
     * compris du fichier.
     *
     * <p>Le refus disait « … Detected: application/pdf ». Le type n'est pas celui
     * que le client déclare mais celui que Tika renifle dans les octets : le dire
     * apprend à qui téléverse ce que le serveur voit dans son fichier, et fait
     * de cette route un service de détection de type. Il reste au journal.
     */
    @Test
    void unTypeDeFichierRefuse_neDoitPasRenvoyerLeTypeDetecte() {
        String token = inscrire("err-avatar");

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(pdfMinimal()) {
            @Override
            public String getFilename() {
                return "releve-bancaire.pdf";
            }
        }).contentType(MediaType.APPLICATION_PDF);

        ErrorResponse erreur = webTestClient.post().uri("/api/users/me/avatar")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(builder.build()))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(erreur).isNotNull();
        assertThat(erreur.code()).isEqualTo("MEDIA_TYPE_NOT_ALLOWED");
        assertThat(erreur.message())
            .doesNotContain("Detected")
            .doesNotContain("application/pdf")
            .doesNotContain("pdf");
    }

    /**
     * Non-régression, et non correctif : le constat de P-BS-17 sur ce point est
     * <b>infirmé</b>. Le contrôleur lève bien
     * {@code ResourceNotFoundException("Fichier introuvable : " + nom)}, mais le
     * code {@code MEDIA_FILE_NOT_FOUND} a sa clé, donc le gestionnaire remplace
     * ce message par le texte traduit et le nom ne part qu'au journal. Ce test
     * existe pour que cela reste vrai : il suffirait de retirer la clé
     * {@code error.MEDIA_FILE_NOT_FOUND} pour que le nom du fichier reparte au
     * client, et rien d'autre ne le dirait.
     */
    @Test
    void unFichierIntrouvable_neDoitPasRenvoyerSonNomAuClient() {
        String token = inscrire("err-fichier");
        String nom = "user_avatar/" + UUID.randomUUID() + "-releve-bancaire.png";

        ErrorResponse erreur = webTestClient.get().uri("/api/media/files/" + nom)
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isNotFound()
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(erreur).isNotNull();
        assertThat(erreur.code()).isEqualTo("MEDIA_FILE_NOT_FOUND");
        assertThat(erreur.message()).doesNotContain("releve-bancaire");
    }

    /**
     * Un identifiant mal typé : {@code 400}, le nom du paramètre, pas sa valeur.
     *
     * <p>Le doublon volontaire avec {@code GlobalExceptionHandlerTest} porte sur
     * un point que le montage unitaire ne peut pas tenir : là-bas le nom du
     * paramètre est écrit en clair dans {@code @PathVariable("id")}, ici il vient
     * de la signature réelle du contrôleur, donc de la présence de
     * {@code -parameters} à la compilation. Sans ce drapeau, le message dirait
     * « Paramètre 'arg0' invalide ».
     */
    @Test
    void unIdentifiantMalType_neDoitPasRenvoyerLaValeurRecue() {
        String token = inscrire("err-parametre");

        ErrorResponse erreur = webTestClient.get().uri("/api/users/pas-un-uuid")
            .headers(h -> h.setBearerAuth(token))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(erreur).isNotNull();
        assertThat(erreur.code()).isEqualTo("INVALID_PARAMETER");
        assertThat(erreur.message())
            .contains("id")
            .as("la valeur reçue ne doit pas être reflétée : un lien fabriqué par un "
                + "tiers y mettrait ce qu'il veut")
            .doesNotContain("pas-un-uuid");
    }

    // — helpers —

    /**
     * Les octets d'un PDF, réduits à ce que Tika regarde : la signature
     * {@code %PDF-}. Un fichier complet ne changerait rien au type détecté, et
     * ce test n'a pas besoin qu'il s'ouvre.
     */
    private static byte[] pdfMinimal() {
        return ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< >>\n%%EOF\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    private UUID uneActiviteDuReferentiel() {
        Activity activite = activityRepository.findAll().stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Aucune activité en base : les migrations de semis n'ont pas tourné."));
        return activite.getId();
    }

    private String inscrire(String prefixe) {
        AuthResponse auth = webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(uniqueEmail(prefixe), "Password123!", "Lecteur d'erreurs"))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return auth.accessToken();
    }
}
