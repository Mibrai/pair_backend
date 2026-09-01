package org.program.pair.domain.watch;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les gabarits d'alerte : ce qu'ils disent, et ce qu'ils ne disent jamais.
 *
 * <p>Deux invariants comptent plus que la mise en forme : la clause « n'a pas
 * confirmé » plutôt que « est en danger », et l'absence de toute coordonnée
 * exacte. Le serveur ne sait pas si la personne va bien ; il sait qu'elle n'a pas
 * répondu.
 */
class AlertMessagesTest {

    private static AlertMessages.Contexte contexte() {
        return new AlertMessages.Contexte(
            "Camille Dubois", "Camille",
            Instant.parse("2026-09-01T22:00:00Z"),
            Instant.parse("2026-09-01T19:30:00Z"),
            "Studio Lumière", "Strasbourg", "Yoga",
            Instant.parse("2026-09-01T21:00:00Z"),
            "https://lien.meetdo.fun/public/watch/abc123");
    }

    @Test
    void lalerteRetour_diteQuElleNaPasConfirme_jamaisQuElleEstEnDanger() {
        String m = AlertMessages.alerteRetourSms(contexte());

        assertThat(m)
            .contains("Camille Dubois")
            .contains("n'a pas confirmé son retour")
            .contains("après trois rappels")
            .contains(AlertMessages.CLAUSE_112)
            .contains("112");
        assertThat(m).doesNotContainIgnoringCase("en danger,");
    }

    @Test
    void lalerteRetour_neContientNiAdresseExacteNiTelephoneNiEmail() {
        String m = AlertMessages.alerteRetourSms(contexte());
        // Le contexte ne porte que le nom du lieu et la ville — jamais rue, numéro,
        // téléphone ou e-mail. On vérifie qu'aucun de ces motifs ne s'y glisse.
        assertThat(m)
            .contains("Studio Lumière")
            .contains("Strasbourg")
            .doesNotContain("@")
            .doesNotContainPattern("\\d{2}[ .]\\d{2}[ .]\\d{2}");
    }

    @Test
    void lalerteRetour_renvoieVersLaPageDeStatut_pasVersUneReponseAuSms() {
        String m = AlertMessages.alerteRetourSms(contexte());
        assertThat(m).contains("https://lien.meetdo.fun/public/watch/abc123");
    }

    @Test
    void laLevee_estCourteEtRassurante_etNommeLaPersonne() {
        String m = AlertMessages.leveeSms(contexte());
        assertThat(m)
            .contains("Camille")
            .containsIgnoringCase("fausse alerte")
            .containsIgnoringCase("confirm");
    }

    @Test
    void lemailDalerte_porteLaClause_leLienEnBouton_etUnDesabonnement() {
        String html = AlertMessages.alerteRetourEmailHtml(
            contexte(), "https://lien.meetdo.fun/public/guardian-consent/tok");
        assertThat(html)
            .contains("n'a pas confirmé son retour")
            .contains(AlertMessages.CLAUSE_112)
            .contains("https://lien.meetdo.fun/public/watch/abc123")
            .contains("https://lien.meetdo.fun/public/guardian-consent/tok")
            .containsIgnoringCase("ne plus être contacté");
    }
}
