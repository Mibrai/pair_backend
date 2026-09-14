package org.program.pair.domain.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La Smart App Banner de la page de réinitialisation : posée seulement le jour où
 * l'app gère {@code /r/}, et toujours sur l'adresse canonique (décision du 14/09/2026).
 */
class BanniereReinitialisationTest {

    private static ReinitialisationLinkController controleur(boolean allume, String appStoreId) {
        ReinitialisationLinkController c = new ReinitialisationLinkController(null, null, null);
        ReflectionTestUtils.setField(c, "reinitialisationDansApp", allume);
        ReflectionTestUtils.setField(c, "appStoreId", appStoreId);
        ReflectionTestUtils.setField(c, "baseUrl", "https://lien.meetdo.fun");
        return c;
    }

    @Test
    void interrupteurEteint_aucuneBanniere_memeAvecLIdentifiant() {
        assertThat(controleur(false, "1234567890").banniere("jeton")).isNull();
    }

    @Test
    void identifiantManquantOuInvente_aucuneBanniere() {
        assertThat(controleur(true, "").banniere("jeton")).isNull();
        assertThat(controleur(true, "A COMPLETER").banniere("jeton")).isNull();
    }

    @Test
    void formulairePropose_laBanniereOuvreLAdresseCanoniqueAvecLeJeton() {
        assertThat(controleur(true, "1234567890").banniere("abc-123"))
            .isEqualTo("app-id=1234567890, app-argument=https://lien.meetdo.fun/r/abc-123");
    }

    @Test
    void pageDEchec_laBanniereOuvreMotDePasseOublie() {
        assertThat(controleur(true, " 1234567890 ").banniere(null))
            .isEqualTo("app-id=1234567890, app-argument=https://lien.meetdo.fun/r")
            .doesNotContain("meetdo://");
    }
}
