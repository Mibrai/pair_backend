package org.program.pair.shared.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ce qu'un journal peut écrire d'une adresse ou d'un jeton (P-BS-19).
 *
 * <p>Les journaux de production sont lus par plus de monde que la base, et
 * conservés ailleurs qu'elle. Une adresse complète y est une donnée personnelle
 * de plus à protéger ; un jeton en clair — de partage, d'appareil — suffit à
 * s'en servir. Ce qu'il faut à celui qui diagnostique, c'est reconnaître la même
 * chose d'une ligne à l'autre, pas pouvoir la réutiliser.
 */
public final class Masque {

    private static final Pattern ADRESSE =
        Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)");

    private Masque() {
    }

    /**
     * {@code seyd.njoya@icloud.com} → {@code s***@icloud.com}. La première lettre
     * et le domaine suffisent à distinguer « un fournisseur refuse ce domaine »
     * d'un problème propre à une personne.
     */
    public static String email(String adresse) {
        if (adresse == null) {
            return null;
        }
        int arobase = adresse.lastIndexOf('@');
        if (arobase <= 0 || arobase == adresse.length() - 1) {
            return "***";
        }
        return adresse.charAt(0) + "***" + adresse.substring(arobase).toLowerCase(Locale.ROOT);
    }

    /**
     * Masque toute adresse contenue dans un texte libre — typiquement le corps
     * d'erreur d'un fournisseur d'e-mail, qui recopie volontiers le destinataire.
     */
    public static String emailsDans(String texte) {
        if (texte == null) {
            return null;
        }
        Matcher m = ADRESSE.matcher(texte);
        StringBuilder sortie = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sortie,
                Matcher.quoteReplacement(m.group(1) + "***@" + m.group(2).toLowerCase(Locale.ROOT)));
        }
        m.appendTail(sortie);
        return sortie.toString();
    }

    /**
     * Les huit premiers caractères hexadécimaux du SHA-256 du jeton : de quoi
     * relier deux lignes de journal entre elles, rien pour rejouer le jeton. Un
     * préfixe du jeton lui-même, comme on l'écrivait, en livre une partie.
     */
    public static String jeton(String jeton) {
        if (jeton == null) {
            return null;
        }
        try {
            byte[] condensat = MessageDigest.getInstance("SHA-256")
                .digest(jeton.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(condensat, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 est garanti par toute JVM", e);
        }
    }
}
