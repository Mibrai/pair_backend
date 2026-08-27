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
