package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le registre des incidents, et son unique pont vers la modération.
 *
 * <p>Le test qui porte la décision de séparation est
 * {@link #unIncidentTransit_neVaPasDansLaModeration()} : « perdu en chemin » et
 * « lieu mal éclairé » ne doivent jamais atterrir dans la file des signalements,
 * sinon la victime finit dans la colonne des signalés.
 */
class IncidentIntegrationTest extends AbstractIntegrationTest {

    @Test
    void unIncidentTransit_estEcrit_etVisibleDansMesIncidents() {
        Compte moi = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "TRANSIT", "note", "Je me suis perdue en chemin."))
            .exchange().expectStatus().isCreated()
            .expectBody().jsonPath("$.target").isEqualTo("TRANSIT");

        webTestClient.get().uri("/api/incidents/me")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].target").isEqualTo("TRANSIT");
    }

    @Test
    void unIncidentTransit_neVaPasDansLaModeration() {
        Compte moi = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "PLACE", "note", "Lieu mal éclairé, peu rassurant."))
            .exchange().expectStatus().isCreated();

        // Rien ne doit être apparu dans mes signalements : le registre est séparé.
        webTestClient.get().uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.page.totalElements").isEqualTo(0);
    }

    @Test
    void unIncidentPerson_basculeDansLaModeration() {
        Compte moi = compte();
        Compte autre = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "target", "PERSON",
                "targetUserId", autre.id().toString(),
                "reason", "HARASSMENT",
                "note", "Comportement inapproprié et insistant."))
            .exchange().expectStatus().isCreated();

        // L'incident est dans mon registre...
        webTestClient.get().uri("/api/incidents/me")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$[0].target").isEqualTo("PERSON");

        // ...et un signalement est bien parti en modération.
        webTestClient.get().uri("/api/reports/me")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.page.totalElements").isEqualTo(1);
    }

    @Test
    void unIncidentPersonSansCible_estRefuse() {
        Compte moi = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "PERSON", "note", "Quelqu'un, mais je ne dis pas qui."))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("INCIDENT_PERSON_TARGET_REQUIRED");
    }

    @Test
    void seSignalerSoiMemeEnPerson_estRefuse() {
        Compte moi = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "target", "PERSON",
                "targetUserId", moi.id().toString(),
                "note", "Description assez longue pour valider la règle."))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("BUSINESS_RULE_VIOLATION");
    }

    @Test
    void retirerUnIncidentDeMonJournal_leFaitDisparaitre() {
        Compte moi = compte();

        String incidentId = String.valueOf(webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "PLACE", "note", "Lieu peu rassurant."))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id"));

        webTestClient.delete().uri("/api/incidents/{id}", incidentId)
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isNoContent();

        webTestClient.get().uri("/api/incidents/me")
            .headers(h -> h.setBearerAuth(moi.token()))
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void retirerLincidentDunAutre_rend404() {
        Compte moi = compte();
        Compte autre = compte();

        String incidentId = String.valueOf(webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "TRANSIT", "note", "Perdue en chemin."))
            .exchange().expectStatus().isCreated()
            .expectBody(Map.class).returnResult().getResponseBody().get("id"));

        // Un incident qui n'est pas le sien est introuvable, pas interdit.
        webTestClient.delete().uri("/api/incidents/{id}", incidentId)
            .headers(h -> h.setBearerAuth(autre.token()))
            .exchange().expectStatus().isNotFound();
    }

    @Test
    void unIncidentPersonSansNote_estRefuse_avecLeCodeAligne() {
        Compte moi = compte();
        Compte autre = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "PERSON", "targetUserId", autre.id().toString()))
            .exchange().expectStatus().isEqualTo(422)
            .expectBody().jsonPath("$.code").isEqualTo("INCIDENT_NOTE_REQUIRED");
    }

    // ———————————————————— pièces jointes (P-BS-01 / P-BS-11) ————————————————————

    /**
     * Une pièce jointe doit avoir été déposée par l'auteur de l'incident.
     *
     * <p>Le chemin d'un fichier n'est pas un secret : il circule en clair dans
     * les DTO. Il suffisait donc de lire une réponse d'API pour joindre la photo
     * de quelqu'un d'autre à son propre signalement — et, une fois la lecture
     * restreinte au déposant (P-BS-11), pour fabriquer un signalement dont la
     * pièce jointe serait illisible par son propre auteur.
     */
    @Test
    void unePieceJointe_devraitEtreRefusee_quandUnAutreCompteLaDeposee() throws IOException {
        Compte auteur = compte();
        Compte tiers = compte();
        String fichierDuTiers = deposerImage(tiers.token());

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(auteur.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "PLACE", "note", "Lieu peu rassurant.",
                "attachmentUrl", fichierDuTiers))
            .exchange().expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("MEDIA_URL_INVALID");
    }

    /**
     * Le volet serveur de P-MS-01 : une URL externe est refusée.
     *
     * <p>Sans ce refus, l'URL était rangée telle quelle et l'application
     * chargeait ensuite l'image avec son client Dio <b>authentifié</b> : le
     * jeton de la personne partait vers l'hôte choisi par qui avait posté
     * l'URL. Le préfixe {@code /api/media/files/} qui apparaît dans l'adresse ne
     * change rien — c'est le début de la chaîne qui est vérifié, pas sa
     * présence quelque part.
     */
    @Test
    void unePieceJointe_devraitEtreRefusee_quandLurlEstExterne() {
        Compte moi = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "PLACE", "note", "Lieu peu rassurant.",
                "attachmentUrl", "https://evil.tld/api/media/files/program_image/x.jpg"))
            .exchange().expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("MEDIA_URL_INVALID");
    }

    /** Un chemin inventé n'est pas plus recevable qu'une URL externe. */
    @Test
    void unePieceJointe_devraitEtreRefusee_quandLeFichierNexistePas() {
        Compte moi = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "PLACE", "note", "Lieu peu rassurant.",
                "attachmentUrl", "/api/media/files/program_image/" + UUID.randomUUID() + ".jpg"))
            .exchange().expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("MEDIA_URL_INVALID");
    }

    /**
     * Le chemin nominal, et la restriction de lecture qui en découle.
     *
     * <p>C'est le rattachement qui marque le fichier comme pièce jointe : avant,
     * il était rangé dans {@code program_image/} et rigoureusement
     * indiscernable d'une couverture de programme. Le refus de lecture est un
     * <b>404</b> et non un 403 — une preuve de harcèlement ne doit pas se
     * révéler à qui devine son chemin.
     */
    @Test
    void unePieceJointe_neDevraitEtreLisible_queParSonAuteur() throws IOException {
        Compte auteur = compte();
        Compte tiers = compte();
        String fichier = deposerImage(auteur.token());

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(auteur.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "PLACE", "note", "Lieu peu rassurant.",
                "attachmentUrl", fichier))
            .exchange().expectStatus().isCreated()
            .expectBody().jsonPath("$.attachmentUrl").isEqualTo(fichier);

        webTestClient.get().uri(fichier)
            .headers(h -> h.setBearerAuth(auteur.token()))
            .exchange().expectStatus().isOk();

        webTestClient.get().uri(fichier)
            .headers(h -> h.setBearerAuth(tiers.token()))
            .exchange().expectStatus().isNotFound()
            .expectBody().jsonPath("$.code").isEqualTo("MEDIA_FILE_NOT_FOUND");
    }

    /**
     * Non-régression : la très grande majorité des incidents n'a pas de pièce
     * jointe, et la validation ne doit pas rendre l'absence suspecte.
     */
    @Test
    void unIncidentSansPieceJointe_devraitResterAccepte() {
        Compte moi = compte();

        webTestClient.post().uri("/api/incidents")
            .headers(h -> h.setBearerAuth(moi.token()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("target", "TRANSIT", "note", "Perdue en chemin."))
            .exchange().expectStatus().isCreated()
            .expectBody().jsonPath("$.attachmentUrl").isEmpty();
    }

    // ------------------------------------------------------------------ outils

    private String deposerImage(String token) throws IOException {
        Map<?, ?> reponse = webTestClient.post().uri("/api/media/upload/image")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(corpsPng().build()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(reponse).isNotNull();
        String url = String.valueOf(reponse.get("url"));
        assertThat(url).startsWith("/api/media/files/");
        return url;
    }

    private MultipartBodyBuilder corpsPng() throws IOException {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(pngValide()) {
            @Override
            public String getFilename() {
                return "piece.png";
            }
        }).contentType(MediaType.IMAGE_PNG);
        return builder;
    }

    private byte[] pngValide() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private record Compte(UUID id, String token) {}

    private Compte compte() {
        String email = uniqueEmail("incident");
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Inc" + UUID.randomUUID().toString().substring(0, 8)))
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
