package org.program.pair.domain.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Year;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'enveloppe de marque des e-mails.
 *
 * <p><b>Ce qui partait avant.</b> Six gabarits nus — un {@code <h2>}, deux
 * {@code <p>}, un bouton {@code #4F46E5} qui n'est aucune couleur du produit —
 * sans nom, sans logo, sans copyright. Sur une demande de contact d'urgence ou
 * une alerte de non-retour, l'absence de marque est le pire des défauts
 * d'habillage : c'est celui qui fait classer le message en indésirable, ou pire,
 * ne pas le croire.
 *
 * <p><b>Ce que ce fichier protège.</b> Pas l'esthétique — on ne teste pas un
 * dégradé — mais les quatre propriétés qui font qu'un courrier arrive lisible
 * partout, et qu'aucun ne sort nu :
 *
 * <ol>
 *   <li>la marque est là : logo, nom, copyright de l'année en cours ;</li>
 *   <li>la mise en page tient chez Outlook — tableaux, styles en ligne, et le
 *       fond du ruban déclaré <b>deux fois</b>, plein puis dégradé ;</li>
 *   <li>envelopper est idempotent : un producteur qui choisit son accent
 *       enveloppe lui-même, et le filet de la porte de sortie le laisse
 *       passer ;</li>
 *   <li>le corps arrive intact — l'enveloppe n'échappe rien et ne réécrit rien,
 *       c'est au producteur de l'avoir fait.</li>
 * </ol>
 */
class GabaritEmailTest {

    private GabaritEmail gabarit;

    @BeforeEach
    void setUp() {
        gabarit = new GabaritEmail();
        ReflectionTestUtils.setField(gabarit, "publicBaseUrl", "https://lien.meetdo.fun");
    }

    @Test
    void toutCourrier_doitPorterLeLogoLeNomEtLeCopyright() {
        String html = gabarit.envelopper("<p>Bonjour</p>");

        // Le logo est une image hébergée, et non un SVG en ligne : presque aucun
        // client de messagerie ne rend le second.
        assertThat(html).contains("https://lien.meetdo.fun/brand/meetdo-symbol.png");
        // Le nom est du **texte** : il reste lisible quand les images sont
        // bloquées, ce qui est le réglage par défaut de plusieurs clients pour
        // un expéditeur inconnu.
        assertThat(html).contains("meet<span style=\"color:#6C63FF;\">Do</span>");
        assertThat(html).contains("© " + Year.now().getValue() + " meetDo");
        assertThat(html).contains("meetdo.fun");
    }

    @Test
    void laMiseEnPage_doitTenirChezOutlook() {
        String html = gabarit.envelopper("<p>Bonjour</p>", GabaritEmail.Accent.CORAL);

        // Tableaux et styles en ligne : le moteur de Word ignore flex, grid et
        // les variables CSS, et une mise en page en <div> y tombe en colonne
        // unique non centrée.
        assertThat(html).contains("role=\"presentation\"");
        assertThat(html).doesNotContain("display:flex");
        assertThat(html).doesNotContain("var(--");

        // Le fond du ruban est déclaré deux fois : Outlook garde la couleur
        // pleine et ignore le dégradé, tous les autres font l'inverse. Une seule
        // déclaration en dégradé donnerait un bandeau transparent.
        assertThat(html).contains("background:#E8484F;background:linear-gradient");

        // Pas d'inversion automatique des couleurs : un thème sombre pour e-mail
        // se négocie client par client, avec des résultats qui vont du correct à
        // l'illisible.
        assertThat(html).contains("name=\"color-scheme\" content=\"light\"");
    }

    @Test
    void envelopper_doitEtreIdempotent() {
        // La propriété qui permet à ResendEmailService d'habiller tout ce qui
        // sort sans habiller deux fois ce qui l'est déjà.
        String uneFois = gabarit.envelopper("<p>Bonjour</p>", GabaritEmail.Accent.MINT);
        String deuxFois = gabarit.envelopper(uneFois);

        assertThat(deuxFois).isEqualTo(uneFois);
        assertThat(deuxFois.split("<!DOCTYPE", -1)).hasSize(2);
        // L'accent choisi par le producteur survit au passage par le filet.
        assertThat(deuxFois).contains("background:#0FA37F;background:linear-gradient");
    }

    @Test
    void leCorps_doitArriverIntact() {
        // L'enveloppe n'échappe rien : c'est au producteur de l'avoir fait, et
        // deux échappements successifs afficheraient « &amp;lt; » au lecteur.
        String corps = "<p>Séance &amp; retour — 18h30</p>";
        assertThat(gabarit.envelopper(corps)).contains(corps);
    }

    @Test
    void unCorpsVide_neDoitPasProduireUneCarteVide() {
        // Un gabarit qui n'a rien à dire ne doit pas produire un courrier de
        // marque autour du néant : il vaut mieux que rien ne parte.
        assertThat(gabarit.envelopper(null)).isNull();
        assertThat(gabarit.envelopper("   ")).isEqualTo("   ");
    }

    @Test
    void leBouton_doitEtreUnTableau_etNonUnLienStyle() {
        String bouton = GabaritEmail.bouton("https://lien.meetdo.fun/v/abc", "Vérifier");

        // Outlook n'applique ni padding ni background à un lien en ligne : un
        // <a> stylé y devient un lien bleu souligné. Le tableau porte le fond.
        assertThat(bouton).contains("<table").contains("bgcolor=\"#6C63FF\"");
        assertThat(bouton).contains("href=\"https://lien.meetdo.fun/v/abc\"");
        assertThat(bouton).contains(">Vérifier</a>");
    }
}
