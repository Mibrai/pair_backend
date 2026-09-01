package org.program.pair.domain.watch;

import java.security.SecureRandom;

/**
 * Tire un code de retour court, lisible, et non devinable.
 *
 * <p><b>Cinq caractères.</b> Assez pour tenir face à trois essais en ligne — c'est
 * la vraie défense — et assez court pour se mémoriser puis se ressaisir sans se
 * tromper, y compris à 23 h après une soirée. La sécurité ne repose pas sur la
 * longueur du code mais sur le plafond d'essais et le poivre hors base ; voir
 * {@code ReturnCode}.
 *
 * <p><b>Un alphabet sans ambiguïté visuelle.</b> Ni {@code O}/{@code 0}, ni
 * {@code I}/{@code 1}/{@code L} : un code qu'on lit de travers est un code qu'on
 * ressaisit faux, et une ressaisie fausse coûte un essai sur trois. Le gain de
 * sécurité d'un ou deux symboles de plus ne vaut pas ce risque.
 *
 * <p><b>{@link SecureRandom}.</b> Un {@code Random} ordinaire devient prédictible à
 * partir de quelques sorties observées ; pour un secret, c'est disqualifiant.
 */
public final class ReturnCodeGenerator {

    /** Longueur du code. */
    public static final int LENGTH = 5;

    /**
     * Alphabet : lettres majuscules et chiffres, débarrassés des paires qui se
     * confondent à l'œil (O/0, I/1/L). Trente symboles.
     */
    private static final char[] ALPHABET =
        "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private ReturnCodeGenerator() {}

    public static String next() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }
}
