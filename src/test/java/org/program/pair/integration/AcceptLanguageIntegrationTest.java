package org.program.pair.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.search.dto.SearchRequest;
import org.program.pair.domain.search.dto.SearchResponse;
import org.program.pair.domain.search.embedding.LocalEmbeddingService;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Demande 3 (a, b) de docs/specs/PROMPT_BACKEND_EVOLUTIONS_2026-08.md, avec les
 * deux règles de repli confirmées par le client dans
 * REPONSE_CLIENT_EVOLUTIONS_2026-08.md : langue non supportée → en, en-tête
 * absent → fr.
 *
 * <p>Un test par critère d'acceptation. La requête « je m'ennuie » déclenche la
 * clarification par le chemin déterministe de RuleBasedIntentExtractor (phrase
 * vague, aucune activité reconnue), donc sans dépendre du modèle d'embeddings.
 *
 * <p>Un seul compte pour toute la classe : l'inscription est plafonnée à
 * 5/heure/IP et le budget est partagé entre classes de test.
 */
class AcceptLanguageIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean LocalEmbeddingService embeddingService;

    private static final String EMAIL = "accept-language@pair.app";
    private static boolean accountCreated = false;
    private static String token;

    @BeforeEach
    void setUp() {
        if (!accountCreated) {
            token = registerAndLogin(EMAIL);
            accountCreated = true;
        }
        when(embeddingService.generateEmbedding(any())).thenReturn(new float[384]);
    }

    // — clarification —

    @Test
    void clarification_avecAcceptLanguageDe_doitRepondreEnAllemand() {
        SearchResponse response = search("je m'ennuie", "de");

        assertThat(response.type()).isEqualTo("clarification");
        assertThat(response.clarificationQuestion())
            .isEqualTo("Welche Aktivität würde dir heute gefallen?");
    }

    @Test
    void clarification_avecAcceptLanguageEn_doitRepondreEnAnglais() {
        assertThat(search("je m'ennuie", "en").clarificationQuestion())
            .isEqualTo("What kind of activity would you enjoy today?");
    }

    @Test
    void clarification_sansEnTete_doitResterEnFrancais() {
        assertThat(search("je m'ennuie", null).clarificationQuestion())
            .isEqualTo("Quel type d'activité te ferait plaisir aujourd'hui ?");
    }

    @Test
    void clarification_langueNonSupportee_doitRetomberSurLAnglais() {
        // Règle explicite du client : « it » est un appareil réel dont
        // l'utilisateur ne lit pas le français — l'anglais est le meilleur repli.
        assertThat(search("je m'ennuie", "it").clarificationQuestion())
            .isEqualTo("What kind of activity would you enjoy today?");
    }

    @Test
    void clarification_enTeteQualifie_doitRetenirLaPremiereLangueSupportee() {
        assertThat(search("je m'ennuie", "it-IT, de;q=0.9, en;q=0.8").clarificationQuestion())
            .isEqualTo("Welche Aktivität würde dir heute gefallen?");
    }

    @Test
    void clarification_sansEnTete_doitGarderLHeuristiqueParMotsCles() {
        // Non-régression : avant Accept-Language, la langue était devinée à
        // partir des mots de la requête. Les binaires déployés ne posent pas
        // l'en-tête ; basculer ce cas sur le français ferait régresser des
        // germanophones qui reçoivent aujourd'hui leur langue.
        assertThat(search("ich will etwas tun", null).clarificationQuestion())
            .isEqualTo("Welche Aktivität würde dir heute gefallen?");
    }

    @Test
    void clarification_lEnTeteDoitPrimerSurLesMotsDeLaRequete() {
        assertThat(search("ich will etwas tun", "en").clarificationQuestion())
            .isEqualTo("What kind of activity would you enjoy today?");
    }

    // — actions d'état vide —

    @Test
    void emptyStateActions_doiventSuivreLaMemeLangueQueLaClarification() {
        SearchResponse response = search("kitesurf à Oulan-Bator", "de");

        assertThat(response.type()).isEqualTo("empty");
        assertThat(response.emptyStateActions()).isNotEmpty();
        assertThat(response.emptyStateActions())
            .extracting(a -> a.label())
            .allSatisfy(label -> assertThat(label).isNotBlank());
        assertThat(response.emptyStateActions().stream().map(a -> a.label()))
            .as("aucun libellé ne doit rester en français quand l'allemand est demandé")
            .noneMatch(label -> label.contains("Créer votre propre")
                || label.contains("Élargir la zone"));
    }

    @Test
    void emptyStateActions_sansEnTete_doiventResterEnFrancais() {
        SearchResponse response = search("kitesurf à Oulan-Bator", null);

        assertThat(response.type()).isEqualTo("empty");
        assertThat(response.emptyStateActions().stream().map(a -> a.label()))
            .anyMatch(label -> label.contains("Créer votre propre")
                || label.contains("Élargir la zone")
                || label.contains("Être le premier"));
    }

    @Test
    void lesTypesDActionNeDoiventJamaisEtreTraduits() {
        SearchResponse response = search("kitesurf à Oulan-Bator", "de");

        // Le client parse ces valeurs (EmptyStateActionType) : elles restent en
        // anglais SCREAMING_SNAKE_CASE quelle que soit la langue.
        assertThat(response.emptyStateActions())
            .extracting(a -> a.type())
            .allSatisfy(type -> assertThat(type)
                .isIn("EXPAND_RADIUS", "CREATE_SLOT", "SET_ALERT", "SIMILAR_ACTIVITY"));
    }

    // — messages d'erreur —

    @Test
    void messageDErreur_doitSuivreLaLangue_maisPasLeCode() {
        UUID inexistant = UUID.randomUUID();

        String messageDe = deleteRecent(inexistant, "de");
        String messageFr = deleteRecent(inexistant, null);
        String messageEn = deleteRecent(inexistant, "en");

        assertThat(messageDe).isEqualTo("Letzte Suche nicht gefunden.");
        assertThat(messageFr).isEqualTo("Recherche récente introuvable.");
        assertThat(messageEn).isEqualTo("Recent search not found.");
    }

    @Test
    void leCodeDErreur_doitEtreIdentiqueDansToutesLesLangues() {
        UUID inexistant = UUID.randomUUID();

        for (String language : new String[]{"fr", "en", "de", "it", null}) {
            webTestClient.delete()
                .uri("/api/search/recent/{id}", inexistant)
                .headers(h -> {
                    h.setBearerAuth(token);
                    if (language != null) {
                        h.set("Accept-Language", language);
                    }
                })
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SEARCH_HISTORY_ENTRY_NOT_FOUND");
        }
    }

    @Test
    void unRefusSansCodeNomme_doitGarderSonMessageDorigine() {
        // Non-régression : la traduction ne couvre que les refus explicitement
        // nommés. Une erreur de liaison de paramètre sort en VALIDATION_ERROR,
        // qui n'a pas de clé error.* — son message reste celui d'avant, construit
        // à partir des champs en faute, même quand l'allemand est demandé.
        webTestClient.get()
            .uri("/api/map/activities?userLat=pasUnNombre&userLng=2.35")
            .headers(h -> {
                h.setBearerAuth(token);
                h.set("Accept-Language", "de");
            })
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
            .jsonPath("$.message").value(m -> assertThat((String) m).contains("userLat"));
    }

    // — helpers —

    private SearchResponse search(String query, String language) {
        return webTestClient.post()
            .uri("/api/search")
            .headers(h -> {
                h.setBearerAuth(token);
                if (language != null) {
                    h.set("Accept-Language", language);
                }
            })
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new SearchRequest(query, 48.8566, 2.3522, 5000))
            .exchange()
            .expectStatus().isOk()
            .expectBody(SearchResponse.class)
            .returnResult()
            .getResponseBody();
    }

    private String deleteRecent(UUID id, String language) {
        byte[] body = webTestClient.delete()
            .uri("/api/search/recent/{id}", id)
            .headers(h -> {
                h.setBearerAuth(token);
                if (language != null) {
                    h.set("Accept-Language", language);
                }
            })
            .exchange()
            .expectStatus().isNotFound()
            .expectBody()
            .returnResult()
            .getResponseBody();

        try {
            return objectMapper.readTree(body).get("message").asText();
        } catch (Exception e) {
            throw new AssertionError("Corps d'erreur illisible", e);
        }
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
        return authResponse.accessToken();
    }
}
