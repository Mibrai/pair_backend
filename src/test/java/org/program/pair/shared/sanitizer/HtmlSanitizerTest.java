package org.program.pair.shared.sanitizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le contrat du sanitiseur, champ par champ de ce qu'il doit garantir.
 *
 * <p>Ces cas n'existaient pas : la classe n'avait aucun test, et les deux seules
 * assertions qui la couvraient vivaient dans des tests d'intégration où un 409
 * de préparation les empêchait de s'exécuter. Elles sont reprises ici, au
 * niveau où elles se vérifient en une fraction de seconde.
 */
class HtmlSanitizerTest {

    private final HtmlSanitizer sanitizer = new HtmlSanitizer();

    // — ce qui doit disparaître —

    @Test
    void leContenuDUnScript_doitPartirAvecSaBalise() {
        // Le défaut de l'ancienne version : elle rendait « alert('hack')Salut ! »
        assertThat(sanitizer.sanitize("<script>alert('hack')</script>Salut !"))
            .isEqualTo("Salut !");
    }

    @Test
    void leContenuDUnStyle_doitPartirAussi() {
        assertThat(sanitizer.sanitize("<style>body{display:none}</style>Bonjour"))
            .isEqualTo("Bonjour");
    }

    @Test
    void unGestionnaireDEvenement_neDoitRienLaisser() {
        assertThat(sanitizer.sanitize("<img src=x onerror=alert(1)>")).isEmpty();
        assertThat(sanitizer.sanitize("<svg onload=alert(1)>")).isEmpty();
    }

    @Test
    void lesBalisesDeMiseEnForme_doiventPartir_maisPasLeurTexte() {
        // Aucun de ces champs n'accepte de mise en forme, mais ce que la personne
        // a écrit lui appartient : on retire le balisage, pas le propos.
        assertThat(sanitizer.sanitize("<b>Yoga</b> le samedi")).isEqualTo("Yoga le samedi");
    }

    // — les schémas d'URI, qu'aucun analyseur HTML ne voit —

    @Test
    void unSchemaDangereux_doitPerdreSonDeuxPoints() {
        // Sans balise, c'est du texte ordinaire : seul un client qui transforme
        // en lien ce qui ressemble à une URL le rendrait nuisible.
        assertThat(sanitizer.sanitize("javascript:alert(1)")).doesNotContain("javascript:");
        assertThat(sanitizer.sanitize("VBScript:msgbox(1)")).doesNotContain(":");
        assertThat(sanitizer.sanitize("data:text/html;base64,PHN2Zz4=")).doesNotContain("data:");
    }

    @Test
    void unSchemaCoupeParUnCaractereInvisible_doitEtreNeutraliseAussi() {
        assertThat(sanitizer.sanitize("javascript\t:alert(1)")).doesNotContain(":alert");
    }

    @Test
    void leMotJavascript_seul_doitResterLisible() {
        // Une phrase légitime ne doit pas être mutilée : on ôte la ponctuation
        // qui fait le protocole, pas le mot.
        assertThat(sanitizer.sanitize("Je code en javascript et en dart"))
            .isEqualTo("Je code en javascript et en dart");
    }

    // — ce qui doit être préservé —

    @Test
    void laSortie_doitEtreDuTexte_pasDuHtmlEchappe() {
        // Ces champs ne sont jamais rendus tels quels : l'application les affiche
        // dans des widgets de texte, et les pages du serveur passent par
        // Thymeleaf, qui échappe. Échapper ici aussi ferait afficher les entités
        // en clair — « Parc de l&#39;Orangerie » sur la page de sécurité.
        assertThat(sanitizer.sanitize("3 < 5")).isEqualTo("3 < 5");
        assertThat(sanitizer.sanitize("L'atelier \"du soir\""))
            .isEqualTo("L'atelier \"du soir\"");
    }

    @Test
    void uneEsperluetteEchappee_neDoitPasDevenirDuBalisage() {
        // Le piège du décodage : traiter &amp; en premier transformerait
        // « &amp;lt;script&amp;gt; » en « <script> ». L'ordre de la passe compte.
        assertThat(sanitizer.sanitize("&amp;lt;script&amp;gt;"))
            .isEqualTo("&lt;script&gt;");
    }

    @Test
    void unTexteOrdinaire_doitTraverserIntact() {
        assertThat(sanitizer.sanitize("Cours de yoga à Strasbourg, niveau débutant"))
            .isEqualTo("Cours de yoga à Strasbourg, niveau débutant");
        assertThat(sanitizer.sanitize("Rendez-vous à 9 h — pensez au tapis !"))
            .isEqualTo("Rendez-vous à 9 h — pensez au tapis !");
    }

    @Test
    void leNullEtLeVide_doiventTraverserSansExploser() {
        assertThat(sanitizer.sanitize(null)).isNull();
        assertThat(sanitizer.sanitize("")).isEmpty();
        assertThat(sanitizer.sanitize("   ")).isEqualTo("   ");
    }
}
