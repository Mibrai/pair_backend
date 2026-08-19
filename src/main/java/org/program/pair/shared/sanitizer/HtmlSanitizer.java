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
 * <p><b>La forme du résultat ne change pas.</b> Comme avant, la sortie est du
 * texte dont les caractères significatifs en HTML restent échappés : un
 * utilisateur qui écrit {@code 3 < 5} voit toujours {@code 3 &lt; 5} stocké.
 * C'est ce que la base contient déjà pour l'existant, et le modifier changerait
 * l'affichage de contenus vieux de plusieurs mois. La bibliothèque échappe en
 * prime les guillemets et les apostrophes, que l'ancienne version décodait sans
 * jamais les ré-encoder — un titre contenant une apostrophe pouvait donc casser
 * l'attribut HTML qui l'accueillerait.
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

        return sanitized.trim();
    }
}
