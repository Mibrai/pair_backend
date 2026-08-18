package org.program.pair.shared.security;

import java.security.SecureRandom;
import java.util.function.Predicate;

/**
 * Le jeton qui identifie une ressource dans une URL publique — et rien d'autre.
 *
 * <p>Une page publique ne peut pas porter l'UUID interne de la ressource. Un
 * UUID sert d'identifiant partout ailleurs dans le système : le publier, c'est
 * donner à qui reçoit un lien la clé d'un objet qu'il n'a pas le droit de
 * manipuler, et c'est surtout offrir une prise à l'énumération. Le jeton est
 * donc tiré au hasard, sans lien avec la ligne qu'il désigne, et ne sert qu'à
 * la retrouver.
 *
 * <p><b>Pourquoi 22 caractères en base62 :</b> 22 × log2(62) ≈ 131 bits. Un
 * attaquant qui tirerait un milliard de jetons par seconde n'en trouverait pas
 * un seul valide avant très longtemps après la fin du soleil. La longueur est
 * fixe pour que la colonne le soit aussi ({@code VARCHAR(22)}), et l'alphabet
 * exclut tout caractère qui demanderait un échappement dans une URL.
 *
 * <p>Cette classe existe parce que <b>deux</b> surfaces publiques en ont besoin
 * — le partage de sécurité d'un créneau et la page publique de créneau — et
 * qu'un second générateur écrit six semaines plus tard n'aurait aucune raison
 * de tomber sur la même longueur ni sur le même alphabet. Deux jetons de formes
 * différentes ne provoquent pas d'erreur : ils provoquent une colonne trop
 * courte, un jour, en production. Même raison d'être que
 * {@code SlotAddressVisibility}, {@code SlotAudience} et {@code SlotTiming}.
 *
 * <p>Le tirage passe par {@link SecureRandom} : un {@code Random} ordinaire est
 * prédictible à partir de quelques sorties observées, ce qui rendrait le jeton
 * devinable pour qui a déjà reçu un lien légitime.
 */
public final class ShareToken {

    /** Longueur exacte d'un jeton, et donc de la colonne qui le stocke. */
    public static final int LENGTH = 22;

    /**
     * Base62 : chiffres, majuscules, minuscules. Aucun caractère réservé d'URL,
     * donc jamais d'échappement — un jeton se colle tel quel dans un lien.
     */
    private static final String ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /**
     * Nombre de tirages avant d'abandonner dans {@link #nextUnique}. Une
     * collision sur 131 bits n'arrivera pas ; la borne est là pour qu'un
     * prédicat mal écrit — qui répondrait « déjà pris » pour tout — échoue
     * franchement au lieu de boucler indéfiniment.
     */
    private static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ShareToken() {}

    /** Un jeton neuf, tiré au hasard. */
    public static String next() {
        StringBuilder token = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            token.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return token.toString();
    }

    /**
     * Un jeton neuf dont l'appelant a vérifié qu'il n'est pas déjà en base.
     *
     * <p>La contrainte {@code UNIQUE} de la colonne reste la seule garantie
     * réelle — ce contrôle-ci lui évite simplement de se manifester sous la
     * forme d'une violation d'intégrité au flush. À utiliser avec un
     * {@code existsByShareToken} du repository.
     *
     * @param alreadyTaken vrai si le jeton proposé est déjà attribué
     * @throws IllegalStateException si aucun jeton libre n'a été trouvé
     */
    public static String nextUnique(Predicate<String> alreadyTaken) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = next();
            if (!alreadyTaken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
            "Aucun jeton de partage libre après " + MAX_ATTEMPTS + " tirages : "
                + "sur " + LENGTH + " caractères en base62, c'est le test d'unicité "
                + "qu'il faut suspecter, pas le hasard.");
    }
}
