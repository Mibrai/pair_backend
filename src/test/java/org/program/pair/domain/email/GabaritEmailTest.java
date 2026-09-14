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

    // ── L'invitation de fin (14/09/2026) ────────────────────────────────────
    //
    // Demandée le 14/09/2026 : tout courrier meetDo se termine par une phrase
    // qui donne envie, un lien vers meetdo.fun et une invitation à installer
    // l'application. Un courrier est souvent le premier contact d'une personne
    // qui n'a pas l'app — un contact de confiance, un invité — et il repartait
    // sans lui dire ce qu'est meetDo ni où la trouver.

    @Test
    void toutCourrier_doitFinirParLInvitationATelecharger() {
        String html = gabarit.envelopper("<p>Bonjour</p>");

        assertThat(html).contains("href=\"https://meetdo.fun/#telecharger\"");
        assertThat(html).contains(">Télécharger meetDo</a>");
        assertThat(html).contains("href=\"https://meetdo.fun\"");
        // Après le corps et avant le copyright : c'est la signature, pas le message.
        int corps = html.indexOf("<p>Bonjour</p>");
        int invitation = html.indexOf("meetdo.fun/#telecharger");
        int copyright = html.indexOf("© ");
        assertThat(corps).isLessThan(invitation);
        assertThat(invitation).isLessThan(copyright);
    }

    @Test
    void lInvitation_doitGarderLeVioletDeLaMarque_quelQueSoitLAccent() {
        // Le corail est la couleur de l'alerte : un bouton « Télécharger » corail
        // sous une alerte de non-retour se lirait comme une action de l'alerte.
        String html = gabarit.envelopper("<p>Alerte</p>", GabaritEmail.Accent.CORAL);
        int invitation = html.indexOf("meetdo.fun/#telecharger");
        String avantLeLien = html.substring(0, invitation);
        assertThat(avantLeLien.substring(avantLeLien.lastIndexOf("bgcolor=")))
            .startsWith("bgcolor=\"#6C63FF\"");
    }

    @Test
    void lInvitation_doitParlerLaLangueDuDestinataire() {
        assertThat(gabarit.envelopper("<p>Hi</p>", GabaritEmail.Accent.VIOLET,
                java.util.Locale.ENGLISH))
            .contains(">Download meetDo</a>").contains("lang=\"en\"")
            .doesNotContain("Télécharger");
        assertThat(gabarit.envelopper("<p>Hallo</p>", GabaritEmail.Accent.VIOLET,
                java.util.Locale.GERMAN))
            .contains(">meetDo herunterladen</a>").contains("lang=\"de\"");
        // Une langue que meetDo ne parle pas retombe sur le français.
        assertThat(gabarit.envelopper("<p>Hola</p>", GabaritEmail.Accent.VIOLET,
                java.util.Locale.forLanguageTag("es")))
            .contains(">Télécharger meetDo</a>");
    }

    @Test
    void lInvitation_neDoitPasReduireMeetDoAuSport() {
        // meetDo met en relation pour faire quelque chose ensemble : l'accroche
        // cite le sport parmi d'autres, jamais seul.
        String html = gabarit.envelopper("<p>Bonjour</p>");
        assertThat(html).contains("soirée jeux").contains("atelier");
    }

    @Test
    void lInvitation_nApparaitQuUneFois_memeApresLeFilet() {
        String html = gabarit.envelopper(gabarit.envelopper("<p>Bonjour</p>"));
        assertThat(html.split("meetdo.fun/#telecharger", -1)).hasSize(2);
    }
}
