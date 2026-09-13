package org.program.pair;

import org.junit.jupiter.api.Test;

/**
 * Le contexte complet démarre.
 *
 * <p>Il tournait sans profil, sur la base locale et le mot de passe de repli
 * d'application.properties. Ce repli n'existe plus (P-BS-12) : le test prend la
 * base de Testcontainers comme les autres.
 */
class PairApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}
