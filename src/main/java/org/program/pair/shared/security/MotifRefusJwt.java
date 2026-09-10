package org.program.pair.shared.security;

/**
 * Pourquoi {@link JwtAuthFilter} n'a pas authentifié, alors qu'un jeton
 * {@code Bearer} lui était bien présenté.
 *
 * <p><b>À quoi cela sert.</b> Le point d'entrée d'authentification de
 * {@code SecurityConfig} est appelé <i>après</i> le filtre, et ne reçoit qu'une
 * requête sans authentification : il ne peut pas savoir si le jeton manquait,
 * s'il était illisible, ou s'il était simplement périmé. Il rendait donc le
 * même {@code UNAUTHORIZED} dans tous les cas — et le client mobile, ne pouvant
 * pas trancher, rafraîchissait à chaque 401, y compris ceux qu'un
 * rafraîchissement ne répare pas. Le filtre pose donc ici ce qu'il sait, sous
 * la clé {@link #ATTRIBUT}, et le point d'entrée n'a plus qu'à le lire.
 *
 * <p><b>L'attribut n'est posé que si un jeton était présent.</b> Son absence
 * signifie « aucun en-tête {@code Authorization: Bearer} » — c'est le cas d'un
 * appel non authentifié tout court, qui n'a rien à voir avec une session
 * finie et n'appelle donc aucun traitement particulier.
 */
public enum MotifRefusJwt {

    /**
     * Le jeton était des nôtres, son échéance est passée. <b>Le seul motif qui
     * vaille un rafraîchissement silencieux côté client</b> : la session est
     * intacte, c'est le jeton court qui a fait son temps.
     */
    EXPIRE,

    /** Illisible, mal signé, ou pour un compte qui n'ouvre plus. Rien à rafraîchir. */
    INVALIDE,

    /**
     * Un jeton de rafraîchissement présenté en {@code Bearer} sur une route
     * ordinaire.
     *
     * <p>Cela marchait, et c'est le défaut que ce lot ferme : le claim
     * {@code type} n'était lu nulle part, si bien qu'un secret de trente jours
     * ouvrait ce que le quart d'heure du jeton d'accès devait borner. Distingué
     * des deux autres parce qu'il ne désigne ni une session finie ni une panne,
     * mais un client qui envoie le mauvais jeton — un état dont on veut pouvoir
     * lire la trace le jour où il se produit en production.
     */
    JETON_DE_RAFRAICHISSEMENT;

    /**
     * La clé sous laquelle le filtre pose le motif sur la requête, à lire avec
     * {@code request.getAttribute(MotifRefusJwt.ATTRIBUT)}. Nommée par la classe
     * elle-même pour qu'aucun autre attribut de requête ne puisse la heurter.
     */
    public static final String ATTRIBUT = "org.program.pair.shared.security.MotifRefusJwt";
}
