package org.program.pair.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ni série, ni décompte de personnes, sur les trois schémas qui ont porté l'un ou
 * l'autre — demande mobile du 14/09 (badges, BIS), décision de l'utilisateur.
 *
 * <p>{@code currentStreakWeeks} et {@code practitionersNearby} étaient servis sans
 * être lus. Un compte servi sans être affiché finit par être rebranché : ce test
 * tient la porte fermée au contrat, comme {@code chk_badges_ni_serie_ni_note} le
 * fait en base pour les badges.
 */
class DoctrineContratSansDecompteTest extends AbstractIntegrationTest {

    private static final List<String> SCHEMAS =
        List.of("PracticeStatsDto", "SuggestedActivityDto", "PractisedNearbyDto");

    @Test
    void lesChampsRetires_nApparaissentPlusNullePart() {
        String contrat = contrat().toString();

        assertThat(contrat).doesNotContain("currentStreakWeeks").doesNotContain("practitionersNearby");
    }

    /**
     * Le module {@code /api/progressions} est retiré (demande mobile badges TER,
     * 14/09) : il servait les dernières séries du contrat (StreakDto). Aucune de ses
     * routes, et aucune propriété {@code streak}, {@code longest} ni
     * {@code activeDates}, ne doit y revenir.
     */
    @Test
    void lesProgressions_etLeursSeries_ontQuitteLeContrat() {
        JsonNode doc = contrat();
        List<String> chemins = new ArrayList<>();
        doc.at("/paths").fieldNames().forEachRemaining(chemins::add);

        assertThat(chemins).noneMatch(c -> c.startsWith("/api/progressions"));
        assertThat(doc.at("/components/schemas/StreakDto").isMissingNode()).isTrue();
        assertThat(doc.at("/components/schemas/ProgressionStatsDto").isMissingNode()).isTrue();
        String texte = doc.at("/components/schemas").toString();
        assertThat(texte).doesNotContain("\"longestStreak\"").doesNotContain("\"activeDates\"")
            .doesNotContain("\"currentStreak\"");
    }

    @Test
    void aucuneProprieteNEvoqueUneSerieOuUnDecompteDePersonnes() {
        JsonNode schemas = contrat().at("/components/schemas");

        for (String schema : SCHEMAS) {
            JsonNode proprietes = schemas.at("/" + schema + "/properties");
            assertThat(proprietes.isMissingNode()).as("%s au contrat", schema).isFalse();

            List<String> noms = new ArrayList<>();
            proprietes.fieldNames().forEachRemaining(n -> noms.add(n.toLowerCase(Locale.ROOT)));
            assertThat(noms)
                .as("propriétés de %s", schema)
                .noneMatch(n -> n.contains("streak") || n.contains("practitioner")
                    || n.contains("people") || n.contains("persons") || n.contains("users"));
        }
    }

    private JsonNode contrat() {
        String corps = webTestClient.get().uri("/v3/api-docs")
            .exchange().expectStatus().isOk()
            .expectBody(String.class).returnResult().getResponseBody();
        try {
            return objectMapper.readTree(corps);
        } catch (Exception e) {
            throw new AssertionError("contrat illisible", e);
        }
    }
}
