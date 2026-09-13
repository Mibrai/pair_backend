package org.program.pair.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Couvre l'exigence contractuelle : les champs additifs organizerId
 * (MapActivityMarkerDto) et scheduledAt (NotificationDto) doivent apparaître
 * dans /v3/api-docs, généré automatiquement par springdoc à partir des DTO —
 * aucune spec statique à maintenir dans ce repo.
 */
class OpenApiContractIntegrationTest extends AbstractIntegrationTest {

    @Test
    void apiDocs_devraitExposerOrganizerIdSurMapActivityMarkerDto() throws Exception {
        JsonNode apiDocs = fetchApiDocs();

        JsonNode schema = apiDocs.path("components").path("schemas").path("MapActivityMarkerDto");
        assertThat(schema.isMissingNode()).isFalse();
        assertThat(schema.path("properties").has("organizerId")).isTrue();
    }

    @Test
    void apiDocs_devraitExposerScheduledAtSurNotificationDto() throws Exception {
        JsonNode apiDocs = fetchApiDocs();

        // GET /api/notifications doit référencer un PagedModel typé
        // (PagedModelNotificationDto), pas le PagedModel générique non typé —
        // sinon aucun schéma NotificationDto n'est jamais généré et scheduledAt
        // ne peut pas y apparaître.
        JsonNode responseSchema = apiDocs.path("paths").path("/api/notifications")
            .path("get").path("responses").path("200").path("content").path("*/*").path("schema");
        assertThat(responseSchema.path("$ref").asText()).isEqualTo("#/components/schemas/PagedModelNotificationDto");

        JsonNode schema = apiDocs.path("components").path("schemas").path("NotificationDto");
        assertThat(schema.isMissingNode()).isFalse();
        assertThat(schema.path("properties").has("scheduledAt")).isTrue();
    }

    /**
     * Le serveur rend {@code 201} sur la création d'un signalement ; le contrat
     * annonçait {@code 200}. L'app accepte les deux — tout {@code 2xx} est un
     * succès pour Dio — donc rien ne cassait et rien ne le signalait non plus.
     *
     * <p>La cause n'était pas un oubli de documentation mais la forme du
     * contrôleur : springdoc lit la signature de la méthode, jamais son corps,
     * et un {@code ResponseEntity.status(CREATED)} posé à l'exécution lui reste
     * invisible. Ce test verrouille le statut documenté, pas l'annotation qui
     * le produit.
     */
    @Test
    void apiDocs_devraitAnnoncer201SurLaCreationDunSignalement() throws Exception {
        JsonNode reponses = fetchApiDocs()
            .path("paths").path("/api/reports").path("post").path("responses");

        assertThat(reponses.has("201")).isTrue();
        assertThat(reponses.has("200")).isFalse();
    }

    /**
     * P-BA-12 — le contrat ne publie plus l'entité de signalement comme réponse.
     * La création référence {@code ReportDto}, et ni {@code Report} ni son nom
     * d'entité {@code ReportPhase3} n'apparaissent parmi les schémas.
     */
    @Test
    void apiDocs_neDoitPublierAucunSchemaDEntiteDeSignalement() throws Exception {
        JsonNode docs = fetchApiDocs();
        JsonNode schemas = docs.path("components").path("schemas");

        assertThat(schemas.has("Report")).isFalse();
        assertThat(schemas.has("ReportPhase3")).isFalse();
        assertThat(docs.path("paths").path("/api/reports").path("post").path("responses")
            .path("201").toString()).contains("#/components/schemas/ReportDto");
    }

    /**
     * P-BA-17 — les deux visibilités ont chacune leur schéma, et le bon champ.
     * Sous un même nom {@code VisibilityRequest}, la spec annonçait {@code visible}
     * pour la carte-souvenir, qui lit {@code visibility}.
     */
    @Test
    void apiDocs_laVisibiliteDUneCarteSouvenir_sePublieAvecLeChampVisibility() throws Exception {
        JsonNode docs = fetchApiDocs();

        assertThat(docs.path("paths").path("/api/slots/{scheduleId}/recap/visibility").path("patch")
            .path("requestBody").toString()).contains("#/components/schemas/RecapVisibilityRequest");
        assertThat(docs.path("components").path("schemas").path("RecapVisibilityRequest")
            .path("properties").has("visibility")).isTrue();
    }

    @Test
    void apiDocs_laVisibiliteDUneActivite_sePublieAvecLeChampVisible() throws Exception {
        JsonNode docs = fetchApiDocs();

        assertThat(docs.path("paths").path("/api/users/me/activities/{userActivityId}/visibility")
            .path("patch").path("requestBody").toString())
            .contains("#/components/schemas/ActivityVisibilityRequest");
        assertThat(docs.path("components").path("schemas").path("ActivityVisibilityRequest")
            .path("properties").has("visible")).isTrue();
        assertThat(docs.path("components").path("schemas").has("VisibilityRequest")).isFalse();
    }

    /**
     * P-BA-14 — chaque paramètre {@code size} du contrat annonce son maximum. Un
     * client qui lit la spec sait qu'au-delà, la page est ramenée — ou refusée.
     */
    @Test
    void apiDocs_chaqueParametreSize_annonceSonMaximum() throws Exception {
        JsonNode paths = fetchApiDocs().path("paths");
        java.util.List<String> sansMaximum = new java.util.ArrayList<>();
        int vus = 0;

        for (var route : (Iterable<java.util.Map.Entry<String, JsonNode>>) paths::fields) {
            for (var operation : (Iterable<java.util.Map.Entry<String, JsonNode>>) route.getValue()::fields) {
                for (JsonNode parametre : operation.getValue().path("parameters")) {
                    if ("size".equals(parametre.path("name").asText())
                            && "query".equals(parametre.path("in").asText())) {
                        vus++;
                        if (!parametre.path("schema").has("maximum")) {
                            sansMaximum.add(operation.getKey().toUpperCase() + " " + route.getKey());
                        }
                    }
                }
            }
        }

        assertThat(vus).as("le contrat porte bien des routes paginées").isGreaterThan(10);
        assertThat(sansMaximum).as("paramètres size sans maximum").isEmpty();
    }

    /** P-BS-03 — la déconnexion publie son corps : le jeton de rafraîchissement et celui de l'appareil. */
    @Test
    void apiDocs_laDeconnexion_publieLogoutRequest() throws Exception {
        JsonNode docs = fetchApiDocs();

        assertThat(docs.path("components").path("schemas").path("LogoutRequest").path("properties")
            .has("refreshToken")).isTrue();
        assertThat(docs.path("paths").path("/api/auth/logout").path("post").path("responses").has("204")).isTrue();
    }

    private JsonNode fetchApiDocs() throws Exception {
        byte[] raw = webTestClient.get()
            .uri("/v3/api-docs")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .returnResult().getResponseBody();
        return objectMapper.readTree(new String(raw, StandardCharsets.UTF_8));
    }
}
