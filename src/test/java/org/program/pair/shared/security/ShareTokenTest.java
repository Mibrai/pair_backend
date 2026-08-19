package org.program.pair.shared.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ce que les colonnes {@code VARCHAR(22)} de V63 et V65 attendent du générateur.
 *
 * <p>Ces tests ne mesurent pas la qualité du hasard — {@link java.security.SecureRandom}
 * s'en charge. Ils verrouillent la forme du jeton, parce que c'est elle que la
 * base contraint et qu'une divergence ne se verrait qu'à l'insertion.
 */
class ShareTokenTest {

    private static final Pattern BASE62 = Pattern.compile("^[0-9A-Za-z]{22}$");

    @Test
    void unJeton_doitFaireExactement22CaracteresBase62() {
        // La colonne est VARCHAR(22) : un caractère de plus est une exception à
        // l'insertion, un caractère réservé d'URL est un lien cassé.
        for (int i = 0; i < 500; i++) {
            String token = ShareToken.next();
            assertThat(token).hasSize(ShareToken.LENGTH);
            assertThat(BASE62.matcher(token).matches())
                .as("jeton hors alphabet base62 : %s", token)
                .isTrue();
        }
    }

    @Test
    void deuxJetons_neDoiventJamaisCoincider() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(ShareToken.next()))
                .as("collision sur 131 bits au tirage %d — le tirage n'est pas aléatoire", i)
                .isTrue();
        }
    }

    @Test
    void nextUnique_doitRetirer_tantQueLeJetonEstDejaPris() {
        Set<String> refused = new HashSet<>();
        String token = ShareToken.nextUnique(candidate -> {
            // Refuse les deux premiers tirages, accepte le troisième.
            if (refused.size() < 2) {
                refused.add(candidate);
                return true;
            }
            return false;
        });

        assertThat(refused).hasSize(2);
        assertThat(token).isNotIn(refused);
        assertThat(BASE62.matcher(token).matches()).isTrue();
    }

    @Test
    void nextUnique_doitEchouerFranchement_siLeTestDUniciteRefuseTout() {
        // Un prédicat qui répond toujours « déjà pris » est un bug d'appelant.
        // Il doit se voir immédiatement, pas boucler.
        assertThatThrownBy(() -> ShareToken.nextUnique(candidate -> true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Aucun jeton de partage libre");
    }
}
