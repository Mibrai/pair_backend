package org.program.pair.domain.watch;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Les cinq gabarits d'alerte, dont trois vivent ici : ② l'alerte retour (SMS),
 * ③ la levée (SMS), ④ l'alerte par e-mail. Aucun champ libre.
 *
 * <p><b>Pourquoi aucun texte n'est saisi par un utilisateur.</b> Un message qu'une
 * personne pourrait rédiger, envoyé par SMS depuis une marque de confiance à un
 * numéro arbitraire, serait un outil de harcèlement offert. Le serveur seul compose,
 * à partir de champs qu'il contrôle.
 *
 * <p><b>La clause qui ne bouge pas.</b> Le message ne dit jamais « est en danger »,
 * seulement « n'a pas confirmé ». Le serveur ne sait pas si la personne va bien ; il
 * sait qu'elle n'a pas répondu. La phrase « meetDo ne sait pas si elle va bien —
 * seulement qu'elle n'a pas répondu. En cas de danger immédiat, appelez le 112. »
 * fait partie du gabarit, pas de son habillage.
 *
 * <p><b>Le filtre de contenu.</b> Rien de ce qui entre ici ne porte l'adresse
 * exacte, un téléphone, un e-mail, ni la liste des participants — c'est la même
 * règle que {@code safety_share_message.dart}. On ne passe que le nom du lieu et la
 * ville, jamais le numéro et la rue.
 *
 * <p><b>Le SMS renvoie vers la page, pas vers une réponse.</b> Un expéditeur
 * alphanumérique français ne peut pas recevoir de réponse (§7.2) : le message
 * dirige tout retour vers {@code lien_statut}, et ne laisse jamais croire qu'une
 * réponse au SMS sera lue.
 */
public final class AlertMessages {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter HEURE =
        DateTimeFormatter.ofPattern("H'h'mm", Locale.FRENCH);
    private static final DateTimeFormatter JOUR_HEURE =
        DateTimeFormatter.ofPattern("EEEE d MMMM 'à' H'h'mm", Locale.FRENCH);

    /** La clause obligatoire, mot pour mot. */
    public static final String CLAUSE_112 =
        "meetDo ne sait pas si elle va bien — seulement qu'elle n'a pas répondu. "
            + "En cas de danger immédiat, appelez le 112.";

    private AlertMessages() {}

    /**
     * Ce qu'un gabarit d'alerte a le droit de nommer, et rien d'autre.
     *
     * @param prenomNom          nom d'affichage de la personne veillée
     * @param prenom             son prénom seul, pour la levée
     * @param heureLimite        l'heure de retour attendue, dépassée
     * @param dernierSigneDeVie  dernière trace — arrivée validée, ou armement
     * @param lieuNom            nom du lieu, jamais son adresse
     * @param ville              ville, si connue
     * @param titre              intitulé de l'activité
     * @param heureFin           fin prévue du créneau
     * @param lienStatut         URL absolue de la page de statut
     */
    public record Contexte(
        String prenomNom, String prenom,
        Instant heureLimite, Instant dernierSigneDeVie,
        String lieuNom, String ville, String titre,
        Instant heureDebut, Instant heureFin,
        String lienStatut) {}

    /** ② Alerte retour, par SMS. */
    public static String alerteRetourSms(Contexte c) {
        StringBuilder m = new StringBuilder();
        m.append(c.prenomNom()).append(" n'a pas confirmé son retour à ")
            .append(heure(c.heureLimite())).append(" après trois rappels. ");
        m.append("Dernier signe de vie ").append(heure(c.dernierSigneDeVie()))
            .append(lieuEtVille(c)).append(". ");
        if (c.titre() != null && !c.titre().isBlank()) {
            m.append("Activité ").append(c.titre());
            if (c.heureFin() != null) {
                m.append(", terminée à ").append(heure(c.heureFin()));
            }
            m.append(". ");
        }
        m.append("Suivi : ").append(c.lienStatut()).append(". ");
        m.append(CLAUSE_112);
        return m.toString();
    }

