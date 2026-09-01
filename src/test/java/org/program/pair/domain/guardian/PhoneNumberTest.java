package org.program.pair.domain.guardian;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La normalisation des numéros, et surtout ce qu'elle refuse de deviner.
 *
 * <p>L'enjeu n'est pas cosmétique : c'est cette forme unique qui rend vrai le
 * « un refus vaut pour tout meetDo, quel que soit le compte qui redésigne ». Si
 * deux écritures du même numéro produisaient deux formes, le blocage se
 * contournerait en réécrivant le numéro autrement.
 */
class PhoneNumberTest {

    @Test
    void lesEcrituresDunMemeMobileFrancais_doiventConvergerVersUneSeuleForme() {
        String attendu = "+33612345678";
        assertThat(PhoneNumber.toE164("0612345678")).contains(attendu);
        assertThat(PhoneNumber.toE164("06 12 34 56 78")).contains(attendu);
        assertThat(PhoneNumber.toE164("06.12.34.56.78")).contains(attendu);
        assertThat(PhoneNumber.toE164("+33 6 12 34 56 78")).contains(attendu);
        assertThat(PhoneNumber.toE164("+33612345678")).contains(attendu);
        assertThat(PhoneNumber.toE164("0033612345678")).contains(attendu);
        assertThat(PhoneNumber.toE164(" (0)6-12-34-56-78 ")).contains(attendu);
    }

    @Test
    void unMobileEn07_estAccepte() {
        assertThat(PhoneNumber.toE164("0712345678")).contains("+33712345678");
    }

    @Test
    void unFixeFrancais_estRefuse_pasDeforme() {
        // 01… n'est pas un mobile : le flux n'envoie qu'à des mobiles, et poser un
        // refus définitif sur un fixe serait poser un refus sur le mauvais numéro.
        assertThat(PhoneNumber.toE164("0123456789")).isEmpty();
    }

    @Test
    void unNumeroTropCourtOuTropLong_estRefuse() {
        assertThat(PhoneNumber.toE164("0612345")).isEmpty();
        assertThat(PhoneNumber.toE164("06123456789")).isEmpty();
    }

    @Test
    void videOuAbsent_estRefuse() {
        assertThat(PhoneNumber.toE164(null)).isEmpty();
        assertThat(PhoneNumber.toE164("")).isEmpty();
        assertThat(PhoneNumber.toE164("   ")).isEmpty();
        assertThat(PhoneNumber.toE164("pas un numéro")).isEmpty();
    }

    @Test
    void unNumeroEtrangerExplicite_estLaissePasser_maisNonPretenduValide() {
        // On ne le déforme pas et on ne prétend pas le vérifier : séparateurs
        // retirés, le + conservé. Le produit est français, mais un contact à
        // l'étranger ne doit pas être rejeté par principe.
        assertThat(PhoneNumber.toE164("+49 151 12345678")).contains("+4915112345678");
    }

    @Test
    void deuxMobilesFrancaisDifferents_neConvergentPas() {
        assertThat(PhoneNumber.toE164("0612345678"))
            .isNotEqualTo(PhoneNumber.toE164("0698765432"));
    }
}
