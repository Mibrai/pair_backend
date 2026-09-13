package org.program.pair.shared.security;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.program.pair.shared.exception.TooManyRequestsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
 *
 * <p><b>Depuis le 12/09, le budget serré ne verrouille plus le compte d'un
 * autre.</b> Il portait sur le compte seul : dix mots de passe faux sur une
 * adresse e-mail — que n'importe qui connaît ou devine — fermaient la porte à
 * qui la porte, depuis chez lui et avec le bon mot de passe. C'est le point le
 * plus grave de la fiche P-BS-08, et il se prouve par un test qui aurait échoué
 * avant : le propriétaire entre depuis une autre adresse. Les deux garde-fous
 * plus larges ont leurs tests aussi, faute de quoi le remède rendrait le plafond
 * contournable en changeant d'adresse.
 *
 * <p><b>Depuis le 10/09, le refus dit aussi quand revenir</b>, et cela se prouve
 * avec la même horloge réglable : un délai est un instant franchi, pas un
 * booléen. Les tests qui suivent vérifient qu'il vaut la fenêtre de la route,
 * qu'il décroît à mesure qu'elle glisse, qu'il ne descend jamais à zéro — un
 * {@code Retry-After: 0} invite à réessayer sur-le-champ —, et qu'il est celui
 * de la clé qui a réellement refusé, ce qui n'a d'intérêt que là où les deux
 * budgets se sont remplis à des moments différents.
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
            limiteur.recordLoginSuccess(IP, "moi@example.org");
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

        limiteur.recordLoginSuccess(IP, "moi@example.org");
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
        assertThatCode(() -> limiteur.recordLoginSuccess(IP, null)).doesNotThrowAnyException();
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

    // ------------------------------------------- le verrouillage du compte d'autrui

    @Test
    void dixEchecsDepuisUneAdresse_nEmpechentPasLeProprietaireDeSeConnecterDAilleurs() {
        // Le défaut le plus grave de la fiche P-BS-08, et celui que ce test
        // ferme : le budget serré portait sur le compte seul, si bien qu'un tiers
        // fermait la porte au propriétaire en dix requêtes, sans rien savoir de
        // lui — un déni de service ciblé contre la personne de son choix.
        RateLimiter limiteur = new RateLimiter();
        String cible = "proprietaire@example.org";

        for (int i = 0; i < 10; i++) {
            limiteur.recordLoginFailure("198.51.100.4", cible);
        }

        // Le nuisible s'est fermé la porte à lui-même, et à lui seul.
        assertThatThrownBy(() -> limiteur.checkLogin("198.51.100.4", cible))
            .isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limiteur.checkLogin("203.0.113.77", cible))
            .doesNotThrowAnyException();
    }

    @Test
    void centEchecsRepartisSurCentAdresses_fermentQuandMemeLeCompte() {
        // Le couple seul serait contournable : dix échecs, on change d'adresse,
        // on recommence. Le plafond de compte reste donc posé par-dessus, dix
        // fois plus large — hors de portée d'un tiers qui veut nuire à peu de
        // frais, atteint par un balayage distribué.
        RateLimiter limiteur = new RateLimiter();
        String cible = "cible@example.org";

        for (int i = 0; i < 100; i++) {
            limiteur.recordLoginFailure("10.1." + (i / 250) + "." + (i % 250), cible);
        }

        TooManyRequestsException refus =
            refusDe(() -> limiteur.checkLogin("203.0.113.88", cible));
        // Le refus vient du compte, pas de la connexion : aucune des cent
        // adresses n'a plus d'un échec à son actif.
        assertThat(refus).hasMessageContaining("ce compte");
        assertThat(refus.getRetryAfterSecondes()).isEqualTo(Duration.ofMinutes(15).toSeconds());
    }

    @Test
    void quatreVingtDixNeufEchecsRepartis_laissentLeProprietaireEntrer() {
        // La contrepartie du test précédent : le garde-fou global ne doit pas se
        // refermer avant son plafond, sans quoi le verrouillage d'autrui
        // reviendrait par la petite porte.
        RateLimiter limiteur = new RateLimiter();
        String cible = "tenace@example.org";

        for (int i = 0; i < 99; i++) {
            limiteur.recordLoginFailure("10.2." + (i / 250) + "." + (i % 250), cible);
        }

        assertThatCode(() -> limiteur.checkLogin("203.0.113.99", cible))
            .doesNotThrowAnyException();
    }

    @Test
    void unSuccesDuProprietaire_effaceLeCoupleEtLeCompteurGlobal() {
        // Une réussite prouve que les échecs précédents n'étaient pas une attaque
        // contre ce compte-là. Laisser le compteur global à quatre-vingt-dix-neuf
        // le laisserait à un essai du refus, pour tout le monde.
        RateLimiter limiteur = new RateLimiter();
        String cible = "moi@example.org";
        for (int i = 0; i < 99; i++) {
            limiteur.recordLoginFailure("10.3." + (i / 250) + "." + (i % 250), cible);
        }

        limiteur.recordLoginSuccess(IP, cible);

        // Cent échecs de plus seraient nécessaires pour refermer : le compteur
        // global est bien reparti de zéro, et pas seulement décrémenté.
        for (int i = 0; i < 99; i++) {
            limiteur.recordLoginFailure("10.4." + (i / 250) + "." + (i % 250), cible);
        }
        assertThatCode(() -> limiteur.checkLogin("203.0.113.55", cible))
            .doesNotThrowAnyException();
    }

    // --------------------------------------------------------- la mémoire bornée

    @Test
    void laCarte_neGardeJamaisPlusDEntreesQueSaBorne() {
        // La carte était un ConcurrentHashMap dont une clé ne partait qu'à la
        // relecture : qui essaie mille adresses e-mail n'en relit aucune, et
        // chacune restait pour la vie du processus. Une voie d'épuisement mémoire
        // à coût nul, sur un conteneur dont le tas se compte en centaines de
        // mégaoctets.
        //
        // La borne éprouvée ici est petite, celle de production ne l'est pas :
        // fabriquer cinquante mille clés dans la suite paierait en mémoire de
        // test ce que cette borne existe pour économiser en production.
        RateLimiter limiteur = new RateLimiter(new HorlogeReglable(), 100);

        for (int i = 0; i < 5_000; i++) {
            limiteur.recordLoginFailure("10.5." + (i / 250) + "." + (i % 250),
                "inconnu" + i + "@example.org");
        }

        assertThat(limiteur.taillePourTests()).isPositive().isLessThanOrEqualTo(100);
    }

    @Test
    void uneCleQuePersonneNeConsultePlus_estOubliee() {
        // Le second mécanisme de la borne : l'expiration. Elle est réglée sur
        // l'horloge du limiteur, pour qu'un test puisse la franchir.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        limiteur.recordLoginFailure(IP, "moi@example.org");
        assertThat(limiteur.taillePourTests()).isPositive();

        horloge.avancer(Duration.ofHours(3));

        assertThat(limiteur.taillePourTests()).isZero();
    }

    // ------------------------------------------ la réinitialisation de mot de passe

    @Test
    void reinitialiserUnMotDePasse_estBorneParAdresse() {
        // La route n'était bornée par rien. Le jeton de 122 bits rend la
        // devinette vaine, mais chaque appel coûte un accès en base et un hachage
        // BCrypt : une boucle y prenait tout le processeur qu'elle voulait.
        RateLimiter limiteur = new RateLimiter();

        for (int i = 0; i < 20; i++) {
            assertThatCode(() -> limiteur.checkResetPasswordAttempt(IP))
                .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiteur.checkResetPasswordAttempt(IP))
            .isInstanceOf(TooManyRequestsException.class)
            .hasMessageContaining("réinitialisation");
        // Une autre connexion n'a rien consommé : le plafond est par adresse.
        assertThatCode(() -> limiteur.checkResetPasswordAttempt("203.0.113.200"))
            .doesNotThrowAnyException();
    }

    @Test
    void leRefusDeReinitialisation_peutAnnoncerMoinsDeCinqSecondes() {
        // À consigner, parce qu'un contrat écrit dit le contraire :
        // modules/session/REPONSE_BACKEND_2026-09-11.md §4 affirme qu'un
        // Retry-After servi par ce limiteur sera « toujours très au-dessus » du
        // seuil de cinq secondes de l'application. Une fenêtre glissante n'a pas
        // cette propriété — le délai rendu est ce qui reste à la plus ancienne
        // tentative retenue, et il tend vers zéro. Le document doit être corrigé
        // sur place ; la route n'étant pas rejouée automatiquement, rien ne casse
        // côté client.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        for (int i = 0; i < 20; i++) {
            limiteur.checkResetPasswordAttempt(IP);
        }
        horloge.avancer(Duration.ofHours(1).minusSeconds(3));

        assertThat(refusDe(() -> limiteur.checkResetPasswordAttempt(IP))
            .getRetryAfterSecondes()).isEqualTo(3);
    }

    // ------------------------------------------------- le délai avant nouvel essai

    /** Le refus attendu, pour pouvoir interroger le délai qu'il porte. */
    private static TooManyRequestsException refusDe(ThrowingCallable appel) {
        Throwable leve = catchThrowable(appel);
        assertThat(leve).isInstanceOf(TooManyRequestsException.class);
        return (TooManyRequestsException) leve;
    }

    @Test
    void leDelai_vautLaFenetreDeLaRoute_quandLeBudgetVientDEtreEpuise() {
        // Dix échecs à la même seconde : la porte rouvre quand le premier sort de
        // la fenêtre, donc un quart d'heure plus tard, jour pour jour.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        for (int i = 0; i < 10; i++) {
            limiteur.recordLoginFailure(IP, "moi@example.org");
        }

        TooManyRequestsException refus = refusDe(() -> limiteur.checkLogin(IP, "moi@example.org"));
        assertThat(refus.getRetryAfterSecondes()).isEqualTo(Duration.ofMinutes(15).toSeconds());
    }

    @Test
    void leDelai_decroitAMesureQueLaFenetreGlisse() {
        // C'est tout l'objet de l'en-tête : le message « dans quelques minutes »
        // dit la même chose à la première minute et à la quatorzième.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        for (int i = 0; i < 10; i++) {
            limiteur.recordLoginFailure(IP, "moi@example.org");
        }

        long precedent = Long.MAX_VALUE;
        for (int minute = 0; minute < 15; minute++) {
            TooManyRequestsException refus =
                refusDe(() -> limiteur.checkLogin(IP, "moi@example.org"));
            long delai = refus.getRetryAfterSecondes();

            assertThat(delai).isLessThan(precedent);
            assertThat(delai).isPositive();
            assertThat(delai).isLessThanOrEqualTo(Duration.ofMinutes(15).toSeconds());
            // Attendre ce que l'en-tête annonce suffit, et exactement : ce qui
            // reste est la fenêtre moins le temps déjà passé devant la porte.
            assertThat(delai).isEqualTo(Duration.ofMinutes(15L - minute).toSeconds());

            precedent = delai;
            horloge.avancer(Duration.ofMinutes(1));
        }

        // La quinzième minute atteinte, le premier échec est sorti de la fenêtre :
        // le dernier délai annoncé — soixante secondes — était donc exact.
        assertThatCode(() -> limiteur.checkLogin(IP, "moi@example.org"))
            .doesNotThrowAnyException();
    }

    @Test
    void leDelai_neTombeJamaisAZeroAuDernierInstantDeLaFenetre() {
        // À un souffle de la réouverture, le temps restant ne fait plus une
        // seconde entière. Zéro est le seul chiffre que l'en-tête ne doit jamais
        // porter — « Retry-After: 0 » invite à réessayer sur-le-champ, soit le
        // contraire de ce qu'on veut dire —, donc on arrondit vers le haut.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        for (int i = 0; i < 10; i++) {
            limiteur.recordLoginFailure(IP, "moi@example.org");
        }
        horloge.avancer(Duration.ofMinutes(15).minusMillis(1));

        TooManyRequestsException refus = refusDe(() -> limiteur.checkLogin(IP, "moi@example.org"));
        assertThat(refus.getDelaiAvantNouvelEssai()).isEqualTo(Duration.ofMillis(1));
        assertThat(refus.getRetryAfterSecondes()).isEqualTo(1);
    }

    @Test
    void attendreExactementLeDelaiAnnonce_suffit() {
        // La seule propriété qui compte vraiment pour le client : ce qu'on lui dit
        // d'attendre est ce qu'il faut attendre. Un chiffre trop court le ferait
        // refuser une fois de plus — le défaut que cet en-tête existe pour
        // supprimer, pas pour déplacer d'un quart d'heure à une seconde.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        for (int i = 0; i < 3; i++) {
            limiteur.checkResendVerification(IP, "cible@example.org");
            horloge.avancer(Duration.ofSeconds(90));
        }

        TooManyRequestsException refus =
            refusDe(() -> limiteur.checkResendVerification(IP, "cible@example.org"));
        horloge.avancer(Duration.ofSeconds(refus.getRetryAfterSecondes()));

        assertThatCode(() -> limiteur.checkResendVerification(IP, "cible@example.org"))
            .doesNotThrowAnyException();
    }

    @Test
    void leDelai_sArrondieALaSecondeSuperieure() {
        // Tronquer rendrait un instant où la porte est encore fermée, donc un
        // refus de plus — précisément ce que cet en-tête existe pour éviter.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        for (int i = 0; i < 10; i++) {
            limiteur.recordLoginFailure(IP, "moi@example.org");
        }
        horloge.avancer(Duration.ofMillis(500));

        TooManyRequestsException refus = refusDe(() -> limiteur.checkLogin(IP, "moi@example.org"));
        assertThat(refus.getDelaiAvantNouvelEssai())
            .isEqualTo(Duration.ofMinutes(15).minusMillis(500));
        assertThat(refus.getRetryAfterSecondes()).isEqualTo(Duration.ofMinutes(15).toSeconds());
    }

    @Test
    void leDelai_vautUneHeureSurLesRoutesDEmail() {
        // La fenêtre de l'inscription et des envois d'e-mail dure une heure : le
        // message parlait bien d'une heure, mais sans dire à partir de quand.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        for (int i = 0; i < 3; i++) {
            limiteur.checkPasswordReset(IP, "cible@example.org");
        }
        horloge.avancer(Duration.ofMinutes(20));

        TooManyRequestsException refus =
            refusDe(() -> limiteur.checkPasswordReset(IP, "cible@example.org"));
        assertThat(refus.getRetryAfterSecondes()).isEqualTo(Duration.ofMinutes(40).toSeconds());
    }

    @Test
    void leDelai_estCeluiDuCompte_quandCEstLeCompteQuiRefuse() {
        // Les deux budgets ne se remplissent pas au même moment : vingt envois
        // depuis un routeur peuvent dater d'une heure moins le quart, et les trois
        // d'une adresse e-mail d'il y a une minute. Annoncer le mauvais des deux,
        // c'est renvoyer le client trop tôt ou beaucoup trop tard.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        // La connexion sature d'abord, avec vingt adresses distinctes.
        for (int i = 0; i < 20; i++) {
            limiteur.checkResendVerification(IP, "passant" + i + "@example.org");
        }

        // Trois quarts d'heure plus tard, une adresse précise sature à son tour,
        // depuis d'autres connexions pour ne pas toucher au budget de celle-ci.
        horloge.avancer(Duration.ofMinutes(45));
        for (int i = 0; i < 3; i++) {
            limiteur.checkResendVerification("198.51.100." + i, "cible@example.org");
        }

        TooManyRequestsException refus =
            refusDe(() -> limiteur.checkResendVerification(IP, "cible@example.org"));

        // Le compte est vérifié le premier, et c'est lui qui bloque : une heure
        // pleine à partir de ses trois envois — et non le quart d'heure qui reste
        // à la connexion.
        assertThat(refus.getRetryAfterSecondes()).isEqualTo(Duration.ofHours(1).toSeconds());
        assertThat(refus.getRetryAfterSecondes())
            .isNotEqualTo(Duration.ofMinutes(15).toSeconds());
    }

    @Test
    void leDelai_estCeluiDeLAdresse_quandCEstLAdresseQuiRefuse() {
        // Le cas symétrique : un balayage a saturé la connexion, le compte visé
        // n'a rien à son compteur, et le délai doit suivre la fenêtre de la
        // connexion — déjà entamée de cinq minutes.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        for (int i = 0; i < 50; i++) {
            limiteur.recordLoginFailure(IP, "cible" + i + "@example.org");
        }
        horloge.avancer(Duration.ofMinutes(5));

        TooManyRequestsException refus =
            refusDe(() -> limiteur.checkLogin(IP, "innocent@example.org"));
        assertThat(refus).hasMessageContaining("connexion");
        assertThat(refus.getRetryAfterSecondes()).isEqualTo(Duration.ofMinutes(10).toSeconds());
    }

    @Test
    void interrogerLaPorte_neRallongePasLeDelaiAnnonce() {
        // Le pendant, côté en-tête, de « un refus ne consomme rien » : un client
        // qui revient trop tôt, ou qui réessaie dix fois, doit s'entendre annoncer
        // le même instant de réouverture. Sans cela l'en-tête serait un piège.
        HorlogeReglable horloge = new HorlogeReglable();
        RateLimiter limiteur = new RateLimiter(horloge);

        for (int i = 0; i < 5; i++) {
            limiteur.checkRegister(IP, "moi@example.org");
        }

        long premier = refusDe(() -> limiteur.checkRegister(IP, "moi@example.org"))
            .getRetryAfterSecondes();
        for (int i = 0; i < 10; i++) {
            assertThat(refusDe(() -> limiteur.checkRegister(IP, "moi@example.org"))
                .getRetryAfterSecondes()).isEqualTo(premier);
        }
        assertThat(premier).isEqualTo(Duration.ofHours(1).toSeconds());
    }
}