    /**
     * ③ Levée — fausse alerte, la personne vient de confirmer.
     *
     * <p><b>Non facultative.</b> Quelqu'un réveillé par ② doit apprendre, par le
     * même canal et dans le même fil, que l'alerte est levée. L'omettre laisserait
     * un proche inquiet pour rien.
     */
    public static String leveeSms(Contexte c) {
        return "Fausse alerte : " + c.prenom() + " vient de confirmer son retour, "
            + "tout va bien. Merci d'avoir été là. — meetDo";
    }

    /** ③ Levée, par e-mail — le pendant de {@link #leveeSms} pour le canal courrier. */
    public static String leveeEmailHtml(Contexte c) {
        return """
            <h2>Fausse alerte — %s vient de confirmer</h2>
            <p>%s a confirmé son retour. Tout va bien ; il n'y a rien à faire.
               Merci d'avoir été là.</p>
            <p style="color:#6b757d;font-size:13px;">— meetDo</p>
            """.formatted(escape(c.prenom()), escape(c.prenom()));
    }

    /**
     * ⑤ « Je suis bien rentrée » — annonce de retour, à la demande expresse de la
     * personne veillée.
     *
     * <p><b>Ce n'est pas une notification de fin de veille, et la nuance est tout
     * le sujet.</b> Le module s'interdit qu'un message apprenne à un tiers qu'une
     * veille s'est terminée — donc qu'elle avait été armée. Ce message-ci ne dit
     * rien d'une veille : il ne nomme ni le dispositif, ni le lieu, ni l'heure
     * limite, ni l'activité. Il dit « je suis rentrée », ce que la personne
     * enverrait de sa main, et il ne part que parce qu'elle l'a demandé sur cet
     * envoi-là.
     *
     * <p>Sobriété volontaire du contenu : tout ce qu'on ajouterait — un lieu, une
     * heure de fin — recomposerait la veille dans la tête du destinataire et
     * ferait de ce message ce qu'il ne doit pas être.
     */
    public static String retourAnnonceSms(Contexte c) {
        return c.prenom() + " est bien rentrée. — meetDo";
    }

    /** ⑤ L'annonce de retour, par e-mail. Voir {@link #retourAnnonceSms}. */
    public static String retourAnnonceEmailHtml(Contexte c) {
        return """
            <h2>%s est bien rentrée</h2>
            <p>%s a demandé à vous prévenir de son retour. Il n'y a rien à faire.</p>
            <p style="color:#6b757d;font-size:13px;">— meetDo</p>
            """.formatted(escape(c.prenom()), escape(c.prenom()));
    }

    /** ④ E-mail d'alerte : la version longue de ②, avec la chronologie et un lien en bouton. */
    public static String alerteRetourEmailHtml(Contexte c, String lienDesabonnement) {
        String titreLigne = (c.titre() == null || c.titre().isBlank())
            ? ""
            : "<li>Activité : <strong>" + escape(c.titre()) + "</strong>"
                + (c.heureFin() != null ? ", terminée à " + heure(c.heureFin()) : "") + "</li>";
        return """
            <h2>%s n'a pas confirmé son retour</h2>
            <p>Son heure limite de retour était <strong>%s</strong>, et trois rappels
               lui ont été adressés sans réponse.</p>
            <ul>
              <li>Dernier signe de vie : <strong>%s</strong>%s</li>
              %s
            </ul>
            <p><a href="%s" style="background:#b3261e;color:#fff;padding:12px 24px;border-radius:6px;text-decoration:none;display:inline-block;">
              Voir la page de suivi
            </a></p>
            <p style="margin-top:16px;"><em>%s</em></p>
            <p style="color:#6b757d;font-size:13px;margin-top:24px;">
              Vous recevez ce message parce que %s vous a désigné comme contact de
              confiance. <a href="%s">Ne plus être contacté</a>.
            </p>
            """.formatted(
                escape(c.prenomNom()),
                jourHeure(c.heureLimite()),
                jourHeure(c.dernierSigneDeVie()), escape(lieuEtVille(c)),
                titreLigne,
                c.lienStatut(),
                CLAUSE_112,
                escape(c.prenomNom()),
                lienDesabonnement);
    }

