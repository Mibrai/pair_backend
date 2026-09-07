package org.program.pair.shared.security;

import org.program.pair.shared.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le plafond des routes non authentifiées : connexion, inscription, envois d'e-mail
 * déclenchés par un inconnu.
 *
 * <p><b>Une fenêtre glissante, et elle glisse vraiment.</b> Les compteurs
 * précédents étaient de simples entiers qui ne redescendaient jamais : « dix par
 * quinze minutes » était en réalité « dix en tout, pour la durée de vie du
 * processus ». Une quinzaine de connexions légitimes depuis une même adresse
 * suffisait à fermer la porte, et la rouvrir demandait un redéploiement. Ici,
 * chaque clé garde les horodatages de ses tentatives retenues et oublie celles qui
 * sont sorties de la fenêtre ; le budget se reconstitue tout seul, minute après
 * minute.
 *
 * <p><b>Un refus ne prolonge rien.</b> Le compte n'est incrémenté qu'à
 * l'enregistrement d'un échec, jamais à la vérification. Réessayer pour voir si
 * l'attente a suffi ne rallonge donc pas l'attente — le mode de panne le plus
 * ingrat de l'ancien code, parce que rien à l'écran ne pouvait le faire
 * comprendre.
 *
 * <p><b>Deux clés pour la connexion, et deux budgets différents.</b> Une adresse IP
 * n'identifie pas une personne : derrière un partage de connexion, un NAT
 * d'entreprise ou un relais, elle en désigne des dizaines. La borner seule fait
 * qu'un compte en bloque un autre sans que ni l'un ni l'autre n'ait rien fait
 * d'anormal. Le budget serré est donc posé sur le <b>compte</b>, qui est ce qu'une
 * attaque par mot de passe vise ; l'adresse garde un plafond plus large, qui
 * n'existe que pour borner un balayage de plusieurs comptes depuis un même point.
 *
 * <p><b>Seuls les échecs comptent.</b> Une connexion réussie ne consomme rien et
 * vide même le compteur du compte : ce qu'il s'agit de ralentir, c'est la
 * recherche d'un mot de passe, pas l'usage.
 *
 * <p><b>Le couple adresse + connexion vaut pour les quatre routes</b> depuis le
 * 07/09. Il n'était posé que sur la connexion, alors que le raisonnement qui
 * l'avait fait écrire — une adresse IP ne désigne pas une personne — vaut mot
 * pour mot pour l'inscription et pour les envois d'e-mail. Il y valait même
 * davantage : un refus de connexion se réessaie, un refus d'inscription tombe au
 * tout premier geste de quelqu'un qui découvre l'application.
 */
@Component
public class RateLimiter {

    /** Fenêtre commune aux tentatives de connexion. */
    private static final Duration FENETRE_LOGIN = Duration.ofMinutes(15);

    /** Échecs tolérés sur un même compte avant refus. Ce qu'une attaque vise. */
    private static final int ECHECS_PAR_COMPTE = 10;

    /**
     * Échecs tolérés depuis une même adresse. Plus large que le budget par compte :
     * l'adresse est partagée, et la serrer autant punirait des voisins.
     */
    private static final int ECHECS_PAR_ADRESSE = 50;

    private static final Duration FENETRE_INSCRIPTION = Duration.ofHours(1);

    /**
     * Inscriptions tolérées depuis une même connexion.
     *
     * <p><b>Cinq auparavant, et c'était le mauvais nombre au mauvais endroit.</b>
     * Signalé par le chantier mobile le 07/09 : s'inscrire à plusieurs, au même
     * endroit et le même soir, est le mode d'arrivée normal sur meetDo. Derrière
     * un NAT associatif ou un partage de connexion, la sixième personne d'un
     * groupe se voyait refuser la création de son compte — un 429 au tout premier
     * geste, et pour une raison qu'aucun écran ne peut rendre compréhensible.
     * Le budget serré vit désormais sur l'adresse e-mail visée.
     */
    private static final int INSCRIPTIONS_PAR_IP = 30;

    /** Inscriptions tolérées sur une même adresse : au-delà, ce n'est plus une hésitation. */
    private static final int INSCRIPTIONS_PAR_COMPTE = 5;

