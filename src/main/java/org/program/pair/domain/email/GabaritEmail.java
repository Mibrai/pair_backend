package org.program.pair.domain.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Year;

/**
 * L'enveloppe de marque de tout e-mail sortant de meetDo.
 *
 * <p><b>Ce qui partait avant.</b> Six gabarits écrits à la main, chacun un
 * fragment nu : {@code <h2>} suivi de {@code <p>}, parfois un
 * {@code <div style="font-family:system-ui">}, un bouton {@code #4F46E5} qui
 * n'est aucune couleur du produit, et surtout <b>aucun nom, aucun logo, aucune
 * mention de qui écrit</b>. Le lecteur voyait un texte gris arrivé d'une
 * adresse qu'il ne connaissait pas — sur un message qui lui demande d'être le
 * contact d'urgence de quelqu'un, ou qui lui annonce que ce quelqu'un n'a pas
 * confirmé son retour, c'est le pire moment pour ne pas être reconnaissable.
 *
 * <p><b>Ce qui part maintenant.</b> Le même habillage que les pages publiques :
 * le symbole meetDo, le nom, la carte sur fond lavande, le ruban d'accent, et
 * le pied avec le copyright. Les jetons sont ceux d'Aurora, recopiés depuis
 * {@code app_colors.dart} le 12/09/2026 — c'est la seule copie, et elle est
 * ici.
 *
 * <h2>Pourquoi des tableaux et des styles en ligne</h2>
 * Ce n'est pas de la nostalgie : Outlook rend le HTML avec le moteur de Word,
 * qui ignore {@code flex}, {@code grid}, les variables CSS et une bonne part
 * des feuilles de style embarquées. Une mise en page en {@code <div>} y tombe
 * en colonne unique non centrée. Les tableaux imbriqués et les attributs
 * {@code style} tiennent partout, et c'est le seul critère qui vaille pour un
 * courrier qu'on n'a aucun moyen de réafficher.
 *
 * <p>De même le dégradé : {@code background} est écrit <b>deux fois</b>, une
 * couleur pleine puis le dégradé. Outlook garde la première et ignore la
 * seconde ; tous les autres font l'inverse. Une seule déclaration en dégradé
 * donnerait un bandeau transparent chez Outlook.
 *
 * <h2>Clair seulement, et c'est délibéré</h2>
 * {@code color-scheme: light} est annoncé pour que les clients qui inversent
 * automatiquement les couleurs (Gmail, Outlook.com) s'abstiennent. Un thème
 * sombre pour e-mail se négocie client par client, avec des résultats qui vont
 * du correct à l'illisible ; le blanc, lui, est le même partout. C'est
 * l'inverse du choix fait sur les pages web, où la préférence système est
 * fiable.
 *
 * <h2>Le filet, et pourquoi il est où il est</h2>
 * {@link ResendEmailService} enveloppe ce qui lui arrive nu. C'est la porte de
 * sortie unique de tout courrier meetDo : un gabarit ajouté demain sans y
 * penser sortira quand même habillé. {@link #estDejaEnveloppe} rend le geste
 * idempotent, pour que ceux qui choisissent leur accent puissent envelopper
 * eux-mêmes sans être enveloppés deux fois.
 */
@Component
public class GabaritEmail {

    /** L'accent du ruban et du bouton. Les mêmes cinq que dans l'application. */
    public enum Accent {
        /** Le défaut : tout ce qui n'est ni sécurité ni bonne nouvelle. */
        VIOLET("#6C63FF", "#6C63FF", "linear-gradient(120deg,#6C63FF,#9B5DE5 55%,#FF6B6B)"),
        /** Ce qui rassure : un retour confirmé, un consentement accepté. */
        MINT("#0FA37F", "#0FA37F", "linear-gradient(120deg,#22D3A7,#0FA37F)"),
        /** L'alerte, et elle seule. Jamais un refus, jamais une expiration. */
        CORAL("#E8484F", "#E8484F", "linear-gradient(120deg,#FF6B6B,#E8484F)"),
        /** L'information de suivi : une page de statut, un lien de veille. */
        SKY("#0B93D5", "#0B93D5", "linear-gradient(120deg,#38BDF8,#0B93D5)");

