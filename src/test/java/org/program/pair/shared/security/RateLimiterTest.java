package org.program.pair.shared.security;

import org.junit.jupiter.api.Test;
import org.program.pair.shared.exception.TooManyRequestsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le limiteur de connexion, tel que le relevé du chantier mobile du 01/09 l'a
 * trouvé et tel qu'il doit se comporter.
 *
 * <p>Trois défauts y étaient constatés, et chacun a son test ici : le compteur ne
 * redescendait jamais, deux comptes sur une même connexion se bloquaient
 * mutuellement, et une tentative refusée paraissait rallonger l'attente. Le
 * dernier est celui qui compte le plus hors campagne de test : un utilisateur
 * légitime qui se trompe de mot de passe, réessaie, et se voit refuser plus
 * longtemps à chaque essai n'a aucun moyen de comprendre ce qui lui arrive.
 */
class RateLimiterTest {

    private static final String IP = "203.0.113.7";

    /** Une horloge qu'on avance à la main : une fenêtre glissante ne se prouve qu'en la franchissant. */
    private static final class HorlogeReglable extends Clock {
        private Instant maintenant = Instant.parse("2026-09-02T20:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return maintenant; }

        void avancer(Duration duree) { maintenant = maintenant.plus(duree); }
    }

    @Test
    void desConnexionsReussies_neConsommentRien() {
        // Le cas qui a bloqué la campagne du 01/09 : une quinzaine de connexions
        // légitimes depuis une seule adresse suffisaient au refus total.
        RateLimiter limiteur = new RateLimiter();

        for (int i = 0; i < 50; i++) {
            limiteur.checkLogin(IP, "moi@example.org");
            limiteur.recordLoginSuccess("moi@example.org");
        }

        assertThatCode(() -> limiteur.checkLogin(IP, "moi@example.org"))
            .doesNotThrowAnyException();
    }

    @Test
    void deuxComptesSurLaMemeConnexion_neSeBloquentPas() {
        // Deux comptes de test sur un même poste se bloquaient mutuellement, alors
        // qu'aucun des deux n'avait rien fait d'anormal.
        RateLimiter limiteur = new RateLimiter();

        for (int i = 0; i < 10; i++) {
            limiteur.recordLoginFailure(IP, "premier@example.org");
        }

        assertThatThrownBy(() -> limiteur.checkLogin(IP, "premier@example.org"))
            .isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limiteur.checkLogin(IP, "second@example.org"))
            .doesNotThrowAnyException();
    }

    @Test
    void uneTentativeRefusee_neRallongePasLAttente() {
        // Le défaut le plus ingrat : vérifier si l'attente a suffi la prolongeait.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        for (int i = 0; i < 10; i++) {
            limiteur.recordLoginFailure(IP, "moi@example.org");
        }
        assertThatThrownBy(() -> limiteur.checkLogin(IP, "moi@example.org"))
            .isInstanceOf(TooManyRequestsException.class);

        // On piétine devant la porte pendant quatorze minutes.
        for (int i = 0; i < 14; i++) {
            horloge.avancer(Duration.ofMinutes(1));
            assertThatThrownBy(() -> limiteur.checkLogin(IP, "moi@example.org"))
                .isInstanceOf(TooManyRequestsException.class);
        }

