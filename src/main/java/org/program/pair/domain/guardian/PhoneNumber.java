package org.program.pair.domain.guardian;

import java.util.Optional;

/**
 * Met un numéro de téléphone sous une forme unique, ou dit qu'il n'y arrive pas.
 *
 * <p><b>Pourquoi une forme unique est le cœur du sujet.</b> Le refus d'un contact
 * est global au numéro et définitif (§7.4 de la demande) : sans normalisation,
 * {@code 06 12 34 56 78}, {@code +33612345678} et {@code 0033 6 12 34 56 78}
 * seraient trois refus distincts, et redésigner le même téléphone sous une autre
 * écriture contournerait le blocage — exactement l'étape de contournement triviale
 * que la règle existe pour fermer. La comparaison ne peut donc pas se faire sur le
 * texte saisi ; elle se fait sur cette forme-ci.
 *
 * <p><b>Périmètre assumé : la France.</b> Le projet n'embarque pas de
 * bibliothèque de numéros (pas de libphonenumber), et en écrire une couvrant tous
 * les plans de numérotation du monde serait à la fois faux et hors sujet. On traite
 * donc les mobiles français, et l'on <b>refuse</b> proprement le reste plutôt que
 * de le déformer : mieux vaut dire « je ne sais pas normaliser ce numéro » que
 * produire une forme fausse qui ferait diverger deux écritures du même numéro, ou
 * converger deux numéros différents. Un numéro déjà en {@code +} d'un autre pays
 * est laissé tel quel, une fois ses séparateurs retirés — on ne le déforme pas, on
 * ne prétend pas non plus le valider.
 */
public final class PhoneNumber {

    private PhoneNumber() {}

    /**
     * La forme E.164 d'un numéro, ou vide si l'entrée n'est pas un numéro qu'on
     * sait normaliser sans risque.
     *
     * <p>Règles, dans l'ordre : on retire tout ce qui n'est ni chiffre ni
     * {@code +} (espaces, points, tirets, parenthèses) ; {@code 0033} et
     * {@code +33} deviennent la même chose ; un {@code 0} de tête suivi de neuf
     * chiffres devient {@code +33} + ces neuf chiffres. Ce qui ne rentre dans
     * aucune de ces formes est rendu vide.
     */
    public static Optional<String> toE164(String saisi) {
        if (saisi == null) {
            return Optional.empty();
        }

        boolean plusDeTete = saisi.strip().startsWith("+");
        String chiffres = saisi.replaceAll("[^0-9]", "");
        if (chiffres.isEmpty()) {
            return Optional.empty();
        }

        // 0033… ⇒ +33… : le préfixe international composé remplace le +.
        if (chiffres.startsWith("00")) {
            chiffres = chiffres.substring(2);
            plusDeTete = true;
        }

        if (plusDeTete) {
            // Un indicatif déjà explicite. On valide le seul pays qu'on connaît —
            // 33 suivi d'un mobile à neuf chiffres commençant par 6 ou 7 — et on
            // laisse passer les autres tels quels, sans prétendre les vérifier.
            if (chiffres.startsWith("33")) {
                return mobileFrancais(chiffres.substring(2));
            }
            return Optional.of("+" + chiffres);
        }

        // Forme nationale : un 0 de tête, puis neuf chiffres.
        if (chiffres.startsWith("0") && chiffres.length() == 10) {
            return mobileFrancais(chiffres.substring(1));
        }

        // Neuf chiffres sans préfixe : on tente le mobile français, sinon vide.
        return mobileFrancais(chiffres);
    }

    /**
     * Neuf chiffres derrière l'indicatif France. On n'accepte que les mobiles
     * (6 ou 7 en tête) : le flux n'envoie de SMS qu'à des mobiles, et un fixe ou
     * un numéro court accepté ici finirait par un message qui ne part jamais, ou
     * pire, un refus définitif posé sur un numéro qui n'est pas celui qu'on croit.
     */
    private static Optional<String> mobileFrancais(String neufChiffres) {
        if (neufChiffres.length() != 9) {
            return Optional.empty();
        }
        char tete = neufChiffres.charAt(0);
        if (tete != '6' && tete != '7') {
            return Optional.empty();
        }
        return Optional.of("+33" + neufChiffres);
    }
}
