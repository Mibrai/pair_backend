package org.program.pair.shared.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** P-BS-19 — ce qu'un journal garde d'une adresse ou d'un jeton. */
class MasqueTest {

    @Test
    void leMasque_gardeLaPremiereLettreEtLeDomaine() {
        assertThat(Masque.email("seyd.njoya@icloud.com")).isEqualTo("s***@icloud.com");
        assertThat(Masque.email("A@Example.ORG")).isEqualTo("A***@example.org");
    }

    @Test
    void uneAdresseMalFormee_neLaissePasPasserSonContenu() {
        assertThat(Masque.email("pas-une-adresse")).isEqualTo("***");
        assertThat(Masque.email("@domaine.fr")).isEqualTo("***");
        assertThat(Masque.email(null)).isNull();
    }

    @Test
    void lesAdressesDUnTexteLibre_sontToutesMasquees() {
        String corps = "{\"message\":\"Invalid `to` field: lena.mueller@web.de, bob@gmx.de\"}";

        String masque = Masque.emailsDans(corps);

        assertThat(masque).doesNotContain("lena.mueller", "bob@");
        assertThat(masque).contains("l***@web.de", "b***@gmx.de", "Invalid `to` field");
    }

    @Test
    void leJeton_devientUneEmpreinteStable_quiNeLivreRienDuJeton() {
        String jeton = "Ab3dEf6hIj9kLm2nOp5qRs";

        String empreinte = Masque.jeton(jeton);

        assertThat(empreinte).hasSize(8).matches("[0-9a-f]{8}");
        assertThat(Masque.jeton(jeton)).as("stable : deux lignes se relient").isEqualTo(empreinte);
        assertThat(Masque.jeton(jeton + "x")).isNotEqualTo(empreinte);
        assertThat(jeton).doesNotContain(empreinte);
    }
}