        // La quinzième minute passée, la porte s'ouvre — les refus n'ont rien ajouté.
        horloge.avancer(Duration.ofMinutes(2));
        assertThatCode(() -> limiteur.checkLogin(IP, "moi@example.org"))
            .doesNotThrowAnyException();
    }

    @Test
    void laFenetre_glisseVraiment() {
        // L'ancien compteur était un entier qui ne redescendait jamais : « dix par
        // quinze minutes » était en fait « dix en tout ».
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        for (int i = 0; i < 9; i++) {
            limiteur.recordLoginFailure(IP, "moi@example.org");
            horloge.avancer(Duration.ofMinutes(2));
        }

        // Dix-huit minutes ont passé : les premiers échecs sont sortis de la
        // fenêtre, et rien ne bloque.
        assertThatCode(() -> limiteur.checkLogin(IP, "moi@example.org"))
            .doesNotThrowAnyException();
    }

    @Test
    void dixEchecsSurUnCompte_fermentLaPorte() {
        RateLimiter limiteur = new RateLimiter();
        for (int i = 0; i < 9; i++) {
            limiteur.recordLoginFailure(IP, "moi@example.org");
            assertThatCode(() -> limiteur.checkLogin(IP, "moi@example.org"))
                .doesNotThrowAnyException();
        }
        limiteur.recordLoginFailure(IP, "moi@example.org");

        assertThatThrownBy(() -> limiteur.checkLogin(IP, "moi@example.org"))
            .isInstanceOf(TooManyRequestsException.class)
            .hasMessageContaining("compte");
    }

    @Test
    void unBalayageDeComptesDepuisUneMemeAdresse_finitParEtreBorne() {
        // Le budget par adresse existe pour cela, et seulement pour cela : un
        // essai sur cinquante comptes distincts ne toucherait aucun plafond par
        // compte.
        RateLimiter limiteur = new RateLimiter();
        for (int i = 0; i < 50; i++) {
            limiteur.recordLoginFailure(IP, "cible" + i + "@example.org");
        }

        assertThatThrownBy(() -> limiteur.checkLogin(IP, "cible99@example.org"))
            .isInstanceOf(TooManyRequestsException.class)
            .hasMessageContaining("connexion");
    }

    @Test
    void unSuccesSurLeCompte_libereLeCompteMaisPasLAdresse() {
        RateLimiter limiteur = new RateLimiter();
        for (int i = 0; i < 10; i++) {
            limiteur.recordLoginFailure(IP, "moi@example.org");
        }
        assertThatThrownBy(() -> limiteur.checkLogin(IP, "moi@example.org"))
            .isInstanceOf(TooManyRequestsException.class);

        limiteur.recordLoginSuccess("moi@example.org");
        assertThatCode(() -> limiteur.checkLogin(IP, "moi@example.org"))
            .doesNotThrowAnyException();
    }

    @Test
    void reset_videTout() {
        RateLimiter limiteur = new RateLimiter();
        for (int i = 0; i < 10; i++) {
            limiteur.recordLoginFailure(IP, "moi@example.org");
        }
        limiteur.reset();

        assertThatCode(() -> limiteur.checkLogin(IP, "moi@example.org"))
            .doesNotThrowAnyException();
    }

    @Test
    void lesEnvoisDEmail_restentBornesEtGlissentAussi() {
        // Ces routes-là consomment à l'appel : c'est l'envoi lui-même qu'il s'agit
        // de borner, et il a lieu que l'adresse existe ou non.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        for (int i = 0; i < 3; i++) {
            limiteur.checkPasswordReset(IP, "moi@example.org");
        }
        assertThatThrownBy(() -> limiteur.checkPasswordReset(IP, "moi@example.org"))
            .isInstanceOf(TooManyRequestsException.class);

        horloge.avancer(Duration.ofHours(1).plusMinutes(1));
        assertThatCode(() -> limiteur.checkPasswordReset(IP, "moi@example.org"))
            .doesNotThrowAnyException();
    }

    @Test
    void troisPersonnesDerriereUnMemeNat_sInscriventToutesLesTrois() {
        // Le défaut signalé le 07/09 : le budget serré était sur la connexion, et
        // s'inscrire ensemble depuis un même réseau est le mode d'arrivée normal.
        // Six comptes distincts depuis une seule IP passent désormais — l'ancien
        // plafond en refusait le sixième.
        RateLimiter limiteur = new RateLimiter();

        for (int i = 0; i < 6; i++) {
            String email = "invite" + i + "@example.org";
            assertThatCode(() -> limiteur.checkRegister(IP, email))
                .doesNotThrowAnyException();
        }
    }

    @Test
    void uneMemeAdresse_resteBorneeMemeEnChangeantDeConnexion() {
        // Le budget serré a changé de clé, il n'a pas disparu : c'est l'adresse
        // visée qui le porte, donc changer d'IP ne le contourne pas.
        RateLimiter limiteur = new RateLimiter();

        for (int i = 0; i < 3; i++) {
            limiteur.checkResendVerification("10.0.0." + i, "cible@example.org");
        }
        assertThatThrownBy(() ->
            limiteur.checkResendVerification("10.0.0.99", "cible@example.org"))
            .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void unRefusParAdresse_neConsommePasLeBudgetDeLaConnexion() {
        // Les deux budgets sont vérifiés avant que l'un ou l'autre ne soit
        // consommé : sinon une tentative refusée rapprocherait du refus suivant,
        // le mode de panne que ce limiteur existe pour avoir supprimé.
        RateLimiter limiteur = new RateLimiter();

        for (int i = 0; i < 3; i++) {
            limiteur.checkResendVerification(IP, "sature@example.org");
        }
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() ->
                limiteur.checkResendVerification(IP, "sature@example.org"))
                .isInstanceOf(TooManyRequestsException.class);
        }

        // La connexion n'a consommé que les trois appels retenus : une autre
        // adresse depuis la même IP passe toujours.
        assertThatCode(() -> limiteur.checkResendVerification(IP, "autre@example.org"))
            .doesNotThrowAnyException();
    }

    @Test
    void unEmailNul_neFaitPasTomberLeLimiteur() {
        // La borne par adresse s'applique alors seule.
        RateLimiter limiteur = new RateLimiter();
        assertThatCode(() -> limiteur.checkLogin(IP, null)).doesNotThrowAnyException();
        assertThatCode(() -> limiteur.recordLoginFailure(IP, null)).doesNotThrowAnyException();
        assertThatCode(() -> limiteur.recordLoginSuccess(null)).doesNotThrowAnyException();
    }

    @Test
    void laCasseDeLEmail_neCreePasDeuxBudgets() {
        RateLimiter limiteur = new RateLimiter();
        for (int i = 0; i < 10; i++) {
            limiteur.recordLoginFailure(IP, "Moi@Example.org");
        }

        assertThatThrownBy(() -> limiteur.checkLogin(IP, "moi@example.org"))
            .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void uneCleVidee_estRetireeDeLaCarte() {
        // Sans élagage, la carte grossirait d'une entrée par adresse vue,
        // définitivement — une fuite lente sur un processus de longue vie.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        limiteur.recordLoginFailure(IP, "moi@example.org");
        horloge.avancer(Duration.ofMinutes(16));

        assertThatCode(() -> limiteur.checkLogin(IP, "moi@example.org"))
            .doesNotThrowAnyException();
        assertThat(limiteur.taillePourTests()).isZero();
    }
}