    private static final Duration FENETRE_EMAIL = Duration.ofHours(1);

    /**
     * E-mails déclenchés depuis une même connexion. Large pour la même raison que
     * ci-dessus : le renvoi est demandé par des gens qui n'ont rien reçu, et
     * trois personnes sur un même réseau consommaient le quota les unes des
     * autres — exactement au moment où l'e-mail manquant les y poussait toutes.
     */
    private static final int EMAILS_PAR_IP = 20;

    /**
     * E-mails déclenchés vers une même adresse. C'est <b>ici</b> que le budget
     * doit être serré : ce qu'il s'agit de borner, c'est le courrier envoyé à
     * quelqu'un, pas le nombre de personnes derrière un routeur.
     */
    private static final int EMAILS_PAR_ADRESSE = 3;

    private final Map<String, Deque<Instant>> compteurs = new ConcurrentHashMap<>();

    /**
     * L'horloge du limiteur.
     *
     * <p>Injectable pour une seule raison : une fenêtre glissante ne se prouve
     * qu'en franchissant son bord, et attendre quinze minutes dans une suite de
     * tests n'est pas une option. C'est ce franchissement qui distingue ce
     * limiteur du précédent — le vérifier valait bien un champ.
     */
    private final Clock horloge;

    public RateLimiter() {
        this(Clock.systemUTC());
    }

    RateLimiter(Clock horloge) {
        this.horloge = horloge;
    }

    /**
     * La porte, avant de tenter la connexion. Ne consomme rien : un appel qui
     * échoue ici ne rapproche personne du refus suivant.
     *
     * @param email le compte visé, tel qu'il est saisi. Peut être nul — la borne
     *              par adresse s'applique alors seule.
     */
    public void checkLogin(String ip, String email) {
        if (email != null && !email.isBlank()
                && depasse(cleCompte(email), ECHECS_PAR_COMPTE, FENETRE_LOGIN)) {
            throw new TooManyRequestsException(
                "Trop de tentatives sur ce compte. Réessayez dans quelques minutes.");
        }
        if (depasse(cleAdresse(ip), ECHECS_PAR_ADRESSE, FENETRE_LOGIN)) {
            throw new TooManyRequestsException(
                "Trop de tentatives depuis cette connexion. Réessayez dans quelques minutes.");
        }
    }

    /** Un mot de passe faux : c'est cela, et cela seul, qui consomme du budget. */
    public void recordLoginFailure(String ip, String email) {
        if (email != null && !email.isBlank()) {
            inscrire(cleCompte(email), FENETRE_LOGIN);
        }
        inscrire(cleAdresse(ip), FENETRE_LOGIN);
    }

    /**
     * Une connexion réussie : le compte repart de zéro.
     *
     * <p>L'adresse, elle, garde ses échecs. Une réussite parmi cinquante essais est
     * exactement ce qu'un balayage produit ; l'effacer serait lui offrir la sortie.
     */
    public void recordLoginSuccess(String email) {
        if (email != null && !email.isBlank()) {
            compteurs.remove(cleCompte(email));
        }
    }

    /**
     * L'inscription, bornée sur l'adresse visée <b>et</b> sur la connexion.
     *
     * <p>Le patron est celui de {@link #checkLogin}, propagé le 07/09 aux trois
     * routes voisines : il y était écrit depuis le 1er septembre, avec le
     * raisonnement sur le NAT, et il n'avait pas quitté la connexion.
     */
    public void checkRegister(String ip, String email) {
        consommerCouple("register", ip, email,
            INSCRIPTIONS_PAR_IP, INSCRIPTIONS_PAR_COMPTE, FENETRE_INSCRIPTION,
            "Trop d'inscriptions. Réessayez dans une heure.");
    }

    /**
     * Renvoi d'un lien de vérification — et demande de changement d'adresse, qui
     * déclenche le même envoi vers une adresse choisie par l'appelant.
     */
    public void checkResendVerification(String ip, String email) {
        consommerCouple("resend", ip, email,
            EMAILS_PAR_IP, EMAILS_PAR_ADRESSE, FENETRE_EMAIL,
            "Trop de demandes. Réessayez dans une heure.");
    }