        private final String plein;
        private final String boutonPlein;
        private final String degrade;

        Accent(String plein, String boutonPlein, String degrade) {
            this.plein = plein;
            this.boutonPlein = boutonPlein;
            this.degrade = degrade;
        }
    }

    // ── Jetons Aurora (app_colors.dart, relevé le 12/09/2026) ───────────────
    private static final String CANVAS = "#F3F2FB";
    private static final String SURFACE = "#FFFFFF";
    private static final String BORDURE = "#E5E5EE";
    private static final String TEXTE = "#1A1A2E";
    private static final String TEXTE_2 = "#626274";
    private static final String TEXTE_3 = "#86869A";
    private static final String VIOLET = "#6C63FF";

    private static final String POLICE =
        "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif";

    @Value("${pair.public.base-url:https://lien.meetdo.fun}")
    private String publicBaseUrl;

    /**
     * Habille [corps] — un fragment HTML — de l'enveloppe de marque.
     *
     * <p>Rend [corps] tel quel s'il est déjà un document complet : voir
     * {@link #estDejaEnveloppe}.
     */
    public String envelopper(String corps) {
        return envelopper(corps, Accent.VIOLET);
    }

    public String envelopper(String corps, Accent accent) {
        if (corps == null || corps.isBlank() || estDejaEnveloppe(corps)) {
            return corps;
        }
        return """
            <!DOCTYPE html>
            <html lang="fr"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="color-scheme" content="light">
            <meta name="supported-color-schemes" content="light">
            </head>
            <body style="margin:0;padding:0;background:%1$s;">
            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background:%1$s;">
            <tr><td align="center" style="padding:26px 12px 36px;">
            <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:100%%;max-width:600px;">

            <!-- La marque. Le symbole est une image hébergée : un SVG en ligne,
                 qui convient aux pages web, n'est rendu par presque aucun client
                 de messagerie. Le nom est du texte — il reste lisible quand les
                 images sont bloquées, ce qui est le réglage par défaut de
                 plusieurs clients pour un expéditeur inconnu. -->
            <tr><td style="padding:0 4px 14px;">
              <!-- Deux cellules plutôt qu'une image et un span côte à côte :
                   `vertical-align:middle` sur une image en ligne aligne le
                   milieu de sa boîte sur la ligne de base du texte, ce qui la
                   pose visiblement trop haut. Deux cellules d'un même tableau
                   s'alignent, elles, sur leur milieu réel — et c'est en plus la
                   seule mise en page qu'Outlook respecte. -->
              <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
                <td width="40" style="width:40px;padding-right:9px;line-height:0;">
                  <img src="%3$s/brand/meetdo-symbol.png" width="40" height="40" alt="meetDo"
                       style="border:0;display:block;width:40px;height:40px;">
                </td>
                <td style="font-family:%2$s;font-size:20px;font-weight:800;letter-spacing:-.5px;color:%4$s;">meet<span style="color:%5$s;">Do</span></td>
              </tr></table>
            </td></tr>

            <tr><td style="background:%6$s;border:1px solid %7$s;border-radius:20px;overflow:hidden;">
              <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0">
                <!-- Le ruban d'accent. Deux déclarations de fond : la pleine pour
                     Outlook, le dégradé pour les autres. -->
                <tr><td height="5" style="height:5px;line-height:5px;font-size:0;background:%8$s;background:%9$s;">&nbsp;</td></tr>
                <tr><td style="padding:26px 24px;font-family:%2$s;font-size:15px;line-height:1.55;color:%4$s;">
            %10$s
                </td></tr>
              </table>
            </td></tr>

            <tr><td style="padding:18px 10px 0;text-align:center;font-family:%2$s;font-size:12px;line-height:1.6;color:%11$s;">
              <span style="color:%12$s;font-weight:600;">meetDo — on publie un créneau, quelqu'un le rejoint.</span><br>
              © %13$s meetDo · <a href="https://meetdo.fun" style="color:%12$s;">meetdo.fun</a>
            </td></tr>

            </table>
            </td></tr>
            </table>
            </body></html>
            """.formatted(
                CANVAS,                 // 1
                POLICE,                 // 2
                publicBaseUrl,          // 3
                TEXTE,                  // 4
                VIOLET,                 // 5
                SURFACE,                // 6
                BORDURE,                // 7
                accent.plein,           // 8
                accent.degrade,         // 9
                corps,                  // 10
                TEXTE_3,                // 11
                TEXTE_2,                // 12
                Year.now().getValue()); // 13
    }

