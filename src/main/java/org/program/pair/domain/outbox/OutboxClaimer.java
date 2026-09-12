package org.program.pair.domain.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.shared.observabilite.MetriquesOutbox;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Réclame un lot de messages à envoyer : il les marque comme pris, et rend leurs
 * identifiants.
 *
 * <p><b>Pourquoi un bean à part.</b> {@code OutboxService.dispatchPending} n'a
 * plus de transaction — c'est ce qui permet d'appeler le fournisseur sans tenir
 * de connexion. Il faut donc que la réclamation, elle, en ait une, et qu'elle
 * soit <b>validée avant</b> l'appel au fournisseur. Une méthode
 * {@code @Transactional} appelée depuis la même classe ne passe pas par le proxy
 * Spring et n'ouvre aucune transaction : d'où un bean distinct, et une méthode
 * publique.
 *
 * <p><b>Ce que la réclamation garantit.</b> Elle est le seul point d'entrée du
 * lot, et elle fait trois choses d'un seul {@code UPDATE} :
 * <ul>
 *   <li>elle pose {@link OutboxStatus#SENDING} et une échéance de verrou, donc
 *       aucun autre balayage ne verra ces lignes comme à envoyer — c'est ce qui
 *       ferme le doublon que deux instances produisaient en lisant les mêmes
 *       {@code PENDING} ;</li>
 *   <li>elle <b>compte l'essai tout de suite</b>, avant le moindre appel réseau.
 *       Un conteneur tué en plein envoi consomme donc un essai. C'est ce qui
 *       borne la remise « au moins une fois » : sans ce comptage, un message qui
 *       fait tomber le processus serait repris sans fin, et la boucle
 *       recommencerait à chaque redéploiement ;</li>
 *   <li>elle reprend les messages dont le verrou a expiré, ce qui rend le
 *       nettoyage inutile : un {@code SENDING} abandonné redevient éligible tout
 *       seul deux minutes plus tard.</li>
 * </ul>
 *
 * <p><b>{@code FOR UPDATE SKIP LOCKED}, et non {@code FOR UPDATE}.</b> Deux
 * balayages simultanés ne doivent pas s'attendre : celui qui arrive second saute
 * les lignes déjà verrouillées et prend les suivantes. Avec un {@code FOR UPDATE}
 * simple, il resterait bloqué le temps du premier {@code UPDATE} puis
 * relirait des lignes devenues {@code SENDING} — donc rien.
 *
 * <p><b>{@code JdbcTemplate} et non JPA.</b> {@code RETURNING} n'est pas pris en
 * charge sous {@code @Modifying} : une requête JPA modifiante rend un compte de
 * lignes, jamais les lignes. Or c'est exactement ce qu'il faut ici — les
 * identifiants que <i>ce</i> balayage a pris, et aucun autre. Les relire par une
 * seconde requête {@code WHERE status = 'SENDING'} rendrait aussi ceux d'un
 * balayage voisin, et l'exclusivité serait perdue.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxClaimer {

    /**
     * Le temps qu'un balayage a pour envoyer ce qu'il a réclamé.
     *
     * <p>Deux minutes : très au-delà du budget d'un appel fournisseur (quelques
     * secondes, et le {@code WebClient} de Resend a son propre délai), et bien en
     * dessous du premier délai entre essais (30 s) multiplié par ce qu'il faut
     * pour qu'un redémarrage se remarque. Trop court, deux balayages enverraient
     * le même message en parallèle ; trop long, un conteneur tué immobiliserait
     * une alerte d'autant.
     *
     * <p><b>Le lot est envoyé en séquence</b>, et le verrou est posé sur toutes
     * ses lignes au même instant : un lot de cinquante messages dispose donc de
     * deux minutes <i>en tout</i>, soit un peu plus de deux secondes par appel
     * fournisseur. Si un fournisseur devenait lent au point de dépasser ce
     * budget, les dernières lignes du lot pourraient être reprises par un autre
     * balayage avant d'avoir été envoyées — un doublon, jamais une perte. C'est
     * la borne à relever (ou le lot à réduire) si les journaux montrent des
     * confirmations tardives ignorées.
     */
    public static final Duration VERROU = Duration.ofMinutes(2);

    private final JdbcTemplate jdbc;

    /**
     * Ce que la réclamation retient est mesuré : l'immobilité de ce compteur dit
     * « plus aucun balayage ne tourne », ce qu'aucun compteur d'échec ne dirait.
     */
    private final MetriquesOutbox metriques;

    /**
     * Les messages à envoyer, marqués comme pris.
     *
     * <p>L'ordre est celui de l'index partiel {@code idx_outbox_a_envoyer} :
     * priorité croissante puis ancienneté — une alerte passe devant un e-mail de
     * vérification.
     *
     * <p>Deux branches, et deux seulement :
     * <ul>
     *   <li>un {@code PENDING} dont l'heure du prochain essai est venue.
     *       {@code next_attempt_at} nul vaut « tout de suite », d'où le
     *       {@code coalesce} sur {@code created_at} : c'est le cas d'un message
     *       qui vient d'être déposé, et celui des messages déjà en file au
     *       déploiement qui a ajouté la colonne ;</li>
     *   <li>un {@code SENDING} dont le verrou est dépassé. Le {@code coalesce}
     *       sur {@code created_at} n'est pas décoratif : une ligne passée en
     *       {@code SENDING} à la main, sans échéance, serait sinon bloquée pour
     *       toujours.</li>
     * </ul>
     */
    private static final String RECLAMER = """
        UPDATE outbox_messages
           SET status = 'SENDING',
               locked_until = ?,
               attempts = attempts + 1,
               last_attempt_at = ?
         WHERE id IN (SELECT id
                        FROM outbox_messages
                       WHERE (status = 'PENDING' AND coalesce(next_attempt_at, created_at) <= ?)
                          OR (status = 'SENDING' AND coalesce(locked_until, created_at) < ?)
                       ORDER BY priority, created_at
                       LIMIT ?
                         FOR UPDATE SKIP LOCKED)
        RETURNING id
        """;

    /**
     * Prend jusqu'à {@code lot} messages et rend leurs identifiants, dans l'ordre
     * où ils doivent partir.
     *
     * <p><b>{@code now} est passé, et non lu en base.</b> Le reste de l'outbox
     * calcule ses dates avec l'horloge de l'application —
     * {@code next_attempt_at}, {@code sent_at} — et mélanger les deux horloges
     * ferait dépendre l'éligibilité d'un message de la dérive entre le service et
     * la base, qui ne sont pas dans la même région (le service en Europe, la base
     * à San Francisco). Une seule horloge, donc, et celle qui écrit les dates
     * qu'on compare. C'est aussi ce qui permet aux tests de rejouer une panne de
     * trente minutes sans attendre trente minutes.
     *
     * @param lot le nombre maximal de messages à prendre
     * @param now l'instant du balayage
     * @return les identifiants réclamés — vide si rien n'est à envoyer
     */
    @Transactional
    public List<UUID> reclamer(int lot, Instant now) {
        OffsetDateTime maintenant = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        OffsetDateTime finDuVerrou = OffsetDateTime.ofInstant(now.plus(VERROU), ZoneOffset.UTC);

        List<UUID> ids = jdbc.queryForList(RECLAMER, UUID.class,
            finDuVerrou, maintenant, maintenant, maintenant, lot);

        metriques.messagesReclames(ids.size());
        if (!ids.isEmpty()) {
            log.debug("Outbox : {} message(s) réclamé(s) pour {} s", ids.size(), VERROU.toSeconds());
        }
        return ids;
    }
}