    public void checkPasswordReset(String ip, String email) {
        consommerCouple("reset", ip, email,
            EMAILS_PAR_IP, EMAILS_PAR_ADRESSE, FENETRE_EMAIL,
            "Trop de demandes. Réessayez dans une heure.");
    }

    /**
     * Remet tous les compteurs à zéro.
     *
     * <p>Réservé aux tests. Ce composant est un singleton du contexte Spring,
     * partagé par toutes les méthodes d'une classe de test d'intégration, et les
     * fenêtres d'inscription durent une heure : sans remise à zéro, la sixième
     * inscription d'une classe échouerait en 429 quelle que soit la méthode qui la
     * demande. {@code AbstractIntegrationTest} appelle donc cette méthode avant
     * chaque test, pour que l'ordre d'exécution de JUnit n'influe sur rien.
     */
    public void reset() {
        compteurs.clear();
    }

    /**
     * Le nombre de clés encore suivies. Réservé aux tests : il n'existe que pour
     * prouver que l'élagage retire bien les clés vidées, sans quoi la carte
     * grossirait d'une entrée par adresse vue et ne rendrait jamais rien.
     */
    int taillePourTests() {
        return compteurs.size();
    }

    // ------------------------------------------------------------------ interne

    private static String cleCompte(String email) {
        return "login:compte:" + email.strip().toLowerCase();
    }

    private static String cleAdresse(String ip) {
        return "login:ip:" + ip;
    }

    /**
     * Les deux clés d'une même route : la connexion, largement ; l'adresse visée,
     * serrée.
     *
     * <p><b>Les deux budgets sont vérifiés avant que l'un ou l'autre ne soit
     * consommé.</b> Consommer au fil de la vérification ferait qu'un refus sur la
     * seconde clé aurait quand même entamé la première — une tentative refusée
     * rapprocherait du refus suivant, ce qui est précisément le mode de panne que
     * le commentaire de tête dit avoir supprimé.
     *
     * <p>L'adresse peut être absente : la borne par connexion s'applique alors
     * seule, ce qui reste le comportement d'avant pour un appelant qui n'en
     * fournit pas.
     */
    private void consommerCouple(String prefixe, String ip, String email,
                                 int budgetIp, int budgetCompte,
                                 Duration fenetre, String message) {
        String cleIp = prefixe + ":ip:" + ip;
        boolean parCompte = email != null && !email.isBlank();
        String cleCompte = parCompte
            ? prefixe + ":compte:" + email.strip().toLowerCase()
            : null;

        if (parCompte && depasse(cleCompte, budgetCompte, fenetre)) {
            throw new TooManyRequestsException(message);
        }
        if (depasse(cleIp, budgetIp, fenetre)) {
            throw new TooManyRequestsException(message);
        }

        if (parCompte) {
            inscrire(cleCompte, fenetre);
        }
        inscrire(cleIp, fenetre);
    }

    private boolean depasse(String cle, int budget, Duration fenetre) {
        Deque<Instant> tentatives = compteurs.get(cle);
        if (tentatives == null) {
            return false;
        }
        synchronized (tentatives) {
            elaguer(tentatives, fenetre);
            // Une clé vidée par l'élagage est retirée : sans cela, la carte
            // grossirait d'une entrée par adresse vue, définitivement.
            if (tentatives.isEmpty()) {
                compteurs.remove(cle, tentatives);
                return false;
            }
            return tentatives.size() >= budget;
        }
    }

    private void inscrire(String cle, Duration fenetre) {
        Deque<Instant> tentatives = compteurs.computeIfAbsent(cle, k -> new ArrayDeque<>());
        synchronized (tentatives) {
            elaguer(tentatives, fenetre);
            tentatives.addLast(horloge.instant());
        }
    }

    private void elaguer(Deque<Instant> tentatives, Duration fenetre) {
        Instant limite = horloge.instant().minus(fenetre);
        while (!tentatives.isEmpty() && tentatives.peekFirst().isBefore(limite)) {
            tentatives.removeFirst();
        }
    }
}
