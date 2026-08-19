package org.program.pair.domain.block;

/**
 * Le prédicat de blocage, écrit une fois.
 *
 * <p>Sept requêtes de visibilité doivent l'appliquer — carte, fil des créneaux,
 * fil des cartes-souvenirs, recherche de programmes, recherche de personnes. Le
 * recopier sept fois garantirait qu'une des sept diverge un jour, et le dépôt a
 * déjà rencontré ce mode de panne : la javadoc de {@code SlotRecapRepository}
 * demande explicitement de ne pas laisser sa requête s'éloigner de celle du fil
 * des créneaux.
 *
 * <p><b>Pourquoi en SQL et pas en post-filtrage.</b> Trois surfaces ne
 * survivraient pas à un filtre appliqué après coup : {@code /map/bounds} calcule
 * sa troncature depuis un {@code COUNT} séparé et annoncerait un total qu'elle
 * ne rend pas, {@code /api/search} pagine en mémoire avec des compteurs
 * d'onglets portant sur la requête entière, et les fils bornés par un
 * {@code LIMIT} rendraient des pages qui rétrécissent sans le dire. Le filtre
 * doit donc entrer dans le {@code WHERE}, et dans le {@code COUNT} qui
 * l'accompagne.
 *
 * <p>Les deux sens sont testés ensemble : le blocage n'est enregistré que dans
 * un sens, mais il s'applique dans les deux. Les index {@code idx_blocks_blocker}
 * et {@code idx_blocks_blocked} (V62) existent pour cette double condition.
 *
 * <p>Chaque constante nomme la colonne qui porte l'<i>autre</i> personne, et
 * attend un paramètre {@code :viewerId} — l'appelant. Un {@code viewerId} nul
 * neutralise le prédicat : sans identité, il n'y a personne à masquer, et une
 * route publique doit continuer de fonctionner.
 */
public final class BlockSql {

    private BlockSql() {}

    /** Pour une requête qui joint {@code users u} — la personne regardée est {@code u.id}. */
    public static final String NOT_BLOCKED_U = """
         AND (:viewerId IS NULL OR NOT EXISTS (
             SELECT 1 FROM user_blocks ub
             WHERE (ub.blocker_id = :viewerId AND ub.blocked_id = u.id)
                OR (ub.blocker_id = u.id AND ub.blocked_id = :viewerId)
         ))
        """;

    /** Pour une requête qui passe par {@code user_activities ua} sans joindre {@code users}. */
    public static final String NOT_BLOCKED_UA_USER = """
         AND (:viewerId IS NULL OR NOT EXISTS (
             SELECT 1 FROM user_blocks ub
             WHERE (ub.blocker_id = :viewerId AND ub.blocked_id = ua.user_id)
                OR (ub.blocker_id = ua.user_id AND ub.blocked_id = :viewerId)
         ))
        """;
}
