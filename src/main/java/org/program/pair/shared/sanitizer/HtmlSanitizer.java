package org.program.pair.shared.sanitizer;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Neutralise le texte saisi par un utilisateur avant qu'il n'entre en base.
 *
 * <p>Les vingt et un champs qui passent par ici — bio, nom affiché, message,
 * titre et description de programme, nom de lieu, ville, mot d'accueil, note
 * d'hôte, objectifs, prérequis — sont du <b>texte</b>. Aucun n'accepte de mise
 * en forme, aucun n'est censé contenir de balise.
 *
 * <p><b>Ce qui était fait avant, et pourquoi ça ne suffisait pas.</b> La version
 * précédente retirait les balises avec une expression régulière
 * ({@code <[^>]*>}), ce qui ôte bien {@code <script>} et {@code </script>} mais
 * laisse en place ce qu'il y avait entre les deux :
 * {@code <script>alert('hack')</script>Salut !} devenait
 * {@code alert('hack')Salut !}. Rendu comme du texte, c'est inoffensif ; recopié
 * un jour dans un contexte qui interprète, beaucoup moins. Un analyseur HTML
 * réel, lui, sait que le contenu d'un {@code <script>} n'est pas du texte et le
 * jette avec l'élément. La dépendance OWASP était déjà déclarée dans le
 * {@code pom.xml}, et un {@code TODO Phase 2} demandait précisément de s'en
 * servir ; c'est fait.
 *
 * <p><b>La sortie est du texte, pas du HTML.</b> Ces champs ne sont jamais
 * rendus tels quels dans une page : l'application mobile les affiche dans des
 * widgets de texte, et les pages web du serveur passent par Thymeleaf, qui
 * échappe. Échapper ici <i>aussi</i> reviendrait à échapper deux fois — un lieu
 * nommé « Parc de l'Orangerie » s'affichait littéralement
 * {@code Parc de l&amp;#39;Orangerie} sur la page de partage de sécurité. La
 * bibliothèque produit du HTML échappé, on le ramène donc en texte avant de le
 * stocker.
 *
 * <p>C'est la bonne répartition : le sanitiseur retire ce qui est dangereux, et
 * chaque rendu échappe pour son propre contexte. Un seul échappement, posé au
 * plus près de l'affichage, par celui qui sait dans quoi il écrit.
 *
 * <p><b>Les schémas d'URI dangereux sont neutralisés à part</b>, parce qu'aucun
 * analyseur HTML ne peut s'en charger : {@code javascript:alert(1)} saisi dans
 * une bio ne comporte aucune balise, c'est du texte ordinaire, et il ressort
 * donc intact d'un sanitiseur. Il ne devient nuisible que chez un client qui
 * transforme automatiquement en lien ce qui ressemble à une URL — ce que font
 * beaucoup d'interfaces. On casse le schéma en lui retirant son deux-points :
 * privé de sa ponctuation, il ne peut plus désigner de protocole, et la phrase
 * d'un développeur qui mentionnerait {@code javascript} reste lisible.
 */
@Component
public class HtmlSanitizer {

    /**
     * N'autorise aucun élément, aucun attribut. Le contenu des éléments dont le
     * corps n'est pas du texte — {@code <script>}, {@code <style>} — part avec
     * eux, ce qui est toute la différence avec un retrait de balises.
     */
    private static final PolicyFactory TEXT_ONLY = new HtmlPolicyBuilder().toFactory();

    /**
     * Schémas d'URI capables de porter du code. Les caractères invisibles admis
     * avant le deux-points couvrent les contournements classiques du type
     * {@code java&#9;script:}, que le décodage d'entités vient de rendre
     * littéraux.
     */
    private static final Pattern DANGEROUS_SCHEME = Pattern.compile(
        "(?i)\\b(javascript|vbscript|data)[\\s\\u0000-\\u001F]*:");

    public String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String sanitized = TEXT_ONLY.sanitize(input);

        // Le deux-points seul est retiré : le mot reste, le protocole meurt.
        sanitized = DANGEROUS_SCHEME.matcher(sanitized).replaceAll("$1");

        return unescape(sanitized).trim();
    }

    /**
     * Ramène en texte ce que la bibliothèque a rendu en HTML.
     *
     * <p>{@code &amp;} est décodé <b>en dernier</b>, et ce n'est pas un détail :
     * le faire en premier transformerait {@code &amp;lt;} en {@code &lt;}, que
     * la passe suivante changerait en {@code <}. Une chaîne écrite pour être lue
     * deviendrait ainsi du balisage — précisément ce que la sanitisation venait
     * d'empêcher.
     */
    private static String unescape(String html) {
        return html
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&amp;", "&");
    }
}