    /**
     * ⑤ Non-arrivée, par SMS — distincte de ②.
     *
     * <p>Un contact qui lit « n'est pas rentrée » alors que la personne n'est
     * jamais partie cherche au mauvais endroit. Ce message dit l'inverse : elle
     * n'est pas <b>arrivée</b>. Il nomme la destination et l'heure à laquelle on
     * l'attendait, là où ② nommait le dernier signe de vie et la fin de séance.
     *
     * <p>Le lieu de départ n'est pas toujours connu — on ne stocke aucune adresse
     * de domicile — et l'heure de départ est celle de l'armement. Ni la position,
     * ni un contact, ni une coordonnée n'entrent : mêmes interdits que ②.
     *
     * @param lieuDepart nom du lieu de départ, s'il est connu ; sinon {@code null}
     * @param heureDepart heure de départ (l'armement de la veille)
     */
    public static String nonArriveeSms(Contexte c, String lieuDepart, Instant heureDepart) {
        StringBuilder m = new StringBuilder();
        m.append(c.prenomNom()).append(" n'est pas arrivée. Partie");
        if (lieuDepart != null && !lieuDepart.isBlank()) {
            m.append(" de ").append(lieuDepart);
        }
        m.append(" à ").append(heure(heureDepart)).append(" pour ");
        if (c.titre() != null && !c.titre().isBlank()) {
            m.append(c.titre()).append(", ");
        }
        m.append(nomEtVille(c));
        if (c.heureDebut() != null) {
            m.append(" attendue à ").append(heure(c.heureDebut()));
        }
        m.append(". Suivi : ").append(c.lienStatut()).append(". ");
        m.append(CLAUSE_112);
        return m.toString();
    }

    /** ⑤ Non-arrivée, par e-mail — le pendant courrier de {@link #nonArriveeSms}. */
    public static String nonArriveeEmailHtml(Contexte c, String lieuDepart,
                                             Instant heureDepart, String lienDesabonnement) {
        String depart = (lieuDepart == null || lieuDepart.isBlank())
            ? "" : " de " + escape(lieuDepart);
        String titreLigne = (c.titre() == null || c.titre().isBlank())
            ? "" : "<li>Pour : <strong>" + escape(c.titre()) + "</strong></li>";
        return """
            <h2>%s n'est pas arrivée</h2>
            <p>Partie%s à <strong>%s</strong>, elle n'a pas validé son arrivée à
               destination.</p>
            <ul>
              <li>Destination : <strong>%s</strong>%s</li>
              %s
            </ul>
            <p><a href="%s" style="background:#b3261e;color:#fff;padding:12px 24px;border-radius:6px;text-decoration:none;display:inline-block;">
              Voir la page de suivi
            </a></p>
            <p style="margin-top:16px;"><em>%s</em></p>
            <p style="color:#6b757d;font-size:13px;margin-top:24px;">
              Vous recevez ce message parce que %s vous a désigné comme contact de
              confiance. <a href="%s">Ne plus être contacté</a>.
            </p>
            """.formatted(
                escape(c.prenomNom()),
                depart, heure(heureDepart),
                escape(nomEtVille(c)),
                c.heureDebut() != null ? ", attendue à " + heure(c.heureDebut()) : "",
                titreLigne,
                c.lienStatut(),
                CLAUSE_112,
                escape(c.prenomNom()),
                lienDesabonnement);
    }

    /** Nom du lieu et ville, sans la virgule de tête que produit {@link #lieuEtVille}. */
    private static String nomEtVille(Contexte c) {
        String s = lieuEtVille(c);
        return s.startsWith(", ") ? s.substring(2) : s;
    }

    private static String lieuEtVille(Contexte c) {
        StringBuilder s = new StringBuilder();
        if (c.lieuNom() != null && !c.lieuNom().isBlank()) {
            s.append(", ").append(c.lieuNom());
        }
        if (c.ville() != null && !c.ville().isBlank()) {
            s.append(", ").append(c.ville());
        }
        return s.toString();
    }

    private static String heure(Instant instant) {
        return instant == null ? "?" : HEURE.format(instant.atZone(ZONE));
    }

    private static String jourHeure(Instant instant) {
        return instant == null ? "?" : JOUR_HEURE.format(instant.atZone(ZONE));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
