package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les pages publiques portent la marque meetDo — toutes, et sans exception.
 *
 * <p><b>Ce qu'elles étaient.</b> Huit variations d'un même bloc gris :
 * {@code #f5f6f7} en fond, une carte blanche, un bouton {@code #14607f}
 * bleu-canard qui n'est aucune couleur du produit, et surtout aucun logo, aucun
 * nom, aucune mention de qui parle. Le destinataire d'un lien de sécurité — un
 * proche, sans compte, qui vient de recevoir « voici où je vais » — ouvrait une
 * page qui pouvait être de n'importe qui.
 *
 * <p><b>Pourquoi un test plutôt qu'une relecture.</b> Une page publique ne se
 * regarde jamais : elle est vue par des gens qui ne nous écriront pas, dans des
 * navigateurs intégrés que nous n'ouvrons pas. Une neuvième page ajoutée demain
 * sans le gabarit ne se remarquerait nulle part — sauf ici.
 *
 * <p>Les six adresses balayées sont les pages d'échec, choisies parce qu'elles
 * s'obtiennent <b>sans aucune mise en place</b> : un jeton inventé suffit. Les
 * pages nominales ont leurs propres suites, où le contenu compte davantage que
 * l'habillage.
 */
class MarquePagesPubliquesIntegrationTest extends AbstractIntegrationTest {

    /** Une adresse par gabarit d'échec, et le nom du gabarit qu'elle rend. */
    private static final Map<String, String> PAGES = new LinkedHashMap<>() {{
        put("/public/safety/jetonQuiNExistePas", "safety-share-expired");
        put("/s/jetonQuiNExistePas", "public-slot-unavailable");
        put("/p/jetonQuiNExistePas", "public-program (indisponible)");
        put("/public/watch/jetonQuiNExistePas", "watch-status-expired");
        put("/public/guardian-consent/jetonQuiNExistePas", "guardian-consent-expired");
        put("/v/jetonQuiNExistePas", "verify-email");
    }};

    @Test
    void chaquePagePublique_doitPorterLeLogoLeNomEtLeCopyright() {
        int annee = Year.now().getValue();

        PAGES.forEach((uri, gabarit) -> {
            String html = body(uri);

            assertThat(html)
                .as("le nom, sur %s (%s)", uri, gabarit)
                // Le « Do » est dans son propre <b> : c'est le mot-symbole du
                // logo, pas une chaîne « meetDo » qui traînerait dans un titre.
                .contains("<span class=\"marque-nom\">meet<b>Do</b></span>");

            assertThat(html)
                .as("le symbole, sur %s (%s)", uri, gabarit)
                // SVG en ligne : une page de confiance ne doit pas dépendre
                // d'une seconde requête pour être reconnaissable.
                .contains("aria-label=\"meetDo\"")
                .contains("<svg");

            assertThat(html)
                .as("le copyright, sur %s (%s)", uri, gabarit)
                // Calculé, jamais figé : un « © 2026 » écrit en dur dans huit
                // gabarits vieillit en silence.
                .contains("© " + annee + " meetDo");
        });
    }

    @Test
    void chaquePagePublique_doitEtreAuxCouleursAurora() {
        PAGES.forEach((uri, gabarit) -> {
            String html = body(uri);

            assertThat(html)
                .as("le violet Aurora, sur %s (%s)", uri, gabarit)
                .contains("--violet: #6C63FF");

            assertThat(html)
                .as("les deux thèmes, sur %s (%s)", uri, gabarit)
                // Clair et sombre : ces pages s'ouvrent le soir, sur un
                // téléphone en thème sombre, dans le navigateur d'une
                // messagerie. Une page qui reste blanche à 23 h éblouit celui
                // qu'on voulait rassurer.
                .contains("@media (prefers-color-scheme: dark)");

            assertThat(html)
                .as("plus une couleur de l'ancien habillage, sur %s (%s)", uri, gabarit)
                // Le bleu-canard et le gris d'avant. Ils n'appartiennent à
                // aucune palette du produit : les voir revenir signalerait un
                // gabarit écrit hors du socle.
                .doesNotContain("#14607f")
                .doesNotContain("#f5f6f7");
        });
    }

    @Test
    void lesPagesDeSecurite_neDoiventPasEtreIndexables() {
        // L'habillage ne change rien à la règle : une page qui dit où quelqu'un
        // se trouve ne produit ni aperçu social, ni entrée d'index.
        assertThat(body("/public/safety/jetonQuiNExistePas"))
            .contains("noindex, nofollow")
            .doesNotContain("og:title");
        assertThat(body("/public/watch/jetonQuiNExistePas"))
            .contains("noindex, nofollow");
    }

    /**
     * La page, demandée comme un navigateur la demande.
     *
     * <p>L'en-tête {@code Accept} n'est pas décoratif : {@code /v/{token}}
     * négocie le contenu et rend du JSON à qui ne réclame pas de HTML — c'est
     * volontaire (voir {@code ReponseVerificationEmail}), et un test sans
     * en-tête vérifierait l'habillage d'une réponse qui n'en a pas.
     */
    private String body(String uri) {
        return new String(webTestClient.get().uri(uri)
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE)
            .exchange().expectBody().returnResult().getResponseBodyContent());
    }
}