    /**
     * Vrai quand [corps] est déjà un document complet — donc déjà habillé.
     *
     * <p>Le test porte sur la forme et non sur un marqueur maison : un gabarit
     * écrit ailleurs, un jour, avec son propre {@code <html>}, ne doit pas se
     * retrouver imbriqué dans le nôtre.
     */
    public static boolean estDejaEnveloppe(String corps) {
        String debut = corps.stripLeading().toLowerCase(java.util.Locale.ROOT);
        return debut.startsWith("<!doctype") || debut.startsWith("<html");
    }

    // ── Les morceaux que personne ne devrait réécrire ────────────────────────

    /**
     * Un bouton d'action, en tableau.
     *
     * <p>Un {@code <a>} stylé suffit partout sauf chez Outlook, qui n'applique
     * ni {@code padding} ni {@code background} à un lien en ligne : le bouton y
     * devient un lien bleu souligné. Le tableau porte le fond, le lien porte le
     * texte, et le résultat est le même partout.
     */
    public static String bouton(String url, String libelle) {
        return bouton(url, libelle, Accent.VIOLET);
    }

    public static String bouton(String url, String libelle, Accent accent) {
        return """
            <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:18px 0 4px;">
              <tr><td align="center" bgcolor="%s" style="border-radius:14px;background:%s;background:%s;">
                <a href="%s" style="display:inline-block;padding:13px 26px;font-family:%s;font-size:15px;font-weight:700;color:#ffffff;text-decoration:none;border-radius:14px;">%s</a>
              </td></tr>
            </table>
            """.formatted(accent.boutonPlein, accent.boutonPlein, accent.degrade,
                          url, POLICE, libelle);
    }

    /** Le titre d'un courrier. Un seul par message. */
    public static String titre(String texte) {
        return "<h1 style=\"margin:0 0 12px;font-family:" + POLICE
            + ";font-size:20px;font-weight:800;letter-spacing:-.2px;line-height:1.3;color:"
            + TEXTE + ";\">" + texte + "</h1>";
    }

    /** La ligne discrète de fin — expiration, désabonnement, rappel de règle. */
    public static String note(String texte) {
        return "<p style=\"margin:18px 0 0;font-size:13px;line-height:1.5;color:"
            + TEXTE_2 + ";\">" + texte + "</p>";
    }

    /**
     * Un encart teinté — ce qu'on veut faire lire même à celui qui parcourt.
     *
     * <p>La barre latérale est un {@code border-left} : les bordures partielles
     * sont l'une des rares choses que Word rend correctement.
     */
    public static String encart(String texte, Accent accent) {
        String fond = switch (accent) {
            case MINT -> "#E8F8F3";
            case CORAL -> "#FDECEC";
            case SKY -> "#E8F5FD";
            case VIOLET -> "#F0EEFF";
        };
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
            + " style=\"margin:16px 0 0;background:" + fond + ";border-left:3px solid "
            + accent.plein + ";border-radius:8px;\">"
            + "<tr><td style=\"padding:12px 14px;font-family:" + POLICE
            + ";font-size:14px;line-height:1.5;color:" + TEXTE_2 + ";\">" + texte
            + "</td></tr></table>";
    }
}
