package org.program.pair.shared.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.program.pair.shared.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Le plafond des routes non authentifiées : connexion, inscription, envois d'e-mail
 * déclenchés par un inconnu, réinitialisation d'un mot de passe.
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
 * <p><b>Trois clés pour la connexion, depuis le 12/09, et c'est le cœur de la
 * fiche P-BS-08.</b> Le budget serré vivait sur le <b>compte</b> seul : dix échecs
 * sur une adresse e-mail fermaient la porte à qui la portait, <b>y compris avec le
 * bon mot de passe et depuis une autre connexion</b>. N'importe qui pouvait donc
 * verrouiller le compte d'un autre sans rien savoir de lui — un déni de service
 * ciblé, à dix requêtes, contre la personne de son choix. Le budget serré porte
 * désormais sur le <b>couple (compte, adresse)</b> : celui qui échoue dix fois ne
 * ferme la porte que devant lui-même, et le propriétaire continue d'entrer depuis
 * chez lui.
 *
 * <p>Le couple seul ne suffirait pas : il rendrait le plafond par compte
 * contournable en changeant d'adresse à chaque dixième essai, ce qu'un réseau de
 * machines fait sans effort. Deux garde-fous plus larges restent donc posés
 * au-dessus, et {@link #checkLogin} les consulte dans cet ordre :
 * <ol>
 *   <li>le <b>couple</b> (compte, adresse) : {@value #ECHECS_PAR_COUPLE} échecs
 *       par quart d'heure. C'est le budget qui arrête une attaque par mot de
 *       passe venue d'un point ;</li>
 *   <li>le <b>compte</b>, largement : {@value #ECHECS_PAR_COMPTE_GLOBAL} échecs
 *       par quart d'heure, tous appelants confondus. Il borne le balayage
 *       distribué. Dix fois le budget du couple, donc hors de portée d'un tiers
 *       qui voudrait nuire depuis une machine ou deux, mais atteint par un
 *       véritable balayage ;</li>
 *   <li>l'<b>adresse</b> : {@value #ECHECS_PAR_ADRESSE} échecs, inchangé. Une
 *       adresse IP ne désigne pas une personne — derrière un NAT d'entreprise ou
 *       un partage de connexion elle en désigne des dizaines —, d'où un plafond
 *       large qui n'existe que pour borner un balayage de plusieurs comptes
 *       depuis un même point.</li>
 * </ol>
 *
 * <p><b>Ce que ce choix de clé ne prétend pas être.</b> Le verrouillage d'un tiers
 * n'est pas rendu impossible, il est rendu coûteux et visible : il faut
 * {@value #ECHECS_PAR_COMPTE_GLOBAL} échecs depuis au moins dix adresses
 * distinctes en un quart d'heure, et l'effet cesse un quart d'heure après le
 * dernier essai. En face, le plafond reste immédiatement efficace là où il sert :
 * qui cherche un mot de passe le cherche depuis quelque part, et dix essais
 * suffisent à l'arrêter.
 *
 * <p><b>Seuls les échecs comptent.</b> Une connexion réussie ne consomme rien et
 * vide même le couple et le compteur global du compte : ce qu'il s'agit de
 * ralentir, c'est la recherche d'un mot de passe, pas l'usage. L'adresse, elle,
 * garde ses échecs — une réussite parmi cinquante essais est exactement ce qu'un
 * balayage produit.
 *
 * <p><b>Le couple adresse + connexion vaut pour les quatre routes</b> depuis le
 * 07/09. Il n'était posé que sur la connexion, alors que le raisonnement qui
 * l'avait fait écrire — une adresse IP ne désigne pas une personne — vaut mot
 * pour mot pour l'inscription et pour les envois d'e-mail. Il y valait même
 * davantage : un refus de connexion se réessaie, un refus d'inscription tombe au
 * tout premier geste de quelqu'un qui découvre l'application.
 *
 * <p><b>Un refus dit quand revenir</b> depuis le 10/09. Il ne portait qu'un
 * message — « dans quelques minutes », « dans une heure » — et c'est le seul
 * endroit du code où l'instant de réouverture soit connaissable : il vaut la
 * sortie de fenêtre de la <b>plus ancienne tentative retenue</b> de la clé qui a
 * refusé, et rien d'autre. Une fenêtre glissante n'a pas de fin commune qu'on
 * pourrait annoncer par une constante ; deux appelants refusés à la même seconde
 * n'attendent pas la même chose. {@link TooManyRequestsException} porte donc ce
 * délai jusqu'à l'en-tête {@code Retry-After} — demande 4 du chantier mobile du
 * 10/09, qui décrivait exactement ce que coûte de le faire deviner : réessayer
 * quand le message le suggère, et se faire refuser autant de fois.
 *
 * <p><b>La mémoire est bornée</b> depuis le 12/09. La carte était un
 * {@code ConcurrentHashMap} dont une clé ne partait qu'à la relecture : les
 * adresses e-mail jamais relues — et qui essaie mille comptes n'en relit aucun —
 * y restaient pour la vie du processus. Une voie d'épuisement mémoire à coût nul
 * pour l'attaquant, et sur un conteneur dont le tas se compte en centaines de
 * mégaoctets. Un cache Caffeine borné la remplace ; voir
 * {@link #TAILLE_MAX} pour le choix de la taille et {@link #DUREE_DE_VIE} pour
 * celui de l'expiration.
 *
 * <p><b>L'adresse retenue est celle que le conteneur voit</b>, c'est-à-dire ce que
 * rend {@code HttpServletRequest.getRemoteAddr()} chez l'appelant. Derrière un
 * proxy, ce n'est celle du client que si un proxy <b>de confiance</b> l'a établie :
 * voir {@code config/ProxyDeConfiance} et
 * {@code domain/auth/AuthController#adresseAppelante}. Ce limiteur ne choisit pas
 * l'adresse, il lui fait confiance ; c'est pour cela que la question est traitée
 * là-bas et non ici.
 *
 * <p><b>Ce limiteur est local à l'instance.</b> Ses compteurs vivent dans le tas
 * du processus : deux répliques ont deux budgets, et un redémarrage remet tout à
 * zéro. Tant que Railway n'en fait tourner qu'une, cela ne se voit pas. Le jour
 * où il y en aura deux, les plafonds seront à multiplier par le nombre de
 * répliques, et le remède — Redis ou Bucket4j derrière un magasin partagé —
 * sort de la fiche P-BS-08 : elle le dit à son étape 7, et c'est ici qu'il faut
 * l'avoir lu.
 */
@Component
public class RateLimiter {

    /** Fenêtre commune aux tentatives de connexion. */
    private static final Duration FENETRE_LOGIN = Duration.ofMinutes(15);

    /**
     * Échecs tolérés sur un même couple (compte, adresse) avant refus.
     *
     * <p>C'est l'ancien budget par compte, à la clé près, et c'est ce
     * déplacement-là qui referme le verrouillage d'autrui : le chiffre qui arrête
     * une attaque par mot de passe n'a jamais eu besoin d'être global, puisqu'une
     * attaque se mène depuis quelque part.
     */
    private static final int ECHECS_PAR_COUPLE = 10;

    /**
     * Échecs tolérés sur un même compte, tous appelants confondus.
     *
     * <p>Le garde-fou contre le balayage distribué, et lui seul : dix fois le
     * budget du couple, pour qu'un tiers ne puisse pas verrouiller un compte à
     * peu de frais tout en bornant celui qui dispose de cent machines.
     */
    private static final int ECHECS_PAR_COMPTE_GLOBAL = 100;

    /**
     * Échecs tolérés depuis une même adresse. Plus large que le budget par couple :
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

    private static final Duration FENETRE_RESET = Duration.ofHours(1);

    /**
     * Lectures de « déjà pratiquée près de toi » tolérées par compte.
     *
     * <p>La route est appelée au fil de la frappe, quelques fois par activité
     * créée : trente lectures en dix minutes y suffisent largement. Au-delà, ce
     * n'est plus quelqu'un qui écrit un nom, c'est quelqu'un qui balaie des
     * positions — exactement ce que le seuil et l'arrondi de la route rendent
     * déjà peu rentable, et ce que ce plafond borne en volume.
     */
    private static final int PRATIQUE_PROCHE_PAR_COMPTE = 30;

    private static final Duration FENETRE_PRATIQUE_PROCHE = Duration.ofMinutes(10);

    /**
     * Présentations d'un jeton de réinitialisation tolérées depuis une même
     * connexion.
     *
     * <p>La route n'était bornée par rien : le jeton de 122 bits rend la
     * devinette vaine, mais rien n'empêchait d'y adosser une boucle — chaque
     * appel coûte un accès en base et un hachage BCrypt, c'est-à-dire du temps de
     * processeur qu'on ne rend pas aux autres requêtes.
     *
     * <p>Le budget porte sur l'adresse seule, et il est large : le compte visé
     * n'est pas connaissable ici (l'appelant présente un jeton, pas une adresse
     * e-mail), et un plafond serré ferait échouer la personne qui recommence
     * parce que son nouveau mot de passe est refusé par les règles de forme.
     * Vingt essais par heure laissent la place à cette maladresse et rien de
     * plus.
     */
    private static final int RESETS_PAR_IP = 20;

    /**
     * Le nombre maximal de clés suivies simultanément.
     *
     * <p><b>Le chiffre est un compromis de mémoire, pas une limite de trafic.</b>
     * Une entrée pèse la clé (une soixantaine de caractères) plus une file
     * d'horodatages qui va de un à cinquante {@link Instant}, plus la
     * comptabilité du cache : de l'ordre de 300 octets en usage normal, jusqu'à
     * un kilo et demi pour une clé au plafond. Cinquante mille entrées tiennent
     * donc dans une quinzaine de mégaoctets au repos et une soixantaine dans le
     * pire cas imaginable. La fiche P-BS-08 proposait 200 000 : quatre fois plus,
     * soit jusqu'à 300 Mo sur un conteneur qui n'en a pas tant à donner — et
     * l'intérêt marginal est faible, le nombre d'adresses et de comptes
     * réellement actifs par quart d'heure étant de plusieurs ordres de grandeur
     * en dessous.
     *
     * <p><b>Ce que l'éviction fait quand la borne est atteinte, et pourquoi c'est
     * le bon sens.</b> Caffeine évince selon la fréquence d'usage (W-TinyLFU), et
     * non par ancienneté : sous une inondation de clés vues une seule fois — ce
     * que produit exactement un balayage d'adresses e-mail aléatoires —, ce sont
     * ces clés-là qui partent, pas celles d'un compte réellement attaqué, qui est
     * relu à chaque essai. L'éviction est ouvrante (perdre une clé rend du
     * budget) : elle doit donc tomber sur les clés dont le budget n'est pas en
     * jeu, et c'est le cas.
     */
    private static final long TAILLE_MAX = 50_000L;

    /**
     * L'âge au-delà duquel une clé non consultée est oubliée.
     *
     * <p>Deux heures, soit le double de la plus longue fenêtre. Le sens de la
     * marge est ce qui compte : expirer trop tôt <b>rendrait du budget</b> à qui
     * n'y a pas droit, alors qu'expirer tard ne coûte que de la mémoire déjà
     * bornée par {@link #TAILLE_MAX}. Une clé dont la fenêtre est encore vivante
     * a forcément été écrite dans l'heure, donc consultée dans l'heure : elle ne
     * peut pas sortir par cette porte.
     *
     * <p>Sur l'accès et non sur l'écriture, pour que consulter une clé sans
     * l'incrémenter — ce que fait chaque vérification — suffise à la garder.
     */
    private static final Duration DUREE_DE_VIE = Duration.ofHours(2);

    /**
     * Les compteurs, bornés en nombre et en durée.
     *
     * <p>Un {@code ConcurrentHashMap} jusqu'au 12/09, et une fuite : une clé n'en
     * partait qu'à la relecture, or qui essaie mille adresses e-mail n'en relit
     * aucune. Voir {@link #TAILLE_MAX} et {@link #DUREE_DE_VIE}.
     *
     * <p>L'élagage de la fenêtre reste fait à la main, par
     * {@link #elaguer(Deque, Duration)} : ce n'est pas le cache qui compte les
     * tentatives, il ne fait que borner le nombre de files qu'on garde. Les deux
     * mécanismes ne se recouvrent pas — l'un décide du budget, l'autre de la
     * mémoire.
     */
    private final Cache<String, Deque<Instant>> compteurs;

    /**
     * L'horloge du limiteur.
     *
     * <p>Injectable pour une seule raison : une fenêtre glissante ne se prouve
     * qu'en franchissant son bord, et attendre quinze minutes dans une suite de
     * tests n'est pas une option. C'est ce franchissement qui distingue ce
     * limiteur du précédent — le vérifier valait bien un champ.
     *
     * <p>Elle règle aussi l'expiration du cache, par le {@code Ticker} construit
     * plus bas : sans cela, avancer l'horloge de trois heures dans un test
     * laisserait les entrées en place et l'expiration ne serait jamais éprouvée.
     */
    private final Clock horloge;

    public RateLimiter() {
        this(Clock.systemUTC(), TAILLE_MAX);
    }

    RateLimiter(Clock horloge) {
        this(horloge, TAILLE_MAX);
    }

    /**
     * @param tailleMax la borne du cache. Réglable pour les tests seulement :
     *                  éprouver la vraie borne demanderait de fabriquer cinquante
     *                  mille clés, donc de payer en mémoire de suite ce que cette
     *                  borne existe pour économiser en production.
     */
    RateLimiter(Clock horloge, long tailleMax) {
        this.horloge = horloge;
        this.compteurs = Caffeine.newBuilder()
            .maximumSize(tailleMax)
            .expireAfterAccess(DUREE_DE_VIE)
            .ticker(this::tic)
            // L'entretien du cache — éviction et expiration — se fait sur le fil
            // appelant plutôt que sur le pool commun. Le travail est minuscule
            // (quelques files retirées d'une carte), et cela rend l'éviction
            // observable au retour de l'appel : sans cela, un test qui vient
            // d'écrire mille clés ne pourrait pas affirmer qu'il n'en reste que
            // cent, l'entretien étant encore en vol.
            .executor(Runnable::run)
            .build();
    }

    /** L'horloge du limiteur, telle que Caffeine la lit : des nanosecondes croissantes. */
    private long tic() {
        Instant maintenant = horloge.instant();
        return maintenant.getEpochSecond() * 1_000_000_000L + maintenant.getNano();
    }

    /**
     * La porte, avant de tenter la connexion. Ne consomme rien : un appel qui
     * échoue ici ne rapproche personne du refus suivant.
     *
     * <p>Les trois budgets sont consultés du plus serré au plus large, et le
     * premier qui refuse décide du message comme du délai. L'ordre est celui de
     * la précision : un refus qui vient du couple parle de « ce compte depuis
     * cette connexion », un refus qui vient de l'adresse parle de « cette
     * connexion » — deux choses différentes pour qui lit l'écran.
     *
     * @param email le compte visé, tel qu'il est saisi. Peut être nul — la borne
     *              par adresse s'applique alors seule.
     */
    public void checkLogin(String ip, String email) {
        if (estRenseigne(email)) {
            Duration delaiCouple =
                delaiSiAuPlafond(cleCouple(email, ip), ECHECS_PAR_COUPLE, FENETRE_LOGIN);
            if (delaiCouple != null) {
                throw new TooManyRequestsException(
                    "Trop de tentatives sur ce compte depuis cette connexion. "
                        + "Réessayez dans quelques minutes.", delaiCouple);
            }
            Duration delaiCompte =
                delaiSiAuPlafond(cleCompte(email), ECHECS_PAR_COMPTE_GLOBAL, FENETRE_LOGIN);
            if (delaiCompte != null) {
                throw new TooManyRequestsException(
                    "Trop de tentatives sur ce compte. Réessayez dans quelques minutes.",
                    delaiCompte);
            }
        }
        Duration delai = delaiSiAuPlafond(cleAdresse(ip), ECHECS_PAR_ADRESSE, FENETRE_LOGIN);
        if (delai != null) {
            throw new TooManyRequestsException(
                "Trop de tentatives depuis cette connexion. Réessayez dans quelques minutes.", delai);
        }
    }

    /** Un mot de passe faux : c'est cela, et cela seul, qui consomme du budget. */
    public void recordLoginFailure(String ip, String email) {
        if (estRenseigne(email)) {
            inscrire(cleCouple(email, ip), FENETRE_LOGIN);
            inscrire(cleCompte(email), FENETRE_LOGIN);
        }
        inscrire(cleAdresse(ip), FENETRE_LOGIN);
    }

    /**
     * Une connexion réussie : le compte repart de zéro, ici et globalement.
     *
     * <p>Les deux clés du compte sont effacées, pas seulement celle de la
     * connexion d'où la réussite vient. Un propriétaire qui finit par entrer
     * prouve que les échecs précédents n'étaient pas une attaque contre lui, et
     * laisser le compteur global à quatre-vingt-dix-neuf le laisserait à un essai
     * du refus pour tout le monde.
     *
     * <p>L'adresse, elle, garde ses échecs. Une réussite parmi cinquante essais est
     * exactement ce qu'un balayage produit ; l'effacer serait lui offrir la sortie.
     */
    public void recordLoginSuccess(String ip, String email) {
        if (estRenseigne(email)) {
            compteurs.invalidate(cleCouple(email, ip));
            compteurs.invalidate(cleCompte(email));
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
     * La présentation d'un jeton de réinitialisation, bornée sur la connexion
     * seule — voir {@link #RESETS_PAR_IP} pour la raison.
     *
     * <p>À ne pas confondre avec {@link #checkPasswordReset}, qui borne la
     * <b>demande</b> d'un lien ({@code /forgot-password}) et connaît, elle,
     * l'adresse e-mail visée. Celle-ci borne la <b>consommation</b> du lien
     * ({@code /reset-password}), où l'appelant ne présente qu'un jeton.
     *
     * <p>Consomme à l'appel, comme les routes d'e-mail : il n'y a pas ici de
     * notion d'issue à attendre, et c'est le coût de l'appel lui-même — un accès
     * en base et un hachage — qu'il s'agit de borner.
     */
    public void checkResetPasswordAttempt(String ip) {
        consommerSeul("reset-token:ip:" + ip, RESETS_PAR_IP, FENETRE_RESET,
            "Trop de tentatives de réinitialisation. Réessayez dans une heure.");
    }

    /** Voir {@link #PRATIQUE_PROCHE_PAR_COMPTE}. Budget par compte : la route exige une session. */
    public void checkPractisedNearby(java.util.UUID userId) {
        consommerSeul("practised-nearby:" + userId, PRATIQUE_PROCHE_PAR_COMPTE, FENETRE_PRATIQUE_PROCHE,
            "Trop de lectures. Réessayez dans quelques minutes.");
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
        compteurs.invalidateAll();
    }

    /**
     * Le nombre de clés encore suivies, entretien du cache fait.
     *
     * <p>Réservé aux tests, et il en prouve deux choses : que l'élagage retire
     * bien les clés vidées, et que le cache ne dépasse pas sa borne. Le
     * {@code cleanUp()} est indispensable au second : l'éviction de Caffeine est
     * amortie, et la taille estimée peut dépasser la borne jusqu'à ce que
     * l'entretien passe.
     */
    int taillePourTests() {
        compteurs.cleanUp();
        return (int) compteurs.estimatedSize();
    }

    // ------------------------------------------------------------------ interne

    private static boolean estRenseigne(String email) {
        return email != null && !email.isBlank();
    }

    private static String normaliser(String email) {
        return email.strip().toLowerCase();
    }

    /**
     * La clé du budget serré de la connexion : le compte <b>et</b> le point d'où
     * on l'essaie.
     *
     * <p>Le séparateur ne peut pas apparaître dans une adresse e-mail valide, ce
     * qui interdit de fabriquer une adresse qui se lirait comme le couple d'une
     * autre.
     */
    private static String cleCouple(String email, String ip) {
        return "login:couple:" + normaliser(email) + "|" + ip;
    }

    private static String cleCompte(String email) {
        return "login:compte:" + normaliser(email);
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
     *
     * <p><b>Le délai annoncé est celui de la clé qui a refusé</b>, pas le plus
     * grand ni le plus petit des deux. Les deux budgets se remplissent à des
     * moments différents — trente inscriptions depuis un routeur ne datent pas de
     * la même minute que les cinq d'une adresse e-mail — et seule la clé qui
     * bloque décide de l'instant où elle cesse de bloquer.
     */
    private void consommerCouple(String prefixe, String ip, String email,
                                 int budgetIp, int budgetCompte,
                                 Duration fenetre, String message) {
        String cleIp = prefixe + ":ip:" + ip;
        boolean parCompte = estRenseigne(email);
        String cleCompte = parCompte
            ? prefixe + ":compte:" + normaliser(email)
            : null;

        if (parCompte) {
            Duration delai = delaiSiAuPlafond(cleCompte, budgetCompte, fenetre);
            if (delai != null) {
                throw new TooManyRequestsException(message, delai);
            }
        }
        Duration delaiIp = delaiSiAuPlafond(cleIp, budgetIp, fenetre);
        if (delaiIp != null) {
            throw new TooManyRequestsException(message, delaiIp);
        }

        if (parCompte) {
            inscrire(cleCompte, fenetre);
        }
        inscrire(cleIp, fenetre);
    }

    /** Une seule clé, vérifiée puis consommée. */
    private void consommerSeul(String cle, int budget, Duration fenetre, String message) {
        Duration delai = delaiSiAuPlafond(cle, budget, fenetre);
        if (delai != null) {
            throw new TooManyRequestsException(message, delai);
        }
        inscrire(cle, fenetre);
    }

    /**
     * Le temps restant avant que cette clé retrouve du budget, ou {@code null} si
     * elle n'est pas au plafond.
     *
     * <p>Remplace le {@code depasse} booléen du 1er septembre : la réponse était
     * déjà calculée ici, puisqu'il faut élaguer pour comparer, et l'appelant n'en
     * gardait que le oui/non. Le délai juste est la sortie de fenêtre de la
     * <b>plus ancienne tentative retenue</b> : c'est elle qui, en partant, rend
     * la place qui manque, et une seule suffit puisque le refus se déclenche à
     * l'égalité avec le budget.
     *
     * <p><b>Toujours pas de consommation.</b> Rien n'est inscrit ici, et
     * l'élagage ne retire que ce qui est déjà hors fenêtre : demander « est-ce
     * encore fermé ? », même cent fois, ne rapproche personne du refus suivant ni
     * n'allonge le délai rendu. C'est la propriété que la tête de classe promet,
     * et elle est ce qui rend l'en-tête {@code Retry-After} honnête — un client
     * qui interroge avant l'heure ne se punit pas.
     */
    private Duration delaiSiAuPlafond(String cle, int budget, Duration fenetre) {
        Deque<Instant> tentatives = compteurs.getIfPresent(cle);
        if (tentatives == null) {
            return null;
        }
        synchronized (tentatives) {
            elaguer(tentatives, fenetre);
            // Une clé vidée par l'élagage est retirée tout de suite : le cache la
            // retirerait de lui-même, mais deux heures plus tard, et la garder
            // d'ici là occuperait une place que la borne compte.
            if (tentatives.isEmpty()) {
                compteurs.asMap().remove(cle, tentatives);
                return null;
            }
            if (tentatives.size() < budget) {
                return null;
            }
            // Le bord de fenêtre étant sortant (voir elaguer), attendre exactement
            // cette durée suffit : à cet instant-là, la plus ancienne tentative
            // est oubliée et la place est rendue. Le reste — l'arrondi à la
            // seconde et le plancher qui interdit un zéro — appartient à
            // TooManyRequestsException, parce que c'est là que se décide ce qui
            // s'écrit dans l'en-tête.
            return Duration.between(horloge.instant(), tentatives.peekFirst().plus(fenetre));
        }
    }

    private void inscrire(String cle, Duration fenetre) {
        Deque<Instant> tentatives =
            compteurs.asMap().computeIfAbsent(cle, k -> new ArrayDeque<>());
        synchronized (tentatives) {
            elaguer(tentatives, fenetre);
            tentatives.addLast(horloge.instant());
        }
    }

    /**
     * Oublie les tentatives sorties de la fenêtre.
     *
     * <p><b>Le bord est sortant depuis le 10/09</b> : une tentative vieille
     * d'exactement une fenêtre n'y est plus. Elle y restait — la comparaison était
     * un {@code isBefore} strict —, et l'écart d'un instant n'a jamais eu d'effet
     * visible tant que le refus ne disait rien. Il en a un depuis que nous
     * annonçons un {@code Retry-After} : le délai calculé ici est celui qu'un
     * client va attendre au chronomètre, et avec l'ancien bord, attendre
     * exactement ce qu'on lui annonce le faisait refuser une fois de plus — le
     * défaut même que cet en-tête existe pour supprimer, déplacé d'un quart
     * d'heure à une seconde. Le budget ne s'en trouve élargi que d'un instant.
     */
    private void elaguer(Deque<Instant> tentatives, Duration fenetre) {
        Instant limite = horloge.instant().minus(fenetre);
        while (!tentatives.isEmpty() && !tentatives.peekFirst().isAfter(limite)) {
            tentatives.removeFirst();
        }
    }
}
